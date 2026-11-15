package kr.routerescue.demo

import org.junit.Assert.*
import org.junit.Test

class VisitVerifierTest {
    private val entry=VisitPoint(37.54,127.04)
    private val exit=VisitPoint(37.54,127.041)
    @Test fun passengerRequiresContinuousEntryThenExit() {
        val v=VisitVerifier(ApprovedStop(entry,exit,999999))
        v.gps(entry,1000,1000)
        v.gps(entry,2000,2000)
        v.gps(exit,6000,6000)
        assertFalse(v.verified)
        v.gps(exit,7000,7000)
        assertTrue(v.verified)
    }
    @Test fun locationJumpHoldsCredit() {
        val v=VisitVerifier(ApprovedStop(entry,exit,999999))
        v.gps(entry,1000,1000)
        v.gps(VisitPoint(38.0,128.0),2000,2000)
        assertNotNull(v.hold)
        assertFalse(v.verified)
    }
    private val area = VisitArea(listOf(listOf(listOf(
        VisitPoint(37.539,127.039), VisitPoint(37.539,127.045), VisitPoint(37.541,127.045),
        VisitPoint(37.541,127.039), VisitPoint(37.539,127.039)
    ))))

    @Test fun passengerCanUseAnotherRoadButNeedsDistanceAndTime() {
        val v=VisitVerifier(ApprovedStop(entry,exit,999999,area))
        for (i in 0..11) {
            val time=1000L+i*1000
            v.gps(VisitPoint(37.5405,127.04+i*.00023),time,time)
        }
        assertFalse(v.verified)
        v.gps(VisitPoint(37.5405,127.04276),13000,13000)
        assertTrue(v.verified)
    }

    @Test fun crossingOutsideResetsContinuousDistance() {
        val v=VisitVerifier(ApprovedStop(entry,exit,999999,area))
        for (i in 0..5) {
            val time=1000L+i*1000
            v.gps(VisitPoint(37.5409,127.04+i*.0003),time,time)
        }
        v.gps(VisitPoint(37.5411,127.0415),7000,7000)
        for (i in 0..12) {
            val time=8000L+i*1000
            v.gps(VisitPoint(37.5409,127.0415+i*.0001),time,time)
        }
        assertNull(v.hold)
        assertFalse(v.verified)
    }

    @Test fun soloRequiresDoorAndThreeMinutes() {
        val v=VisitVerifier(ApprovedStop(entry,null,999999))
        v.gps(entry,1000,1000)
        v.gps(entry,2000,2000)
        v.vehicle(true,false,true,2000)
        v.vehicle(true,true,true,3000)
        v.vehicle(true,true,false,4000)
        for (t in 5000L..183000L step 1000) {
            v.gps(entry,t,t)
            v.vehicle(true,false,false,t)
        }
        v.gps(entry,184000,184000)
        v.vehicle(true,true,true,184000)
        assertTrue(v.verified)
    }
}
