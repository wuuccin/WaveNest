package com.waterlogistics.app.recompute;

import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.dispatch.taskstate.DronePositionPort;
import com.waterlogistics.monitor.store.FlightRecordStore;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link DronePositionPort} 的装配实现：从 M4 已落库的飞行记录中取该机最后一个几何可用位置。
 *
 * <p><b>不复用 MQ 订阅</b>：`data.uav.telemetry` 契约规定「遥测只接入一次：M4 不另建订阅链路」，
 * 故这里只读 {@link FlightRecordStore#replay(String)}，与 M4 的
 * {@code AlertRecorder#lastKnownPosition} 使用同一取数口径（倒扫、跳过补插帧与几何不可用帧）。
 *
 * <p><b>性能</b>：每次调用检索该机全量记录；调用方按 2s 一拍的在飞订单调用，当前量级可接受。
 * 若后续记录增长，可在此加短 TTL 缓存而不影响调用方。
 */
@Component
public class FlightRecordStoreDronePositionAdapter implements DronePositionPort {

    private final FlightRecordStore flightRecordStore;

    public FlightRecordStoreDronePositionAdapter(FlightRecordStore flightRecordStore) {
        this.flightRecordStore = flightRecordStore;
    }

    @Override
    public Optional<Position> latest(String droneId) {
        if (droneId == null || droneId.isBlank()) {
            return Optional.empty();
        }
        List<TelemetryState> track = flightRecordStore.replay(droneId);
        for (int i = track.size() - 1; i >= 0; i--) {
            TelemetryState state = track.get(i);
            if (!state.imputed() && state.geometryUsable()) {
                return Optional.of(new Position(state.longitude(), state.latitude()));
            }
        }
        return Optional.empty();
    }
}
