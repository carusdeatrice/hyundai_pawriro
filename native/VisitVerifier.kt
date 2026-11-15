package kr.routerescue.demo

import kotlin.math.*

internal data class VisitPoint(val lat: Double,val lon: Double) {
    fun distance(other: VisitPoint): Double {
        val a=Math.toRadians(lat)
        val b=Math.toRadians(other.lat)
        val h=sin((b-a)/2).pow(2)+cos(a)*cos(b)*sin(Math.toRadians(other.lon-lon)/2).pow(2)
        return 12_742_000*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
}
internal data class ApprovedStop(val entry: VisitPoint,val exit: VisitPoint?,val expiresAt: Long,val area: VisitArea? = null)

internal class VisitArea(private val polygons: List<List<List<VisitPoint>>>) {
    fun contains(point: VisitPoint) = polygons.any { rings ->
        rings.isNotEmpty() && insideRing(point,rings.first()) && rings.drop(1).none { insideRing(point,it) }
    }

    private fun insideRing(point: VisitPoint, ring: List<VisitPoint>): Boolean {
        var inside=false
        for ((a,b) in ring.zipWithNext()) {
            if ((a.lat>point.lat)!=(b.lat>point.lat) &&
                point.lon < a.lon+(b.lon-a.lon)*(point.lat-a.lat)/(b.lat-a.lat)) inside=!inside
        }
        return inside
    }

    fun crossesBoundary(a: VisitPoint, b: VisitPoint): Boolean {
        if (a==b) return false
        return polygons.any { rings -> rings.any { ring ->
            ring.zipWithNext().any { (c,d) ->
                val rx=b.lon-a.lon
                val ry=b.lat-a.lat
                val sx=d.lon-c.lon
                val sy=d.lat-c.lat
                val denominator=rx*sy-ry*sx
                if (denominator==0.0) false else {
                    val dx=c.lon-a.lon
                    val dy=c.lat-a.lat
                    val t=(dx*sy-dy*sx)/denominator
                    val u=(dx*ry-dy*rx)/denominator
                    t in 0.0..1.0 && u in 0.0..1.0
                }
            }
        } }
    }
}

internal class VisitVerifier(private val stop: ApprovedStop) {
    var hold: String? = null
        private set
    var verified = false
        private set
    private var last: Pair<VisitPoint,Long>? = null
    private var nearCount=0
    private var entered=false
    private var exitCount=0
    private var areaStartedAt: Long? = null
    private var distanceAnchor: VisitPoint? = null
    private var distanceInArea=0.0
    private var lastMovementAt=0L
    private var doorOpened=false
    private var exitedAt: Long?=null
    private var occupiedBefore=false
    private var doorWasClosed=false
    private var vehicleAt=0L

    fun gps(point: VisitPoint?, time: Long, now: Long) {
        if (verified || hold!=null) return
        if (point==null || now-time !in 0..15_000 || point.lat !in -90.0..90.0 || point.lon !in -180.0..180.0) {
            if (last!=null) hold="GPS 신호 누락 또는 만료"
            return
        }
        val previous=last
        if (previous!=null) {
            val dt=time-previous.second
            if (dt==0L) return
            if (dt !in 1..15_000 || previous.first.distance(point)>30+dt*.055) {
                hold="GPS 간격 또는 위치 이동 이상"
                return
            }
        }
        last=point to time
        stop.area?.let { area ->
            verifyAreaDrive(area, previous?.first, point, time)
            return
        }
        val exit=stop.exit
        if (exit!=null && entered) {
            exitCount=if (point.distance(exit)<=30) exitCount+1 else 0
            if (exitCount>=2) verified=true
            return
        }
        nearCount=if (point.distance(stop.entry)<=30) nearCount+1 else 0
        if (exit!=null && nearCount>=2) entered=true
    }

    private fun verifyAreaDrive(area: VisitArea, previous: VisitPoint?, point: VisitPoint, time: Long) {
        if (!area.contains(point)) {
            areaStartedAt=null
            distanceAnchor=null
            distanceInArea=0.0
            return
        }
        if (areaStartedAt==null || previous==null || time-lastMovementAt>5_000 || area.crossesBoundary(previous,point)) {
            areaStartedAt=time
            lastMovementAt=time
            distanceAnchor=point
            distanceInArea=0.0
            return
        }
        val moved=distanceAnchor?.distance(point) ?: 0.0
        if (moved>=5) {
            lastMovementAt=time
            distanceInArea+=moved
            distanceAnchor=point
        }
        verified=moved>=5 && distanceInArea>=200 && time-(areaStartedAt ?: time)>=12_000
    }

    fun vehicle(parked: Boolean?, open: Boolean?, occupied: Boolean?, now: Long) {
        if (verified || hold!=null || stop.exit!=null) return
        if (nearCount<2 || parked!=true || open==null || occupied==null) {
            if (exitedAt!=null) hold="하차 중 주차·문·좌석 신호 누락"
            doorOpened=false
            occupiedBefore=false
            doorWasClosed=false
            return
        }
        if (exitedAt!=null && now-vehicleAt>15_000) { hold="하차 중 차량 신호 간격 초과"; return }
        vehicleAt=now
        if (occupied && exitedAt!=null) {
            if (now-exitedAt!!>=MIN_WALK_MILLIS) verified=true
            else hold="하차 시간이 3분 미만"
            return
        }
        if (!open) doorWasClosed=true
        if (occupied) occupiedBefore=true
        if (open && doorWasClosed && occupiedBefore) doorOpened=true
        if (!occupied && doorOpened && exitedAt==null) exitedAt=now
    }
}
