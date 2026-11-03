package ir.bazr.inspector

import org.json.JSONArray
import org.json.JSONObject

data class InspectorProfile(val name: String, val role: String, val inspectorRef: String)
data class ChecklistSection(val id: String, val title: String, val category: String)
data class ChecklistQuestion(
    val id: String,
    val text: String,
    val sectionId: String,
    val weight: Double,
    val critical: Boolean
)
data class Mission(
    val key: String,
    val id: String,
    val orgName: String,
    val type: String,
    val date: String,
    val status: String,
    val scheduleState: String,
    val scheduleLabel: String,
    val canExecute: Boolean,
    val sections: List<ChecklistSection>,
    val questions: List<ChecklistQuestion>
)
data class MobileReport(
    val id: Long,
    val missionKey: String,
    val receiptCode: String,
    val reviewStatus: String,
    val submittedAt: String
)
data class SyncSnapshot(
    val profile: InspectorProfile,
    val missions: List<Mission>,
    val reports: List<MobileReport>,
    val serverTime: String
)
data class UploadRef(
    val fileId: Long,
    val questionId: String,
    val kind: String,
    val name: String
)

data class MissionDraft(
    val answers: MutableMap<String, Double> = mutableMapOf(),
    val notes: MutableMap<String, String> = mutableMapOf(),
    val uploads: MutableList<UploadRef> = mutableListOf(),
    var startedAt: String? = null
)

fun JSONObject.optStringAny(vararg keys: String): String {
    for (k in keys) {
        if (has(k) && !isNull(k)) {
            val v = optString(k, "").trim()
            if (v.isNotEmpty()) return v
        }
    }
    return ""
}

fun JSONArray.objects(): List<JSONObject> = buildList {
    for (i in 0 until length()) optJSONObject(i)?.let(::add)
}

fun parseSyncSnapshot(raw: String): SyncSnapshot {
    val root = JSONObject(raw)
    val p = root.optJSONObject("profile") ?: JSONObject()
    val profile = InspectorProfile(
        name = p.optString("name", "بازرس"),
        role = p.optString("role", "inspector"),
        inspectorRef = p.optString("inspector_ref", "")
    )
    val missions = root.optJSONArray("missions")?.objects()?.map { m ->
        val snap = m.optJSONObject("checklistSnapshot") ?: JSONObject()
        val sections = snap.optJSONArray("sections")?.objects()?.map { s ->
            ChecklistSection(
                id = s.optString("id", ""),
                title = s.optStringAny("title", "name", "id"),
                category = s.optString("category", "عمومی")
            )
        }.orEmpty()
        val questions = snap.optJSONArray("questions")?.objects()?.map { q ->
            ChecklistQuestion(
                id = q.optString("id", ""),
                text = q.optStringAny("text", "title", "label"),
                sectionId = q.optString("sectionId", ""),
                weight = q.optDouble("weight", 1.0).coerceAtLeast(0.01),
                critical = q.optBoolean("critical", false)
            )
        }.orEmpty()
        Mission(
            key = m.optString("_server_key", m.optStringAny("id", "missionId")),
            id = m.optStringAny("id", "missionId", "_server_key"),
            orgName = m.optStringAny("orgName", "organizationName").ifBlank { "دستگاه اجرایی" },
            type = m.optStringAny("type", "title").ifBlank { "بازرسی" },
            date = m.optStringAny("date", "visitDate", "missionDate"),
            status = m.optString("status", "تعیین شده"),
            scheduleState = m.optString("schedule_state", ""),
            scheduleLabel = m.optString("schedule_label", ""),
            canExecute = m.optBoolean("can_execute", false),
            sections = sections,
            questions = questions
        )
    }.orEmpty()
    val reports = root.optJSONArray("reports")?.objects()?.map { r ->
        MobileReport(
            id = r.optLong("id", 0),
            missionKey = r.optString("mission_key", ""),
            receiptCode = r.optString("receipt_code", ""),
            reviewStatus = r.optString("review_status", "pending_review"),
            submittedAt = r.optString("submitted_at", "")
        )
    }.orEmpty()
    return SyncSnapshot(profile, missions, reports, root.optString("server_time", ""))
}
