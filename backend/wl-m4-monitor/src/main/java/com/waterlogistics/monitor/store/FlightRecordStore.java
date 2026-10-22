package com.waterlogistics.monitor.store;

import com.waterlogistics.algo.measurement.TelemetryState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 进程内飞行记录仓。
 *
 * <p>当前遥测契约没有 {@code orderId}/{@code taskId}，因此按清洗后状态中的
 * {@code uavId}（即 {@link TelemetryState#flightId()}）归集一段飞行记录。待任务绑定关系
 * 进入遥测契约后，可在这里把归集键替换为正式任务标识，查询层接口无需改变。
 */
@Component
public final class FlightRecordStore {
    private final Map<String, List<TelemetryState>> records = new ConcurrentHashMap<>();
    public void append(TelemetryState state) {
        records.computeIfAbsent(state.flightId(), ignored -> new ArrayList<>());
        var list = records.get(state.flightId());
        synchronized (list) { list.add(state); }
    }
    public List<TelemetryState> replay(String flightId) {
        var list = records.get(flightId);
        if (list == null) return List.of();
        synchronized (list) { return list.stream().sorted(Comparator.comparingLong(TelemetryState::timestamp)).toList(); }
    }
    public List<String> flightIds() { return records.keySet().stream().sorted().toList(); }
    public Map<String, Object> statistics() {
        long flights = records.size();
        long samples = 0;
        long estimated = 0;
        for (List<TelemetryState> list : records.values()) {
            synchronized (list) {
                samples += list.size();
                estimated += list.stream().filter(TelemetryState::imputed).count();
            }
        }
        return Map.of("flights", flights, "samples", samples, "imputedSamples", estimated);
    }
}
