package kr.routerescue.demo

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

private val parks = listOf(
    "M0 80 200 0h180l30 170-150 100L0 235Z",
    "m720 0 280 0v205l-170 50-130-110Z",
    "m0 520 180-85 165 110-45 135H0Z",
    "m765 470 235-80v290H730Z"
)
private val roads = listOf(
    "M-40 430C120 390 174 260 320 292s225 140 390 88 224-180 330-170",
    "M205-40c30 170 7 294 70 430s148 180 164 330",
    "M690-20c-30 165 8 250-42 340s-89 170-70 390",
    "M-20 135c200 27 310 65 470 47s360-50 580 15",
    "M-20 575c200-50 350-25 510 10s330 12 540-45"
)
private val localStreets = listOf(
    "M12 210 Q105 180 215 193", "M32 267 Q119 231 233 247",
    "M82 152 L107 317 M153 139 L176 288",
    "M246 419 Q319 384 430 419 L559 468",
    "M280 472 Q356 434 460 466 L584 519",
    "M356 346 Q373 447 398 551 M470 380 L505 565",
    "M720 103 Q813 73 991 80 M731 133 Q842 108 990 115",
    "M780 42 L802 175 M858 40 L883 181 M929 43 L950 178",
    "M778 495 Q887 446 990 463 M771 533 Q895 501 999 514",
    "M813 448 L846 570 M890 416 L914 548 M950 405 L974 560"
).map(::svg)
private val demoMapBounds = Rect(0f, 0f, 1000f, 680f)
private val areaShape = svg("M650 310c50-57 164-72 225-17 55 49 34 143-32 178-76 40-189 9-213-59-11-33-2-70 20-102Z")
private val innerArea = svg("M760 340c18-23 50-18 58 8 7 22-18 47-45 39-22-7-28-29-13-47Z")
private val marker = svg("M0-34c-19 0-34 15-34 34 0 25 34 57 34 57S34 25 34 0C34-19 19-34 0-34Z")
private val paw = svg("M6 17c0-4 3-7 6-7s6 3 6 7c0 3-3 4-6 4s-6-1-6-4Z")
private val flag = svg("M-6 8V-9l15 5-15 5")
private val parkPaths = parks.map(::svg)
private val roadPaths = roads.map(::svg)
private val dash = PathEffect.dashPathEffect(floatArrayOf(12f,7f))
private const val rescueMarkerRadius = 23f
private const val rescueMarkerStroke = 6f
private val blueRoute = Color(0xff1677ff)
internal val violet = Color(0xff6c4cff)
internal val pink = Color(0xffef3f78)

private fun svg(value: String) = PathParser().parsePathString(value).toPath()

@Composable
internal fun PawriroMap(
    modifier: Modifier = Modifier,
    route: Boolean = false,
    rescue: Boolean = false,
    pin: Offset? = null,
    visibleAreas: List<String> = emptyList(),
    reportPins: List<Offset> = emptyList(),
    position: Offset? = demoRoute.first(),
    routePoints: List<Offset> = demoRoute,
    rescueStop: Offset? = null,
    onArea: ((String) -> Unit)? = null,
    publicCoverage: List<androidx.compose.ui.geometry.Rect> = emptyList(),
    editLines: List<List<Offset>> = emptyList(),
    savedCircles: List<Pair<Offset,Float>> = emptyList(),
    editCircle: Pair<Offset,Float>? = null,
    onPin: ((Offset) -> Unit)? = null,
    fitRoute: Boolean = false,
    rescueStopName: String? = null,
    geographic: Boolean = false
) {
    if (geographic) {
        GeographicMap(modifier, line=if (route) routePoints else emptyList(),position=position,
            rescue=rescue,stop=rescueStop,stopName=rescueStopName,
            zones=(searchAreas.filter { it.name in visibleAreas }.map { it.bounds } + publicCoverage).distinct(),
            pins=reportPins,onArea=onArea)
        return
    }
    var zoomFactor by remember { mutableStateOf(1f) }
    val stop = rescueStop
    val path = remember(routePoints) {
        Path().apply {
            moveTo(routePoints.first().x,routePoints.first().y)
            routePoints.drop(1).forEach { lineTo(it.x,it.y) }
        }
    }
    val routeBounds = remember(routePoints, fitRoute) {
        if (!fitRoute) null else Rect(
            minOf(demoMapBounds.left, routePoints.minOf { it.x }),
            minOf(demoMapBounds.top, routePoints.minOf { it.y }),
            maxOf(demoMapBounds.right, routePoints.maxOf { it.x }),
            maxOf(demoMapBounds.bottom, routePoints.maxOf { it.y }))
    }
    val destination = routePoints.last()
    val areaAction by rememberUpdatedState(onArea)
    val pinAction by rememberUpdatedState(onPin)
    Box(modifier.background(Color(0xffedf1e8))) {
        Canvas(
            Modifier.fillMaxSize().semantics { contentDescription = "pawriro 모의 지도" }
                .then(if (onArea != null || onPin != null) Modifier.pointerInput(zoomFactor, routeBounds) {
                    detectTapGestures { tap ->
                        val mapSize = Size(size.width.toFloat(), size.height.toFloat())
                        val scale = mapZoom(mapSize, routeBounds) * zoomFactor
                        val point = (tap - mapOrigin(mapSize, routeBounds, scale)) / scale
                        if (pinAction != null) pinAction?.invoke(point)
                        else searchAreas.firstOrNull { it.bounds.contains(point) }?.let { areaAction?.invoke(it.name) }
                    }
                } else Modifier)
        ) {
            val zoom = mapZoom(size, routeBounds) * zoomFactor
            val origin = mapOrigin(size, routeBounds, zoom)
            clipRect {
                if (fitRoute) drawRect(Color(0xffedf1e8))
                translate(origin.x, origin.y) {
                    scale(zoom,zoom,Offset.Zero) {
                        parkPaths.forEach { drawPath(it,Color(0xffdce8d7),alpha=.9f) }
                        localStreets.forEach { drawPath(it, Color(0xfffcfdf9), style = Stroke(6f)) }
                        roadPaths.forEach { drawPath(it,Color.White,style=Stroke(16f)) }
                        roadPaths.take(3).forEach { drawPath(it,Color(0xffcbd2c7),style=Stroke(2f)) }
                        mapText("언주로",90f,360f)
                        mapText("성수대교",455f,550f)
                        mapText("서울숲",740f,330f)
                        mapText("압구정로",350f,145f)
                        mapText("성수동1가",775f,570f)
                        searchAreas.filter { it.name in visibleAreas }.forEach { area ->
                            if (area.name == neighborhoods.first()) {
                                drawPath(areaShape,Color(0x2dff5d94))
                                drawPath(areaShape,Color(0xffe94579),style=Stroke(5f,pathEffect=dash))
                                drawPath(innerArea,Color(0x36ff5d94))
                            } else {
                                drawRect(Color(0x2dff5d94),area.bounds.topLeft,area.bounds.size)
                                drawRect(pink,area.bounds.topLeft,area.bounds.size,style=Stroke(5f,pathEffect=dash))
                                mapText(area.name,area.bounds.left+8,area.bounds.top+23)
                            }
                        }
                        publicCoverage.forEach { bounds ->
                            val outline = searchAreaOutline(bounds)
                            drawPath(outline, pink.copy(alpha = .18f))
                            drawPath(outline, pink, style = Stroke(4f, pathEffect = dash))
                        }
                        editLines.forEach { line ->
                            line.zipWithNext().forEach { (a,b) -> drawLine(violet,a,b,4f) }
                            line.forEach { drawCircle(violet,5f,it) }
                        }
                        (savedCircles+listOfNotNull(editCircle)).forEach { (center, meters) ->
                            drawCircle(pink.copy(alpha=.18f),meters/2.5f,center)
                            drawCircle(pink,meters/2.5f,center,style=Stroke(3f))
                        }
                        if (route) {
                            drawPath(path,Color.White,style=Stroke(17f,cap=StrokeCap.Round))
                            drawPath(path,if (rescue) violet else blueRoute,style=Stroke(10f,cap=StrokeCap.Round))
                        }
                        publicCoverage.forEach { bounds ->
                            if (minOf(bounds.width, bounds.height) >= 52f) {
                                val badge = Offset(bounds.left + bounds.width * .29f,
                                    bounds.top + bounds.height * .33f)
                                drawCircle(Color.White, 18f, badge)
                                drawCircle(pink, 15f, badge)
                                drawPawMark(badge, .9f)
                            }
                        }
                        if (rescue && stop != null) {
                            point(stop.x,stop.y,rescueMarkerRadius,rescueMarkerStroke,violet)
                            mapText("1",stop.x,stop.y+7f,19f,true)
                        }
                        (reportPins + listOfNotNull(pin)).distinct().forEach { pet ->
                        translate(pet.x,pet.y) {
                            drawPath(marker,pink)
                            drawPath(marker,Color.White,style=Stroke(6f))
                            drawPawMark(Offset(0f, -6f))
                        }
                        }
                        position?.let {
                            point(it.x,it.y,23f,7f,blueRoute)
                            drawCircle(Color.White,7f,it)
                        }
                        if (route) {
                            point(destination.x,destination.y,22f,6f,Color(0xff18231d))
                            translate(destination.x,destination.y) { drawPath(flag,Color.White) }
                        }
                    }
                }
            }
        }
        if (rescue && stop != null && rescueStopName != null) {
            Layout(
                modifier = Modifier.matchParentSize(),
                content = {
                    Row(
                        Modifier.padding(5.dp).shadow(5.dp, RoundedCornerShape(12.dp))
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(32.dp).background(violet, RoundedCornerShape(50)),
                            Alignment.Center) { Label("1", 16, Color.White, true) }
                        Column {
                            Label("수색 경유지", 13, bold = true)
                            Label(rescueStopName, 10, muted)
                        }
                    }
                }
            ) { measurables, constraints ->
                val mapSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                val zoom = mapZoom(mapSize, routeBounds) * zoomFactor
                val anchor = mapOrigin(mapSize, routeBounds, zoom) + stop * zoom
                val card = measurables.single().measure(Constraints(
                    maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
                val markerTop = anchor.y - (rescueMarkerRadius + rescueMarkerStroke / 2) * zoom
                val left = (anchor.x - card.width / 2f).roundToInt()
                    .coerceIn(0, constraints.maxWidth - card.width)
                val top = (markerTop - card.height).roundToInt().coerceAtLeast(0)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    if (anchor.x in 0f..mapSize.width && anchor.y in 0f..mapSize.height) {
                        card.place(left, top)
                    }
                }
            }
        }
        if (onPin == null && onArea == null) {
            Column(Modifier.align(Alignment.CenterEnd).padding(end=20.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Action(Modifier.size(52.dp), 14, elevation=5, click={ zoomFactor=(zoomFactor*1.25f).coerceAtMost(3f) }) {
                    Label("+",28,bold=true)
                }
                Action(Modifier.size(52.dp), 14, elevation=5, click={ zoomFactor=(zoomFactor/1.25f).coerceAtLeast(1f) }) {
                    Label("−",28,bold=true)
                }
                Action(Modifier.size(52.dp), 14, elevation=5, click={ zoomFactor=1f }) {
                    Label("전체",14,bold=true)
                }
            }
        }
        Label("DEMO MAP · pawriro",9,Color(0xff6e786f),modifier=Modifier.align(Alignment.BottomEnd)
            .padding(end=10.dp,bottom=7.dp).background(Color(0xb8ffffff)).padding(horizontal=5.dp,vertical=2.dp))
    }
}

// Illustrates the published coverage envelope without exposing the original report geometry.
private fun searchAreaOutline(bounds: Rect): Path = Path().apply {
    fun x(fraction: Float) = bounds.left + bounds.width * fraction
    fun y(fraction: Float) = bounds.top + bounds.height * fraction
    moveTo(x(.06f), y(.30f))
    cubicTo(x(.12f), y(.08f), x(.27f), y(.04f), x(.45f), y(.10f))
    cubicTo(x(.64f), y(.15f), x(.87f), y(.03f), x(.94f), y(.26f))
    cubicTo(x(.99f), y(.43f), x(.90f), y(.59f), x(.92f), y(.72f))
    cubicTo(x(.94f), y(.92f), x(.68f), y(.95f), x(.51f), y(.88f))
    cubicTo(x(.34f), y(.81f), x(.20f), y(.96f), x(.10f), y(.78f))
    cubicTo(x(.02f), y(.63f), x(.01f), y(.45f), x(.06f), y(.30f))
    close()
}

private fun mapZoom(size: Size, bounds: Rect?): Float =
    bounds?.let {
        minOf(size.width * .94f / it.width, size.height * .79f / it.height)
    } ?: max(size.width / 1000f, size.height / 680f)

private fun mapOrigin(size: Size, bounds: Rect?, zoom: Float): Offset =
    Offset(size.width / 2, size.height / 2) - (bounds?.center ?: Offset(500f, 340f)) * zoom

private fun DrawScope.drawPawMark(center: Offset, scaleFactor: Float = 1f) {
    translate(center.x - 12f * scaleFactor, center.y - 10f * scaleFactor) {
        scale(scaleFactor, scaleFactor, Offset.Zero) {
            drawCircle(Color.White, 3f, Offset(6f, 7f))
            drawCircle(Color.White, 3f, Offset(18f, 7f))
            drawCircle(Color.White, 3f, Offset(12f, 3f))
            drawPath(paw, Color.White)
        }
    }
}

private fun DrawScope.point(x: Float,y: Float,r: Float,border: Float,color: Color) {
    drawCircle(color,r,Offset(x,y))
    drawCircle(Color.White,r,Offset(x,y),style=Stroke(border))
}
private fun DrawScope.mapText(text: String,x: Float,y: Float,size: Float=17f,marker: Boolean=false) {
    drawContext.canvas.nativeCanvas.drawText(text,x,y,Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = if (marker) android.graphics.Color.WHITE else 0xff869083.toInt()
        if (marker) {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    })
}
