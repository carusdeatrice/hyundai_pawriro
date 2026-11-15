package kr.routerescue.demo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal data class SearchArea(val name: String, val bounds: Rect, val parkingMinutes: Int?, val roadMinutes: Int?)
internal val searchAreas = listOf(
    SearchArea("성수동1가",Rect(632f,270f,902f,491f),6,4),
    SearchArea("성수동2가",Rect(864f,136f,984f,268f),9,7),
    SearchArea("송정동",Rect(713f,55f,850f,178f),null,12)
)

internal data class SearchOffer(val reportId: String, val area: String, val extraMinutes: Int, val passenger: Boolean, val sightingPriority: Long = 0) {
    val waypointName get() = "$area ${if (passenger) "일반 도로" else "주차 후보"}"
    val waypoint get() = searchAreas.first { it.name == area }.bounds.let {
        if (passenger) Offset(it.center.x + 15f,it.center.y - 20f)
        else Offset(it.center.x - 35f,it.center.y + 12f)
    }
}
internal const val MAX_EXTRA_MINUTES = 10
internal val demoRoute = listOf(Offset(310f,610f),Offset(410f,441f),Offset(520f,380f),
    Offset(700f,300f),Offset(830f,250f))

internal fun demoRouteFor(place: Place): List<Offset> =
    demoRoute.dropLast(1) + demoDestinationPins[places.indexOf(place)]

internal val demoDestinationPins = listOf(Offset(830f,250f),Offset(790f,337f),Offset(867f,219f))

internal fun hasSearchAreaAhead(reports: List<PetReport>, remaining: List<Offset>, asked: Set<String>): Boolean =
    reports.any { report ->
        report.status == ReportStatus.Active && report.id !in asked &&
            searchAreas.any { it.name in report.areas && ahead(remaining,report.publicCoverage[it.name] ?: it.bounds) }
    }

private fun ahead(remaining: List<Offset>, bounds: Rect) =
    remaining.zipWithNext().any { (a,b) -> a != b && intersects(a,b,bounds) }

internal fun matchSearch(
    reports: List<PetReport>, remaining: List<Offset>, passenger: Boolean, alreadyAsked: Set<String>
): SearchOffer? = reports.asSequence()
    .filter { it.status == ReportStatus.Active && it.id !in alreadyAsked }
    .flatMap { report ->
        searchAreas.asSequence().filter { it.name in report.areas }.mapNotNull { area ->
            val minutes = (if (passenger) area.roadMinutes else area.parkingMinutes) ?: return@mapNotNull null
            if (minutes > MAX_EXTRA_MINUTES || !ahead(remaining,report.publicCoverage[area.name] ?: area.bounds))
                return@mapNotNull null
            SearchOffer(report.id,area.name,minutes,passenger,
                runCatching { java.time.OffsetDateTime.parse(report.latestSighting).toEpochSecond() }.getOrDefault(0))
        }
    }.minWithOrNull(compareByDescending<SearchOffer> { it.sightingPriority }.thenBy { it.extraMinutes })

private fun intersects(a: Offset, b: Offset, bounds: Rect): Boolean {
    var enter = 0f
    var exit = 1f
    val dx = b.x - a.x
    val dy = b.y - a.y
    val edges = listOf(-dx to a.x-bounds.left,dx to bounds.right-a.x,
        -dy to a.y-bounds.top,dy to bounds.bottom-a.y)
    for ((direction, distance) in edges) {
        if (direction == 0f) {
            if (distance < 0f) return false
        } else {
            val t = distance / direction
            if (direction < 0f) enter = maxOf(enter,t) else exit = minOf(exit,t)
            if (enter > exit) return false
        }
    }
    return exit > 0f
}
