package ir.bazr.inspector

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

class ApiException(val status: Int, val payload: String) : Exception(runCatching {
    val j = JSONObject(payload)
    j.optString("message").ifBlank { j.optString("error", "خطای ارتباط با سرور") }
}.getOrDefault("خطای ارتباط با سرور"))

class ApiClient(private val store: SecureStore, private val crypto: DeviceCrypto) {
    data class LoginResult(val deviceToken: String, val sessionToken: String, val profileJson: String)
    data class SessionResult(val token: String, val expiresIn: Int)

    private fun endpoint(path: String): URL {
        val base = store.apiBaseUrl
        require(base.startsWith("https://")) { "آدرس API باید با https:// شروع شود" }
        return URL(base + path.removePrefix("/"))
    }

    private fun request(
        path: String,
        method: String = "GET",
        bearer: String? = null,
        body: ByteArray? = null,
        contentType: String = "application/json; charset=utf-8",
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val c = endpoint(path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "BAZR-Inspector-Android/1.0")
        if (!bearer.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $bearer")
        extraHeaders.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.setFixedLengthStreamingMode(body.size)
            BufferedOutputStream(c.outputStream).use { it.write(body) }
        }
        val status = c.responseCode
        val stream = if (status in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (status !in 200..299) throw ApiException(status, text)
        return text
    }

    fun passwordLogin(username: String, password: String): LoginResult {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("device_id", store.deviceId)
            .put("public_key_pem", crypto.publicKeyPem())
            .put("device_name", "Android")
            .put("app_version", "1.0.0")
            .toString().toByteArray(Charsets.UTF_8)
        val raw = request("mobile_password_login.php", "POST", body = body)
        val j = JSONObject(raw)
        val profile = j.optJSONObject("profile") ?: JSONObject()
        return LoginResult(
            deviceToken = j.getString("device_token"),
            sessionToken = j.getString("session_token"),
            profileJson = profile.toString()
        )
    }

    fun openMobileSession(deviceToken: String): SessionResult {
        val ts = Instant.now().toString()
        val nonce = UUID.randomUUID().toString()
        val msg = "session|${store.deviceId}|$ts|$nonce"
        val body = JSONObject()
            .put("device_id", store.deviceId)
            .put("device_token", deviceToken)
            .put("timestamp", ts)
            .put("nonce", nonce)
            .put("signature", crypto.sign(msg))
            .toString().toByteArray(Charsets.UTF_8)
        val j = JSONObject(request("mobile_session.php", "POST", body = body))
        return SessionResult(j.getString("token"), j.optInt("expires_in", 1800))
    }

    fun sync(sessionToken: String): Pair<SyncSnapshot, String> {
        val raw = request("mobile_sync.php", bearer = sessionToken)
        return parseSyncSnapshot(raw) to raw
    }

    fun upload(
        sessionToken: String,
        resolver: ContentResolver,
        missionKey: String,
        questionKey: String,
        uri: Uri
    ): UploadRef {
        val boundary = "----BAZR${UUID.randomUUID().toString().replace("-", "")}" 
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = queryName(resolver, uri) ?: "evidence"
        val url = endpoint("mobile_upload.php")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.doOutput = true
        c.setChunkedStreamingMode(64 * 1024)
        c.setRequestProperty("Authorization", "Bearer $sessionToken")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val out = BufferedOutputStream(c.outputStream)
        fun textField(key: String, value: String) {
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n".toByteArray())
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray())
        }
        textField("mission_key", missionKey)
        textField("question_key", questionKey)
        textField("file_kind", if (mime.startsWith("image/")) "image" else "document")
        textField("captured_at", Instant.now().toString())
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"file\"; filename=\"${name.replace("\"", "_")}\"\r\n".toByteArray())
        out.write("Content-Type: $mime\r\n\r\n".toByteArray())
        resolver.openInputStream(uri)?.use { input ->
            BufferedInputStream(input).copyTo(out, 64 * 1024)
        } ?: throw IllegalStateException("فایل قابل خواندن نیست")
        out.write("\r\n--$boundary--\r\n".toByteArray())
        out.flush(); out.close()
        val status = c.responseCode
        val stream = if (status in 200..299) c.inputStream else c.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (status !in 200..299) throw ApiException(status, raw)
        val j = JSONObject(raw)
        return UploadRef(
            fileId = j.getLong("file_id"),
            questionId = j.optString("question_key", questionKey),
            kind = j.optString("file_kind", if (mime.startsWith("image/")) "image" else "document"),
            name = j.optString("name", name)
        )
    }

    fun submit(sessionToken: String, mission: Mission, draft: MissionDraft): String {
        val answers = JSONObject()
        draft.answers.forEach { (k, v) -> answers.put(k, v) }
        val notes = JSONObject()
        draft.notes.forEach { (k, v) -> if (v.isNotBlank()) notes.put(k, v) }
        val uploads = JSONArray()
        draft.uploads.forEach { u ->
            uploads.put(JSONObject().put("file_id", u.fileId).put("question_id", u.questionId).put("kind", u.kind))
        }
        val body = JSONObject()
            .put("mission_key", mission.key)
            .put("answers", answers)
            .put("notes", notes)
            .put("uploads", uploads)
            .put("findings", JSONArray())
            .put("started_at", draft.startedAt ?: Instant.now().toString())
            .put("finished_at", Instant.now().toString())
            .toString().toByteArray(Charsets.UTF_8)
        val raw = request(
            "mobile_submit.php", "POST", bearer = sessionToken, body = body,
            extraHeaders = mapOf("X-Idempotency-Key" to UUID.randomUUID().toString())
        )
        return JSONObject(raw).optString("receipt_code", "")
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
