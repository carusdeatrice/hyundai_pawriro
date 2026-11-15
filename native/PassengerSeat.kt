package kr.routerescue.demo

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal class PassengerSeat(context: Context) : AutoCloseable {
    // The Korean MVP targets left-hand-drive vehicles.
    private val frontPassenger = SeatArea(SeatValue.ROW_1_RIGHT)
    private val vehicle = Vehicle(context.applicationContext)

    init { vehicle.initialize() }

    suspend fun confirmed(): Boolean {
        val result = withTimeoutOrNull(1500) {
            coroutineScope {
                val occupied = async { read(vehicle.getSeat()::getCurrentSeatOccupancy) }
                val buckled = async { read(vehicle.getSeat()::getCurrentSeatBeltBuckledState) }
                val occupancy = occupied.await()
                val belt = buckled.await()
                occupancy == true && belt == true
            }
        }
        Log.i("PawriroPleos", "Passenger confirmation: ${result ?: "timeout"}")
        return result == true
    }

    private suspend fun read(query: (SeatArea, (Boolean?) -> Unit, (Exception) -> Unit) -> Unit): Boolean? =
        suspendCancellableCoroutine { continuation ->
            val once = java.util.concurrent.atomic.AtomicBoolean()
            fun finish(value: Boolean?) {
                if (once.compareAndSet(false,true)) continuation.resume(value)
            }
            try {
                query(frontPassenger,::finish) {
                    Log.w("PawriroPleos", "Passenger seat read failed",it)
                    finish(null)
                }
            } catch (error: RuntimeException) {
                Log.w("PawriroPleos", "Passenger seat read failed",error)
                finish(null)
            }
        }

    suspend fun parked(): Boolean = withTimeoutOrNull(1500) {
        suspendCancellableCoroutine { continuation ->
            val once = java.util.concurrent.atomic.AtomicBoolean()
            fun finish(parked: Boolean) {
                if (once.compareAndSet(false,true)) continuation.resume(parked)
            }
            try {
                vehicle.getGear().getCurrentGearSelectionState(
                    { finish(it == ai.pleos.playground.vehicle.constant.control.VehicleGear.GEAR_PARK) },
                    { finish(false) }
                )
            } catch (_: RuntimeException) { finish(false) }
        }
    } == true

    suspend fun driverOccupied(): Boolean? = withTimeoutOrNull(1500) {
        suspendCancellableCoroutine { continuation ->
            val once=java.util.concurrent.atomic.AtomicBoolean()
            fun finish(value: Boolean?) { if (once.compareAndSet(false,true)) continuation.resume(value) }
            runCatching { vehicle.getSeat().getCurrentSeatOccupancy(SeatArea(SeatValue.ROW_1_LEFT),::finish) { finish(null) } }
                .onFailure { finish(null) }
        }
    }

    suspend fun driverDoorOpen(): Boolean? = withTimeoutOrNull(1500) {
        suspendCancellableCoroutine { continuation ->
            val once=java.util.concurrent.atomic.AtomicBoolean()
            fun finish(value: Boolean?) { if (once.compareAndSet(false,true)) continuation.resume(value) }
            val area=ai.pleos.playground.vehicle.api.model.area.DoorArea(
                ai.pleos.playground.vehicle.constant.area.DoorValue.ROW_1_LEFT)
            runCatching { vehicle.getDoor().getCurrentDoorPosition(area,{ value -> finish(value?.let { it>0 }) }) { finish(null) } }
                .onFailure { finish(null) }
        }
    }

    override fun close() { runCatching { vehicle.release() } }
}
