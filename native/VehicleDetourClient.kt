package kr.routerescue.demo

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

internal data class VehicleDetour(
    val offer: SearchOffer,
    val plan: RealDetour,
    val proof: ApprovedStop,
    val baselineSeconds: Int,
    val detourSeconds: Int
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
        result.getInt("baseline_seconds"), result.getInt("detour_seconds")
    )
}

private fun Set<String>.takeLastForRouting() = toList().takeLast(100)
