package ir.bazr.inspector

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class InspectorViewModel(app: Application) : AndroidViewModel(app) {
    val store = SecureStore(app)
    private val crypto = DeviceCrypto()
    private val api = ApiClient(store, crypto)

    var screen by mutableStateOf("login")
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<String?>(null)
        private set
    var snapshot by mutableStateOf<SyncSnapshot?>(null)
        private set
    var selectedMission by mutableStateOf<Mission?>(null)
        private set
    var draftTick by mutableIntStateOf(0)
        private set

    private var sessionToken: String? = null
    private val drafts = mutableMapOf<String, MissionDraft>()

    val biometricEnabled: Boolean get() = store.biometricEnabled && store.hasBiometricPayload
    val canAutoResume: Boolean get() = !store.biometricEnabled && store.loadDeviceToken() != null

    init {
        store.cachedSyncJson?.let { raw -> runCatching { snapshot = parseSyncSnapshot(raw) } }
        if (store.apiBaseUrl.isNotBlank() && canAutoResume) {
            store.loadDeviceToken()?.let { resumeWithDeviceToken(it, silent = true) }
        }
    }

    fun clearMessage() { error = null; info = null }

    fun login(apiBase: String, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) { error = "نام کاربری و رمز عبور الزامی است"; return }
        val normalized = SecureStore.normalizeBase(apiBase)
        if (!normalized.startsWith("https://")) { error = "برای امنیت، آدرس سرور باید HTTPS باشد"; return }
        store.apiBaseUrl = normalized
        busy = true; error = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.passwordLogin(username.trim(), password) }
            }.onSuccess { r ->
                store.saveDeviceToken(r.deviceToken)
                store.profileJson = r.profileJson
                sessionToken = r.sessionToken
                screen = "home"
                info = "ورود با موفقیت انجام شد"
                sync(silent = true)
            }.onFailure { error = friendly(it) }
            busy = false
        }
    }

    fun resumeWithDeviceToken(token: String, silent: Boolean = false) {
        if (store.apiBaseUrl.isBlank()) { error = "آدرس سرور تنظیم نشده است"; return }
        busy = !silent; error = null
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.openMobileSession(token) } }
                .onSuccess {
                    sessionToken = it.token
                    screen = "home"
                    sync(silent = true)
                }
                .onFailure {
                    if (!silent) error = friendly(it)
                    screen = "login"
                }
            busy = false
        }
    }

    fun sync(silent: Boolean = false) {
        val token = sessionToken ?: return
        if (!silent) busy = true
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.sync(token) } }
                .onSuccess { (snap, raw) ->
                    snapshot = snap
                    store.cachedSyncJson = raw
                    if (!silent) info = "اطلاعات با سرور همگام شد"
                }
                .onFailure {
                    if (!silent) error = "اتصال برقرار نشد؛ آخرین اطلاعات ذخیره‌شده نمایش داده می‌شود. ${friendly(it)}"
                }
            busy = false
        }
    }

    fun navigate(target: String) {
        selectedMission = if (target == "checklist") selectedMission else null
        screen = target
    }

    fun openMission(mission: Mission) {
        selectedMission = mission
        draftFor(mission)
        screen = "checklist"
    }

    fun currentDraft(): MissionDraft? = selectedMission?.let { draftFor(it) }

    private fun draftFor(mission: Mission): MissionDraft {
        return drafts.getOrPut(mission.key) {
            store.loadDraft(mission.key)?.let(::parseDraft) ?: MissionDraft(startedAt = Instant.now().toString())
        }
    }

    fun setAnswer(questionId: String, value: Double) {
        val mission = selectedMission ?: return
        val d = draftFor(mission)
        d.answers[questionId] = value
        persistDraft(mission, d)
    }

    fun setNote(questionId: String, value: String) {
        val mission = selectedMission ?: return
        val d = draftFor(mission)
        d.notes[questionId] = value.take(1500)
        persistDraft(mission, d)
    }

    fun uploadEvidence(resolver: ContentResolver, questionId: String, uri: Uri) {
        val mission = selectedMission ?: return
        val token = sessionToken ?: run { error = "نشست ورود منقضی شده است"; return }
        busy = true; error = null
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.upload(token, resolver, mission.key, questionId, uri) } }
                .onSuccess { ref ->
                    val d = draftFor(mission)
                    d.uploads.removeAll { it.fileId == ref.fileId }
                    d.uploads.add(ref)
                    persistDraft(mission, d)
                    info = "مستند ثبت شد"
                }
                .onFailure { error = friendly(it) }
            busy = false
        }
    }

    fun submitMission() {
        val mission = selectedMission ?: return
        val token = sessionToken ?: run { error = "نشست ورود منقضی شده است"; return }
        val d = draftFor(mission)
        val missing = mission.questions.filter { !d.answers.containsKey(it.id) }
        if (missing.isNotEmpty()) { error = "${missing.size} سؤال هنوز پاسخ داده نشده است"; return }
        busy = true; error = null
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.submit(token, mission, d) } }
                .onSuccess { receipt ->
                    store.clearDraft(mission.key); drafts.remove(mission.key)
                    selectedMission = null; screen = "home"
                    info = "گزارش نهایی ثبت شد${if (receipt.isNotBlank()) " — کد رسید: $receipt" else ""}"
                    sync(silent = true)
                }
                .onFailure { error = friendly(it) }
            busy = false
        }
    }

    fun standardDeviceToken(): String? = store.loadDeviceToken()
    fun onBiometricEnabled() { info = "ورود با اثر انگشت فعال شد"; draftTick++ }
    fun onBiometricFailure(message: String) { error = message }

    fun logout() {
        sessionToken = null
        store.clearAuth()
        screen = "login"; selectedMission = null; snapshot = null
        info = "از حساب خارج شدید"
    }

    private fun persistDraft(mission: Mission, d: MissionDraft) {
        val answers = JSONObject(); d.answers.forEach { (k,v) -> answers.put(k,v) }
        val notes = JSONObject(); d.notes.forEach { (k,v) -> notes.put(k,v) }
        val uploads = JSONArray(); d.uploads.forEach { u -> uploads.put(JSONObject().put("file_id",u.fileId).put("question_id",u.questionId).put("kind",u.kind).put("name",u.name)) }
        store.saveDraft(mission.key, JSONObject().put("answers",answers).put("notes",notes).put("uploads",uploads).put("started_at",d.startedAt).toString())
        draftTick++
    }

    private fun parseDraft(raw: String): MissionDraft = runCatching {
        val j = JSONObject(raw); val d = MissionDraft(startedAt = j.optString("started_at", Instant.now().toString()))
        j.optJSONObject("answers")?.let { a -> a.keys().forEach { k -> d.answers[k] = a.optDouble(k) } }
        j.optJSONObject("notes")?.let { n -> n.keys().forEach { k -> d.notes[k] = n.optString(k) } }
        j.optJSONArray("uploads")?.objects()?.forEach { u -> d.uploads += UploadRef(u.optLong("file_id"),u.optString("question_id"),u.optString("kind","document"),u.optString("name","مستند")) }
        d
    }.getOrElse { MissionDraft(startedAt = Instant.now().toString()) }

    private fun friendly(t: Throwable): String {
        if (t is ApiException) {
            val code = runCatching { JSONObject(t.payload).optString("error") }.getOrDefault("")
            return when (code) {
                "invalid_credentials" -> "نام کاربری یا رمز عبور صحیح نیست"
                "too_many_attempts" -> "تعداد تلاش ورود زیاد است؛ کمی بعد دوباره امتحان کنید"
                "inspector_profile_unavailable" -> "پروفایل بازرس در سامانه فعال نیست"
                "mobile_access_disabled" -> "دسترسی این بازرس به اپ موبایل غیرفعال است"
                "mobile_profile_unavailable" -> "کد ملی/پروفایل موبایل بازرس کامل نیست"
                "device_revoked" -> "دسترسی این گوشی لغو شده است؛ با مدیر سامانه تماس بگیرید"
                "device_limit_reached" -> "تعداد دستگاه‌های فعال این حساب به سقف رسیده است"
                "mobile_unauthorized" -> "نشست موبایل منقضی شده است؛ دوباره وارد شوید"
                "mission_not_active_yet" -> "این مأموریت هنوز فعال نشده است"
                "mission_expired" -> "مهلت اجرای این مأموریت گذشته است"
                "not_assigned_to_mission" -> "این مأموریت به شما تخصیص داده نشده است"
                "quality_gate_failed" -> {
                    val j = runCatching { JSONObject(t.payload) }.getOrNull()
                    val missing = j?.optJSONArray("missing_questions")?.length() ?: 0
                    val notes = j?.optJSONArray("low_score_without_note")?.length() ?: 0
                    val evidence = j?.optJSONArray("critical_without_evidence")?.length() ?: 0
                    "گزارش کامل نیست: $missing پاسخ ناقص، $notes توضیح الزامی و $evidence مستند الزامی"
                }
                else -> t.message ?: "خطای سرور (${t.status})"
            }
        }
        return t.message ?: "خطای ارتباط با سرور"
    }
}
