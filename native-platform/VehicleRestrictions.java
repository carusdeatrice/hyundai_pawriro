package kr.routerescue.demo;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import java.util.function.Consumer;

final class VehicleRestrictions implements AutoCloseable {
    private Car car;
    private CarUxRestrictionsManager manager;
    private final Consumer<Boolean> notify;

    VehicleRestrictions(Context context, Consumer<Boolean> notify) {
        this.notify = notify;
        car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, this::onConnection);
    }
    private void onConnection(Car car, boolean ready) {
        manager = null;
        notify.accept(true);
        if (!ready) return;
        try {
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
    @Override public void close() {
        if (manager != null) manager.unregisterListener();
        if (car != null) car.disconnect();
    }
}
