package kr.routerescue.demo

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.max

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
private val areaShape = svg("M650 310c50-57 164-72 225-17 55 49 34 143-32 178-76 40-189 9-213-59-11-33-2-70 20-102Z")
private val innerArea = svg("M760 340c18-23 50-18 58 8 7 22-18 47-45 39-22-7-28-29-13-47Z")
private val marker = svg("M0-34c-19 0-34 15-34 34 0 25 34 57 34 57S34 25 34 0C34-19 19-34 0-34Z")
private val paw = svg("M6 17c0-4 3-7 6-7s6 3 6 7c0 3-3 4-6 4s-6-1-6-4Z")
private val flag = svg("M-6 8V-9l15 5-15 5")
private val parkPaths = parks.map(::svg)
private val roadPaths = roads.map(::svg)
private val dash = PathEffect.dashPathEffect(floatArrayOf(12f,7f))
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
    onPin: ((Offset) -> Unit)? = null
) {
    val stop = rescueStop
    val path = remember(routePoints) {
        Path().apply {
            moveTo(routePoints.first().x,routePoints.first().y)
            routePoints.drop(1).forEach { lineTo(it.x,it.y) }
        }
    }
    val destination = routePoints.last()
    val areaAction by rememberUpdatedState(onArea)
    val pinAction by rememberUpdatedState(onPin)
    Box(modifier.background(Color(0xffedf1e8))) {
        Canvas(
            Modifier.fillMaxSize().semantics { contentDescription = "pawriro 모의 지도" }
                .then(if (onArea != null || onPin != null) Modifier.pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val scale = max(size.width / 1000f, size.height / 680f)
                        val point = Offset(
                            (tap.x - (size.width - 1000f * scale) / 2) / scale,
                            (tap.y - (size.height - 680f * scale) / 2) / scale
                        )
                        if (pinAction != null) pinAction?.invoke(point)
                        else searchAreas.firstOrNull { it.bounds.contains(point) }?.let { areaAction?.invoke(it.name) }
                    }
                } else Modifier)
        ) {
            val zoom = max(size.width / 1000f, size.height / 680f)
            clipRect {
                translate((size.width - 1000f * zoom)/2, (size.height - 680f * zoom)/2) {
                    scale(zoom,zoom,Offset.Zero) {
                        parkPaths.forEach { drawPath(it,Color(0xffdce8d7),alpha=.9f) }
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
                            drawRect(pink.copy(alpha=.18f),bounds.topLeft,bounds.size)
                            drawRect(pink,bounds.topLeft,bounds.size,style=Stroke(3f,pathEffect=dash))
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
                        if (rescue && stop != null) {
                            point(stop.x,stop.y,23f,6f,violet)
                            mapText("1",stop.x,stop.y+7f,19f,true)
                        }
                        (reportPins + listOfNotNull(pin)).distinct().forEach { pet ->
                        translate(pet.x,pet.y) {
                            drawPath(marker,pink)
                            drawPath(marker,Color.White,style=Stroke(6f))
                            translate(-12f,-16f) {
                                drawCircle(Color.White,3f,Offset(6f,7f))
                                drawCircle(Color.White,3f,Offset(18f,7f))
                                drawCircle(Color.White,3f,Offset(12f,3f))
                                drawPath(paw,Color.White)
                            }
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
        Label("DEMO MAP · pawriro",9,Color(0xff6e786f),modifier=Modifier.align(Alignment.BottomEnd)
            .padding(end=10.dp,bottom=7.dp).background(Color(0xb8ffffff)).padding(horizontal=5.dp,vertical=2.dp))
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
