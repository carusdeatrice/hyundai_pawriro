package kr.routerescue.demo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

internal data class VehicleDetour(
    val offer: SearchOffer,
    val plan: RealDetour,
    val proof: ApprovedStop,
    val line: List<Offset>,
    val coverage: List<Offset>,
    val baselineSeconds: Int,
    val detourSeconds: Int,
    val entry: Offset
)

internal suspend fun GuardianClient.vehicleDetour(
    route: NaviSnapshot, latitude: Double, longitude: Double, passenger: Boolean,
    excluded: Set<String>, offered: VehicleDetour?, showReason: (String) -> Unit
): VehicleDetour? {
    val stops = JSONArray().put(JSONArray().put(longitude).put(latitude))
    (route.waypoints + route.destination).forEach { stops.put(JSONArray().put(it.longitude).put(it.latitude)) }
    val request = JSONObject().put("stops", stops).put("mode", if (passenger) "passenger" else "solo")
        .put("excluded", JSONArray(excluded.takeLastForRouting()))
    if (offered != null) {
        request.put("report_id", offered.offer.reportId).put("area", offered.offer.area)
        if (offered.offer.passenger == passenger) request.put("poi_id", offered.plan.stop.poiId)
    }
    val result = request("/vehicle/detour", "POST", request) as JSONObject
    if (!result.has("report_id")) {
        showReason(result.optString("reason", "실제 경유 후보 없음"))
        return null
    }
    val entry = result.getJSONArray("entry")
    val exit = result.optJSONArray("exit")
    val point = VisitPoint(entry.getDouble(1), entry.getDouble(0))
    val stop = NaviStop(point.lat, point.lon, result.getString("name"), "",
        result.getString("poi_id"), "", "")
    val exitPoint = exit?.let { VisitPoint(it.getDouble(1), it.getDouble(0)) }
    val exitStop = exitPoint?.let {
        NaviStop(it.lat, it.lon, "${result.getString("area")} 수색 도로 통과", "", stop.poiId+"-exit", "", "")
    }
    val visitArea = result.optJSONObject("visit_area")?.let { geometry ->
        val coordinates = geometry.getJSONArray("coordinates")
        val polygons = when (geometry.getString("type")) {
            "Polygon" -> listOf(coordinates)
            "MultiPolygon" -> List(coordinates.length(), coordinates::getJSONArray)
            else -> error("Unsupported visit area")
        }
        VisitArea(polygons.map { polygon ->
            List(polygon.length()) { ringIndex ->
                val ring = polygon.getJSONArray(ringIndex)
                List(ring.length()) { i ->
                    ring.getJSONArray(i).let { VisitPoint(it.getDouble(1), it.getDouble(0)) }
                }
            }
        })
    }
    require(!passenger || exitStop != null && visitArea != null)
    val extra = result.getInt("extra_minutes")
    val index = result.getInt("index")
    require(index in 0..route.waypoints.size && extra in 0..MAX_EXTRA_MINUTES)
    return VehicleDetour(
        SearchOffer(result.getString("report_id"), result.getString("area"), extra, passenger),
        RealDetour(route, stop, extra, index, exitStop),
        ApprovedStop(point, exitPoint,
            OffsetDateTime.parse(result.getString("expires_at")).toInstant().toEpochMilli(), visitArea),
        result.getJSONArray("line").mapPoints(), result.getJSONArray("coverage").mapPoints(),
        result.getInt("baseline_seconds"), result.getInt("detour_seconds"), mapPoint(entry)
    )
}

private fun JSONArray.mapPoints() = List(length()) { mapPoint(getJSONArray(it)) }
private fun Set<String>.takeLastForRouting() = toList().takeLast(100)

@androidx.compose.runtime.Composable
internal fun VehicleRouteMap(detour: VehicleDetour, location: Offset?, modifier: androidx.compose.ui.Modifier) {
    val extent = detour.line + detour.coverage + detour.entry
    val left = extent.minOf { it.x }
    val top = extent.minOf { it.y }
    val width = (extent.maxOf { it.x } - left).coerceAtLeast(1f)
    val height = (extent.maxOf { it.y } - top).coerceAtLeast(1f)
    androidx.compose.foundation.Canvas(modifier) {
        drawRect(Color(0xffedf1e8))
        val zoom = minOf(size.width * .80f / width, size.height * .64f / height)
        fun project(p: Offset) = Offset(
            (size.width - width * zoom) / 2 + (p.x-left) * zoom,
            (size.height - height * zoom) / 2 + (p.y-top) * zoom
        )
        fun path(points: List<Offset>) = Path().apply {
            val first = project(points.first())
            moveTo(first.x, first.y)
            points.drop(1).forEach { val p = project(it); lineTo(p.x, p.y) }
        }
        val zone = path(detour.coverage).apply { close() }
        drawPath(zone, Color(0x36e94579))
        drawPath(zone, Color(0xffe94579), style = Stroke(3.dp.toPx()))
        drawPath(path(detour.line), Color(0xff6746ca), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Color(0xff6746ca), 8.dp.toPx(), project(detour.entry))
        drawCircle(Color(0xff202820), 7.dp.toPx(), project(detour.line.last()))
        location?.let { drawCircle(Color(0xff1677ff), 7.dp.toPx(), project(it)) }
    }
}
