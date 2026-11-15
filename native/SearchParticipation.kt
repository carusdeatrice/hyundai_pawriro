package kr.routerescue.demo

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal const val BASE_POINTS = 50
internal const val PHOTO_POINTS = 25
internal const val DAILY_POINTS = 150
internal const val MIN_WALK_MILLIS = 180_000L
private val korea = ZoneId.of("Asia/Seoul")
private fun day(now: Long) = Instant.ofEpochMilli(now).atZone(korea).toLocalDate().toString()

internal class SearchParticipation(private val prefs: SharedPreferences) {
    fun entries(): List<JSONObject> {
        val array = JSONArray(prefs.getString("entries","[]"))
        return (0 until array.length()).map(array::getJSONObject)
    }

    private fun save(rows: List<JSONObject>) =
        prefs.edit().putString("entries",JSONArray(rows).toString()).commit()

    fun begin(offer: SearchOffer, simulated: Boolean): String? {
        val row = JSONObject().put("id",UUID.randomUUID().toString())
            .put("report",offer.reportId).put("area",offer.area)
            .put("passenger",offer.passenger).put("simulated",simulated)
        return row.getString("id").takeIf { save(entries()+row) }
    }

    fun evidence(id: String, event: String, parked: Boolean, elapsed: Long, session: String): Boolean {
        val rows = entries()
        val row = rows.lastOrNull { it.optString("id")==id && !it.has("result") && it.optBoolean("simulated") } ?: return false
        if (row.has("session") && row.optString("session") != session) {
            if (event !in listOf("arrived","entered")) return false
            listOf("arrived","entered","passed","door","exit","walk").forEach(row::remove)
        }
        val passenger = row.optBoolean("passenger")
        when (event) {
            "arrived" -> if (!passenger) row.put("arrived",true) else return false
            "entered" -> if (passenger && !parked) row.put("entered",true) else return false
            "passed" -> if (passenger && !parked && row.optBoolean("entered")) row.put("passed",true) else return false
            "unpark" -> { row.remove("exit"); row.remove("door") }
            "door" -> if (!passenger && parked && row.optBoolean("arrived")) row.put("door",true) else return false
            "exit" -> if (parked && row.optBoolean("door") && !row.has("exit") && !row.has("walk"))
                row.put("exit",elapsed) else return false
            "return" -> if (parked && row.has("exit")) {
                row.put("walk",elapsed-row.getLong("exit")); row.remove("exit"); row.remove("door")
            } else return false
            else -> return false
        }
        row.put("session",session)
        return save(rows)
    }

    fun recordActual(id: String, verified: Boolean, hold: String?): Boolean {
        val rows=entries()
        val row=rows.firstOrNull { it.optString("id")==id && !it.has("result") && !it.optBoolean("simulated") }
            ?: return false
        row.put("verified",verified).put("evidenceHold",hold)
        return save(rows)
    }

    fun submit(id: String, sighting: Boolean, time: String, x: Float?, y: Float?, photo: String?, now: Long): Boolean {
        val rows = entries()
        val row = rows.firstOrNull { it.optString("id")==id && !it.has("result") } ?: return false
        if (sighting && (x==null || y==null || time.isBlank())) return false
        val date = day(now)
        val reason = when {
            !row.optBoolean("simulated") && !row.optBoolean("verified") ->
                row.optString("evidenceHold").ifBlank { "실제 방문 신호 미확보" }
            row.optBoolean("simulated") && row.optBoolean("passenger") && !row.optBoolean("passed") -> "수색 도로 진입·통과 기록 없음"
            !row.optBoolean("simulated") && row.optBoolean("passenger") && !row.optBoolean("arrived") -> "지정 주차 후보 도착 기록 없음"
            row.optBoolean("simulated") && !row.optBoolean("passenger") && row.optLong("walk") < MIN_WALK_MILLIS -> "하차·재탑승 및 3분 하차 기록 미충족"
            rows.any { it.optString("paidDay")==date && it.optInt("base")>0 &&
                it.optString("report")==row.optString("report") && it.optString("area")==row.optString("area") } ->
                "같은 신고·구역의 오늘 적립 완료"
            totalOn(rows,date)+BASE_POINTS > DAILY_POINTS -> "오늘 포인트 한도 도달"
            else -> null
        }
        row.put("result",if (sighting) "목격 제보" else "목격 없음")
        row.put("submittedAt",now)
        if (sighting) row.put("time",time).put("x",x!!.toDouble()).put("y",y!!.toDouble()).put("photo",photo)
        if (reason == null) row.put("base",BASE_POINTS).put("paidDay",date)
        else row.put("hold",reason)
        return save(rows)
    }

    fun approveDemoPhoto(id: String, now: Long): Boolean {
        val rows = entries()
        val row = rows.firstOrNull { it.optString("id")==id } ?: return false
        val date = day(now)
        if (!row.optBoolean("simulated") || row.optInt("base")!=BASE_POINTS ||
            row.optString("photo").isBlank() || row.has("bonus") ||
            totalOn(rows,date)+PHOTO_POINTS > DAILY_POINTS) return false
        row.put("bonus",PHOTO_POINTS).put("bonusDay",date)
        return save(rows)
    }

    private fun totalOn(rows: List<JSONObject>, date: String) = rows.sumOf {
        (if (it.optString("paidDay")==date) it.optInt("base") else 0) +
            (if (it.optString("bonusDay")==date) it.optInt("bonus") else 0)
    }
}
