package kr.routerescue.demo;

import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.hardware.property.CarPropertyManager;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.util.Log;
import java.util.function.Consumer;

final class VehicleRestrictions implements AutoCloseable {
    private Car car;
    private CarUxRestrictionsManager manager;
    private volatile CarPropertyManager properties;
    private final Consumer<Boolean> notify;

    VehicleRestrictions(Context context, Consumer<Boolean> notify) {
        this.notify = notify;
        car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, this::onConnection);
    }
    private void onConnection(Car car, boolean ready) {
        manager = null;
        properties = null;
        notify.accept(true);
        if (!ready) return;
        try {
            properties = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            manager = (CarUxRestrictionsManager) car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
            if (manager != null) {
                manager.registerListener(this::update);
                update(manager.getCurrentCarUxRestrictions());
            }
        } catch (RuntimeException unavailable) {
            notify.accept(true);
        }
    }
    private void update(CarUxRestrictions value) {
        notify.accept(value == null || value.isRequiresDistractionOptimization()
            || value.getActiveRestrictions() != CarUxRestrictions.UX_RESTRICTIONS_BASELINE);
    }
    Boolean parked() {
        CarPropertyManager current = properties;
        if (current == null) return null;
        try {
            int gear = current.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0);
            return gear == VehicleGear.GEAR_PARK;
        } catch (RuntimeException unavailable) {
            Log.w("PawriroPleos", "Android Automotive gear selection unavailable", unavailable);
            return null;
        }
    }
    @Override public void close() {
        if (manager != null) manager.unregisterListener();
        if (car != null) car.disconnect();
    }
}
