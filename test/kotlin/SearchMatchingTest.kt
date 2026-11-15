package kr.routerescue.demo

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class SearchMatchingTest {
    private val report = PetReport("pet","강아지","콩이","갈색","2026-05-12T08:40",
        "빨간 목줄",null,Offset(790f,337f),"서울숲",listOf("성수동1가"))

    @Test fun matchesOnlyAhead() {
        assertEquals(6,matchSearch(listOf(report),demoRoute,false,emptySet())?.extraMinutes)
        assertEquals(4,matchSearch(listOf(report),demoRoute,true,emptySet())?.extraMinutes)
        assertNull(matchSearch(listOf(report),demoRoute.takeLast(1),false,emptySet()))
        assertNull(matchSearch(listOf(report),listOf(Offset.Zero,Offset(20f,20f)),false,emptySet()))
    }

    @Test fun ignoresInactiveAndAskedReports() {
        for (status in listOf(ReportStatus.Found,ReportStatus.Closed)) {
            assertNull(matchSearch(listOf(report.copy(status=status)),demoRoute,false,emptySet()))
        }
        assertNull(matchSearch(listOf(report),demoRoute,false,setOf(report.id)))
    }

    @Test fun rejectsAbsentParkingAndOverLimitDetour() {
        val long = report.copy(areas=listOf("송정동"))
        val route = listOf(Offset(700f,110f),Offset(860f,110f))
        assertNull(matchSearch(listOf(long),route,false,emptySet()))
        assertNull(matchSearch(listOf(long),route,true,emptySet()))
    }
}
