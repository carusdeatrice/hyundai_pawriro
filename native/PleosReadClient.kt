package kr.routerescue.demo

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import android.app.Activity
import android.util.Log

internal data class NaviStop(
    val latitude: Double, val longitude: Double, val name: String, val address: String,
    val poiId: String, val poiSubId: String?, val poiProvider: String?
) {
    fun sameLocation(other: NaviStop) =
        abs(latitude-other.latitude)<0.00001 && abs(longitude-other.longitude)<0.00001
}

internal data class NaviSnapshot(val destination: NaviStop, val waypoints: List<NaviStop>)
internal data class RealDetour(
    val route: NaviSnapshot, val stop: NaviStop, val extraMinutes: Int,
    val index: Int = route.waypoints.size, val exit: NaviStop? = null
) {
    val searchStops get() = listOfNotNull(stop, exit)
}

internal class PleosReadClient(private val activity: Activity, private val state: PawriroState) : AutoCloseable {
    private val context = activity.applicationContext
    private var navi: NaviHelper? = null
    private var location: FusedLocation? = null
    private var closed = false
    private var ready = false
    private var tripDestination: NaviStop? = null
    private var tripRequested = false
    private var tripExpiry = 0L
    private var query: CompletableDeferred<NaviSnapshot?>? = null
    private var queriedDestination: NaviStop? = null
    private var queriedWaypoints: List<NaviStop>? = null
    private var routeResult: CompletableDeferred<Boolean>? = null

    private fun publishSnapshot() {
        val destination = queriedDestination ?: return
        val waypoints = queriedWaypoints ?: return
        query?.complete(NaviSnapshot(destination,waypoints))
    }
    private val naviEvents = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = publish {
            state.sdkDestination = destinationInfo
            queriedDestination = NaviStop(destinationInfo.latitude,destinationInfo.longitude,destinationInfo.name,
                destinationInfo.address,destinationInfo.poiId,destinationInfo.poiSubId,destinationInfo.poiProvider)
            if (state.vehicleDetour?.plan?.route?.destination?.sameLocation(queriedDestination!!) == false)
                state.vehicleDetour = null
            publishSnapshot()
            val destination = queriedDestination ?: return@publish
            if (state.sdkTrip == null && !tripRequested && state.sdkRouteState?.naviMode==NaviMode.ROUTE_GUIDING) {
                tripRequested=true
                tripDestination=destination
                tripExpiry=android.os.SystemClock.elapsedRealtime()+5000
                runCatching {
                    navi?.requestTrip(TripRequest(latitude=destination.latitude,longitude=destination.longitude,
                        poiId=destination.poiId,poiName=destination.name,poiSubId=destination.poiSubId.orEmpty()))
                }.onFailure { Log.w("PawriroPleos","Trip request failed",it) }
            }
            Log.i("PawriroPleos","Destination received")
        }
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = publish {
            state.sdkWaypointsReceived = true
            state.sdkWaypoints = waypointInfoList
            queriedWaypoints = waypointInfoList.map { NaviStop(it.latitude,it.longitude,it.name,it.address,
                it.poiId,it.poiSubId,it.poiProvider) }
            state.vehicleDetour?.plan?.let { plan ->
                val expected = plan.route.waypoints.toMutableList().apply { addAll(plan.index, plan.searchStops) }
                val actual = queriedWaypoints.orEmpty()
                if (actual.size != expected.size || actual.zip(expected).any { (a,b) -> !a.sameLocation(b) })
                    state.vehicleDetour = null
            }
            publishSnapshot()
            Log.i("PawriroPleos","Waypoints received: ${waypointInfoList.size}")
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = publish {
            state.sdkRouteState = routeStateInfo
            if (routeStateInfo.naviMode !in setOf(NaviMode.ROUTE_GUIDING,NaviMode.SIMULATION_GUIDING)
                && !routeStateInfo.isRouting && query==null && routeResult==null) clearRoute()
            Log.i("PawriroPleos","Route state received")
        }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = publish {
            state.sdkTurn = tbtInfoList.firstOrNull()
            state.sdkTurnAt = android.os.SystemClock.elapsedRealtime()
        }
        override fun onTripInfo(tripInfo: TripInfo) = publish {
            Log.i("PawriroPleos", "Trip callback: ${tripInfo.summary.routePoints.size} points")
            if (tripRequested && tripDestination==queriedDestination &&
                android.os.SystemClock.elapsedRealtime()<=tripExpiry) state.sdkTrip=tripInfo
        }
        override fun onDestinationChanged() = publish {
            clearTrip()
            state.sdkTurn=null
            state.sdkTurnAt=0
            refresh()
        }
        override fun onRoutingCompleted() = publish {
            clearTrip()
            if (routeResult?.complete(true) != true && query == null) refresh()
        }
        override fun onRouteEnded() = publish { clearRoute() }
        override fun onRouteCancelled() = publish { clearRoute() }
        override fun onError(errorCode: NaviErrorCode) = publish {
            state.naviDiagnostic = "경로 정보 미확보 · NaviHelper $errorCode"
            query?.complete(null)
            routeResult?.complete(false)
            Log.w("PawriroPleos","Navi error: $errorCode")
        }
    }
    private val position = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) = publish {
            state.sdkLocation = location
            if (location != null) Log.i("PawriroPleos","Location received")
        }
        override fun onError(exception: Exception) {
            publish { state.sdkLocation = null }
            Log.w("PawriroPleos","Location error",exception)
        }
    }

    fun connect() {
        runCatching {
            navi = NaviHelper(context)
            navi?.initialize(
                onConnected = {
                    publish {
                        runCatching {
                            navi?.addListener(naviEvents)
                            ready = true
                            state.naviDiagnostic = "경로 정보 미확보 · 실제 경유 후보와 사전 ETA 필요"
                            refresh()
                        }
                            .onFailure { Log.w("PawriroPleos","Navi subscription failed",it) }
                    }
                },
                onFailed = { error ->
                    publish { state.naviDiagnostic = "경로 정보 미확보 · NaviHelper 서비스 연결 실패" }
                    Log.w("PawriroPleos","Navi connection failed",error)
                }
            )
        }.onFailure { Log.w("PawriroPleos","Navi initialization failed",it) }
        runCatching {
            val client = FusedLocation(context)
            if (client.isSupported()) {
                location = client
                client.initialize()
                client.registerFusedLocationCallback(position)
                client.getLastKnownLocation(position)
            } else Log.w("PawriroPleos","FusedLocation service unavailable")
        }.onFailure { Log.w("PawriroPleos","Location initialization failed",it) }
    }

    private fun publish(action: () -> Unit) {
        activity.runOnUiThread { if (!closed) action() }
    }
    private fun clearTrip() {
        state.sdkTrip=null
        tripDestination=null
        tripRequested=false
    }
    private fun clearRoute() {
        state.vehicleDetour = null
        clearTrip()
        state.sdkTurn=null
        state.sdkTurnAt=0
        queriedDestination = null
        queriedWaypoints = null
        query?.complete(null)
        routeResult?.complete(false)
        state.sdkDestination = null
        state.sdkWaypointsReceived = false
        state.sdkWaypoints = emptyList()
    }
    fun refresh() {
        if (!ready || query != null || routeResult != null) return
        runCatching {
            navi?.getRouteStateInfo()
            navi?.getDestinationInfo()
            navi?.getWaypointInfo()
            navi?.getTBTInfo()
        }.onFailure { Log.w("PawriroPleos","Navi query failed",it) }
    }
    suspend fun readActiveRoute(): NaviSnapshot? {
        val helper = navi ?: return null
        if (!ready || query != null) return null
        val result = CompletableDeferred<NaviSnapshot?>()
        queriedDestination = null
        queriedWaypoints = null
        query = result
        return try {
            helper.getDestinationInfo()
            helper.getWaypointInfo()
            withTimeoutOrNull(3500) { result.await() }
        } catch (error: RuntimeException) {
            Log.w("PawriroPleos","Active route query failed",error)
            null
        } finally {
            query = null
        }
    }

    suspend fun applyDetour(plan: RealDetour): Boolean {
        val helper = navi ?: return false
        if (routeResult != null || plan.route.waypoints.size + plan.searchStops.size > 2) return false
        if (readActiveRoute() != plan.route) return false
        if (plan.index !in 0..plan.route.waypoints.size ||
            plan.route.waypoints.any { existing -> plan.searchStops.any(existing::sameLocation) }) return false
        val stops = plan.route.waypoints.toMutableList().apply { addAll(plan.index,plan.searchStops) }
        val destination = plan.route.destination
        val result = CompletableDeferred<Boolean>()
        routeResult = result
        val completed = try {
            helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(
                destination = RouteInfo(latitude=destination.latitude,longitude=destination.longitude,
                    poiName=destination.name,address=destination.address,poiId=destination.poiId,
                    poiSubId=destination.poiSubId.orEmpty(),poiProvider=destination.poiProvider.orEmpty(),
                    routeOption=state.sdkRouteState?.routeOption ?: ai.pleos.playground.navi.constants.RouteOption.RECOMMENDED),
                waypoints = stops.mapIndexed { i,stop ->
                    RequestWaypointInfo(latitude=stop.latitude,longitude=stop.longitude,poiName=stop.name,
                        address=stop.address,poiId=stop.poiId,poiSubId=stop.poiSubId.orEmpty(),poiProvider=stop.poiProvider.orEmpty(),
                        waypointIndex=if (i==0) WaypointIndex.FIRST else WaypointIndex.SECOND)
                }
            ))
            withTimeoutOrNull(8000) { result.await() } == true
        } catch (error: RuntimeException) {
            Log.w("PawriroPleos","Route request failed",error)
            false
        } finally {
            routeResult = null
        }
        val active = readActiveRoute()
        return completed && active != null && active.destination.sameLocation(destination) &&
            active.waypoints.size == stops.size &&
            active.waypoints.zip(stops).all { (actual,requested) -> actual.sameLocation(requested) }
    }

    enum class Removal { Removed, AlreadyAbsent, Unconfirmed }

    suspend fun removeSearchWaypoint(plan: RealDetour): Removal {
        var removed = false
        for (stop in plan.searchStops.asReversed()) {
            when (removeSearchStop(stop)) {
                Removal.Unconfirmed -> return Removal.Unconfirmed
                Removal.Removed -> removed = true
                Removal.AlreadyAbsent -> Unit
            }
        }
        return if (removed) Removal.Removed else Removal.AlreadyAbsent
    }

    private suspend fun removeSearchStop(target: NaviStop): Removal {
        val helper = navi ?: return Removal.Unconfirmed
        if (routeResult != null) return Removal.Unconfirmed
        val before = readActiveRoute() ?: return Removal.Unconfirmed
        val matches = before.waypoints.indices.filter { index ->
            val stop = before.waypoints[index]
            stop.sameLocation(target) && (stop.poiId == target.poiId || stop.name == target.name)
        }
        if (matches.isEmpty()) return Removal.AlreadyAbsent
        val index = matches.singleOrNull() ?: return Removal.Unconfirmed
        val slot = when (index) {
            0 -> WaypointIndex.FIRST
            1 -> WaypointIndex.SECOND
            else -> return Removal.Unconfirmed
        }
        val remaining = before.waypoints.filterIndexed { i, _ -> i != index }
        val result = CompletableDeferred<Boolean>()
        routeResult = result
        try {
            helper.removeWaypoint(slot)
            withTimeoutOrNull(3500) { result.await() }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: RuntimeException) {
            Log.w("PawriroPleos", "Search waypoint removal failed", error)
        } finally {
            routeResult = null
        }
        val after = readActiveRoute() ?: return Removal.Unconfirmed
        if (!after.destination.sameLocation(before.destination)) return Removal.Unconfirmed
        return if (after.waypoints.size == remaining.size &&
            after.waypoints.zip(remaining).all { (actual, expected) -> actual.sameLocation(expected) })
            Removal.Removed else Removal.Unconfirmed
    }

    override fun close() {
        closed = true
        runCatching { navi?.removeListener(naviEvents) }
        runCatching { navi?.release() }
        runCatching { location?.unregisterFusedLocationCallback(position) }
        runCatching { location?.release() }
    }
}
