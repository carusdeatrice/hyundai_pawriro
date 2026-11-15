package kr.routerescue.demo

import android.content.SharedPreferences
import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject

internal enum class ReportStatus(val label: String) {
    Active("수색 중"), Found("발견 완료"), Closed("신고 종료")
}

internal data class PetReport(
    val id: String, val kind: String, val name: String, val coat: String,
    val time: String, val traits: String, val photo: String?, val pin: Offset?,
    val place: String, val areas: List<String>, val status: ReportStatus = ReportStatus.Active,
    val latestSighting: String = "",
    val publicCoverage: Map<String, androidx.compose.ui.geometry.Rect> = emptyMap()
)

internal class ReportStore(private val prefs: SharedPreferences) {
    fun reports(): List<PetReport> {
        val values = JSONArray(prefs.getString("reports", "[]"))
        return (0 until values.length()).map { decode(values.getJSONObject(it)) }
    }

    fun draft(): PetReport? = prefs.getString("draft", null)?.let { decode(JSONObject(it)) }
    fun step() = prefs.getInt("step", 1)

    fun saveDraft(report: PetReport, step: Int) {
        prefs.edit().putString("draft", encode(report).toString()).putInt("step", step).apply()
    }

    fun saveReports(reports: List<PetReport>, clearDraft: Boolean = false) {
        val editor = prefs.edit().putString("reports", JSONArray(reports.map(::encode)).toString())
        if (clearDraft) editor.remove("draft").remove("step")
        editor.apply()
    }

    private fun encode(r: PetReport) = JSONObject().apply {
        put("id",r.id); put("kind",r.kind); put("name",r.name); put("coat",r.coat)
        put("time",r.time); put("traits",r.traits); put("photo",r.photo)
        r.pin?.let { put("x",it.x.toDouble()); put("y",it.y.toDouble()) }
        put("place",r.place); put("areas",JSONArray(r.areas)); put("status",r.status.name)
    }

    private fun decode(j: JSONObject): PetReport {
        val areas = j.getJSONArray("areas")
        return PetReport(j.getString("id"),j.getString("kind"),j.getString("name"),j.getString("coat"),
            j.getString("time"),j.getString("traits"),j.optString("photo").ifEmpty { null },
            if (j.has("x")) Offset(j.getDouble("x").toFloat(),j.getDouble("y").toFloat()) else null,
            j.getString("place"),(0 until areas.length()).map(areas::getString),
            ReportStatus.valueOf(j.getString("status")))
    }
}
