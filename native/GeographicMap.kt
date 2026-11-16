package kr.routerescue.demo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Overlay
import java.io.File

private fun Offset.geographic() = GeoPoint(37.557-y*.000025,127.022+x*.000026)
private fun Rect.corners() = listOf(topLeft,topRight,bottomRight,bottomLeft)

/** Every overlay and the base map use the same geographic projection. */
@Composable
internal fun GeographicMap(
    modifier: Modifier, line: List<Offset> = emptyList(), position: Offset? = null,
    rescue: Boolean = false, stop: Offset? = null, stopName: String? = null,
    zones: List<Rect> = emptyList(), pins: List<Offset> = emptyList(),
    onArea: ((String) -> Unit)? = null
) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val map=remember(context) {
        Configuration.getInstance().apply {
            userAgentValue="Pawriro/1.1 (Android navigation prototype)"
            osmdroidBasePath=File(context.filesDir,"osm")
            osmdroidTileCache=File(context.cacheDir,"osm-tiles")
        }
        MapView(context).apply {
            setTileSource(XYTileSource("OpenStreetMap",0,19,256,".png",
                arrayOf("https://tile.openstreetmap.org/")))
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel=3.0
            maxZoomLevel=19.0
            controller.setZoom(15.0)
            controller.setCenter((position ?: Offset(846.1538f,640f)).geographic())
        }
    }
    val overlay=remember(map) { GeographicOverlay() }
    val currentArea by rememberUpdatedState(onArea)
    val boundsPoints=remember(line,zones) { line + zones.flatMap { it.corners() } }
    val fitPoints by rememberUpdatedState(boundsPoints + listOfNotNull(position))
    fun fit() {
        val points=fitPoints
        if (points.isEmpty()) return
        val geo=points.map { it.geographic() }
        val north=geo.maxOf { it.latitude }; val south=geo.minOf { it.latitude }
        val east=geo.maxOf { it.longitude }; val west=geo.minOf { it.longitude }
        // Vertical room for the search/turn card, bottom guidance, and stop label.
        val latPad=maxOf((north-south)*.36,.0015)
        val lonPad=maxOf((east-west)*.16,.0015)
        map.zoomToBoundingBox(BoundingBox(north+latPad,east+lonPad,south-latPad,west-lonPad),false,
            (24*context.resources.displayMetrics.density).toInt(),17.0,0L)
    }
    DisposableEffect(map) {
        map.overlays.add(overlay)
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    // Do not reset the user's camera at every simulation tick.
    LaunchedEffect(line,zones) { map.post { fit() } }
    Box(modifier) {
        AndroidView(factory={map},modifier=Modifier.matchParentSize(), update={ view ->
            overlay.line=line; overlay.position=position; overlay.rescue=rescue
            overlay.stop=stop; overlay.stopName=stopName; overlay.zones=zones; overlay.pins=pins
            overlay.onArea={ currentArea?.invoke(it) }
            view.invalidate()
        })
        Column(Modifier.align(Alignment.CenterEnd).padding(end=18.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Action(Modifier.size(52.dp),14,elevation=5,click={map.controller.zoomIn()}) { Label("+",28,bold=true) }
            Action(Modifier.size(52.dp),14,elevation=5,click={map.controller.zoomOut()}) { Label("−",28,bold=true) }
            Action(Modifier.size(52.dp),14,elevation=5,click={fit()}) { Label("전체",14,bold=true) }
            Action(Modifier.size(52.dp),14,elevation=5,click={position?.let {map.controller.animateTo(it.geographic())}}) {
                Label("내 위치",12,bold=true)
            }
        }
        Label("© OpenStreetMap contributors",10,Color(0xff253329),
            modifier=Modifier.align(Alignment.BottomStart).padding(start=12.dp,bottom=112.dp)
                .background(Color(0xeeffffff)).padding(horizontal=6.dp,vertical=3.dp))
    }
}

private class GeographicOverlay: Overlay() {
    var line=emptyList<Offset>()
    var zones=emptyList<Rect>()
    var pins=emptyList<Offset>()
    var position: Offset?=null
    var stop: Offset?=null
    var stopName: String?=null
    var rescue=false
    var onArea: ((String)->Unit)?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas,map: MapView,shadow: Boolean) {
        if (shadow) return
        val d=map.resources.displayMetrics.density
        fun point(p: Offset): Point = map.projection.toPixels(p.geographic(),Point())
        fun path(points: List<Offset>,closed: Boolean=false)=Path().apply {
            points.forEachIndexed { index,p -> val s=point(p)
                if(index==0) moveTo(s.x.toFloat(),s.y.toFloat()) else lineTo(s.x.toFloat(),s.y.toFloat()) }
            if(closed) close()
        }
        fun fill(color: Int) { paint.reset();paint.isAntiAlias=true;paint.color=color;paint.style=Paint.Style.FILL }
        fun stroke(color: Int,width: Float) { fill(color);paint.style=Paint.Style.STROKE;paint.strokeWidth=width*d;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND }
        zones.forEach { zone ->
            val shape=path(zone.corners(),true)
            fill(0x35ef3f78);canvas.drawPath(shape,paint)
            stroke(0xffef3f78.toInt(),2f)
            paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(8*d,5*d),0f)
            canvas.drawPath(shape,paint);paint.pathEffect=null
            val p=point(zone.center)
            fill(0xffef3f78.toInt());canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),14*d,paint)
            fill(android.graphics.Color.WHITE)
            canvas.drawOval(p.x-5*d,p.y-1*d,p.x+5*d,p.y+7*d,paint)
            listOf(-6f to -3f,0f to -7f,6f to -3f).forEach {(x,y)->
                canvas.drawCircle(p.x+x*d,p.y+y*d,2.7f*d,paint)
            }
        }
        if(line.size>1) {
            val route=path(line)
            stroke(android.graphics.Color.WHITE,9f);canvas.drawPath(route,paint)
            stroke(if(rescue) 0xff6c4cff.toInt() else 0xff1677ff.toInt(),5f);canvas.drawPath(route,paint)
            val p=point(line.last());fill(0xff17221c.toInt());canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),11*d,paint)
            fill(android.graphics.Color.WHITE);paint.textSize=12*d;paint.textAlign=Paint.Align.CENTER
            canvas.drawText("도",p.x.toFloat(),p.y+4*d,paint)
        }
        pins.forEach { val p=point(it);fill(0xffef3f78.toInt());canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),6*d,paint) }
        if(rescue) stop?.let { s ->
            val p=point(s)
            fill(android.graphics.Color.WHITE);canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),15*d,paint)
            fill(0xff6c4cff.toInt());canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),12*d,paint)
            fill(android.graphics.Color.WHITE);paint.textAlign=Paint.Align.CENTER;paint.textSize=15*d;paint.isFakeBoldText=true
            canvas.drawText("1",p.x.toFloat(),p.y+5*d,paint)
            val title="수색 경유지";val subtitle=stopName.orEmpty()
            paint.textSize=12*d
            val w=maxOf(130*d,paint.measureText(subtitle)+24*d)
            val l=p.x-w/2; val bottom=p.y-18*d; val top=bottom-53*d
            fill(android.graphics.Color.WHITE);canvas.drawRoundRect(l,top,l+w,bottom,10*d,10*d,paint)
            fill(0xff17221c.toInt());paint.textAlign=Paint.Align.CENTER;paint.textSize=15*d;paint.isFakeBoldText=true
            canvas.drawText(title,p.x.toFloat(),top+20*d,paint)
            paint.textSize=11*d;paint.isFakeBoldText=false
            canvas.drawText(subtitle,p.x.toFloat(),top+39*d,paint)
        }
        position?.let { val p=point(it)
            fill(0x301677ff);canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),21*d,paint)
            fill(android.graphics.Color.WHITE);canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),13*d,paint)
            fill(0xff1677ff.toInt());canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),9*d,paint)
        }
    }
    override fun onSingleTapConfirmed(e: MotionEvent,map: MapView): Boolean {
        val geo=map.projection.fromPixels(e.x.toInt(),e.y.toInt())
        val p=Offset(((geo.longitude-127.022)/.000026).toFloat(),((37.557-geo.latitude)/.000025).toFloat())
        val zone=searchAreas.firstOrNull { it.bounds.contains(p) } ?: return false
        onArea?.invoke(zone.name)
        return onArea!=null
    }
}
