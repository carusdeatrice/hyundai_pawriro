package kr.routerescue.demo

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun RouteOverview(state: PawriroState, modifier: Modifier, content: @Composable (Boolean, String) -> Unit) {
    val destination = state.sdkDestination
    val waypoints = state.sdkWaypoints.map { it.latitude to it.longitude }
    val origin = state.sdkLocation?.takeIf { System.currentTimeMillis()-it.timestampMillis in 0..15_000 }
        ?.let { VisitPoint(it.latitude,it.longitude) } ?: state.navigationPosition
    val position=origin?.let { mapPoint(routeCoordinate(it.lat,it.lon)) }
    val routeReady = state.sdkRouteState.isGuiding() && state.sdkWaypointsReceived
    val enabled = state.showRouteOverview
    var line by remember(enabled,destination?.latitude,destination?.longitude,waypoints,state.routeRevision,routeReady) {
        mutableStateOf(emptyList<Offset>())
    }
    var status by remember(enabled,state.routeRevision,routeReady) { mutableStateOf("") }
    LaunchedEffect(enabled,destination?.latitude,destination?.longitude,waypoints,state.routeRevision,
        routeReady,origin!=null,state.guardianAddress) {
        if (!enabled) return@LaunchedEffect
        if (!routeReady || destination==null || origin==null) {
            status=if (origin==null) "차량 위치 확인 중" else "Pleos 안내 경로 확인 중"
            return@LaunchedEffect
        }
        status="예상 경로 불러오는 중…"
        val stops=JSONArray().put(routeCoordinate(origin.lat,origin.lon))
        waypoints.forEach { (latitude,longitude) -> stops.put(routeCoordinate(latitude,longitude)) }
        stops.put(routeCoordinate(destination.latitude,destination.longitude))
        try {
            val result=GuardianClient(state.guardianAddress).request("/vehicle/route-overview","POST",
                JSONObject().put("stops",stops)) as JSONObject
            val points=result.getJSONArray("line")
            line=List(points.length()) { mapPoint(points.getJSONArray(it)) }.filter { it.x.isFinite() && it.y.isFinite() }
            status=if (line.size>=2) "" else "예상 경로 미확보"
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: GuardianHttpError) {
            android.util.Log.w("PawriroPleos","Route overview HTTP ${error.status}")
            status=when (error.status) {
                404 -> "현재 위치에서 예상 경로를 찾지 못했어요"
                503 -> "예상 경로 서버 응답 없음"
                else -> "예상 경로 요청 실패 (${error.status})"
            }
        } catch (error: Exception) {
            android.util.Log.w("PawriroPleos","Route overview unavailable (${error.javaClass.simpleName})")
            status="예상 경로 연결 실패"
        }
    }
    val zones=state.guardianReports.flatMap { it.publicCoverage.values }
    GeographicMap(modifier,line=line,position=position,zones=zones)
    content(line.size>=2,if (enabled) status else if (position==null) "차량 위치 확인 중" else "")
}

private fun routeCoordinate(latitude: Double, longitude: Double) = JSONArray().put(longitude).put(latitude)

@Composable
private fun RouteOverviewCanvas(line: List<Offset>, zones: List<Rect>, position: Offset?, modifier: Modifier) {
    val center=position ?: zones.firstOrNull()?.center ?: Offset.Zero
    val left=if (line.isEmpty()) center.x-350f else line.minOf { it.x }
    val top=if (line.isEmpty()) center.y-250f else line.minOf { it.y }
    val width=if (line.isEmpty()) 700f else (line.maxOf { it.x }-left).coerceAtLeast(1f)
    val height=if (line.isEmpty()) 500f else (line.maxOf { it.y }-top).coerceAtLeast(1f)
    Canvas(modifier) {
        drawRect(Color(0xffedf1e8))
        val zoom=minOf(size.width*.82f/width,size.height*.55f/height)
        fun project(p: Offset) = Offset((size.width-width*zoom)/2+(p.x-left)*zoom,
            (size.height-height*zoom)/2+(p.y-top)*zoom)
        zones.forEach { zone ->
            val topLeft=project(zone.topLeft)
            val boxSize=androidx.compose.ui.geometry.Size(zone.width*zoom,zone.height*zoom)
            drawRect(Color(0x60e94579),topLeft,boxSize)
            drawRect(Color(0xffe94579),topLeft,boxSize,style=Stroke(2.dp.toPx()))
        }
        if (line.size>=2) {
        val route=Path().apply {
            val start=project(line.first())
            moveTo(start.x,start.y)
            line.drop(1).forEach { p ->
                val point=project(p)
                lineTo(point.x,point.y)
            }
        }
        drawPath(route,Color(0xff6746ca),style=Stroke(5.dp.toPx(),cap=StrokeCap.Round))
        drawCircle(Color(0xff17221c),7.dp.toPx(),project(line.last()))
        }
        position?.let { drawCircle(Color(0xff1677ff),7.dp.toPx(),project(it)) }
    }
}
