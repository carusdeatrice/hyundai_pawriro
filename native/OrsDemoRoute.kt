package kr.routerescue.demo

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

internal data class OrsDemoRoute(val line: List<Offset>, val seconds: Double, val meters: Double) {
    val minutes get() = ceil(seconds / 60).toInt()
    private val samples = buildList {
        add(line.first() to 1)
        var remaining = 45f
        line.zipWithNext().forEachIndexed { index, (start, end) ->
            val length = (end - start).getDistance()
            var distance = remaining
            while (distance < length) {
                add((start + (end - start) * (distance / length)) to index + 1)
                distance += 45f
            }
            remaining = distance - length
        }
        if (last().first != line.last()) add(line.last() to line.size)
    }
    val frames = samples.map { it.first }

    fun remainingLine(frame: Int): List<Offset> {
        val (position, nextVertex) = samples[frame]
        return listOf(position) + line.drop(nextVertex)
    }
}

internal suspend fun loadOrsDemoRoute(address: String, stops: List<JSONArray>): OrsDemoRoute? {
    return try {
        val response = GuardianClient(address).request("/vehicle/route-overview", "POST",
            JSONObject().put("stops", JSONArray(stops))) as JSONObject
        val coordinates = response.getJSONArray("line")
        val line = List(coordinates.length()) { mapPoint(coordinates.getJSONArray(it)) }
        val seconds = response.getDouble("seconds")
        val meters = response.getDouble("meters")
        require(line.size >= 2 && line.all { it.x.isFinite() && it.y.isFinite() })
        require(seconds.isFinite() && seconds > 0 && meters.isFinite() && meters > 0)
        OrsDemoRoute(line, seconds, meters)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }
}
