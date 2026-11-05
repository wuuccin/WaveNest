package com.waterlogistics.monitor.store;

import com.waterlogistics.algo.measurement.TelemetryState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 飞行记录仓。运行时存入 Redis，后端进程重启后仍可回放。
 *
 * <p>当前遥测契约没有 {@code orderId}/{@code taskId}，因此按清洗后状态中的
 * {@code uavId}（即 {@link TelemetryState#flightId()}）归集一段飞行记录。待任务绑定关系
 * 进入遥测契约后，可在这里把归集键替换为正式任务标识，查询层接口无需改变。
 */
@Component
public final class FlightRecordStore {
    private static final String KEY_PREFIX = "flight:track:";
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    /** 仅供不启动 Spring 的旧单元测试；运行时始终注入 Redis 构造器。 */
    private final Map<String, List<TelemetryState>> records;

    @Autowired
    public FlightRecordStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.json = JsonMapper.builder().build();
        this.records = null;
    }

    public FlightRecordStore() {
        this.redis = null;
        this.json = null;
        this.records = new ConcurrentHashMap<>();
    }

    public void append(TelemetryState state) {
        if (redis != null) {
            // Redis zset members are unique. The prefix keeps two otherwise identical frames
            // at the same timestamp instead of silently dropping one of them.
            redis.opsForZSet().add(key(state.flightId()),
                    UUID.randomUUID() + "|" + json.writeValueAsString(state), state.timestamp());
            return;
        }
        records.computeIfAbsent(state.flightId(), ignored -> new ArrayList<>());
        var list = records.get(state.flightId());
        synchronized (list) { list.add(state); }
    }
    public List<TelemetryState> replay(String flightId) {
        if (redis != null) {
            var values = redis.opsForZSet().range(key(flightId), 0, -1);
            if (values == null || values.isEmpty()) return List.of();
            return values.stream().map(value -> json.readValue(value.substring(value.indexOf('|') + 1), TelemetryState.class)).toList();
        }
        var list = records.get(flightId);
        if (list == null) return List.of();
        synchronized (list) { return list.stream().sorted(Comparator.comparingLong(TelemetryState::timestamp)).toList(); }
    }
    public List<String> flightIds() {
        if (redis != null) {
            List<String> ids = new ArrayList<>();
            try (var keys = redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
                while (keys.hasNext()) ids.add(keys.next().substring(KEY_PREFIX.length()));
            }
            return ids.stream().sorted().toList();
        }
        return records.keySet().stream().sorted().toList();
    }
    public Map<String, Object> statistics() {
        if (redis != null) {
            List<String> ids = flightIds();
            long samples = 0, estimated = 0;
            for (String id : ids) {
                Long count = redis.opsForZSet().size(key(id));
                samples += count == null ? 0 : count;
                estimated += replay(id).stream().filter(TelemetryState::imputed).count();
            }
            return Map.of("flights", (long) ids.size(), "samples", samples, "imputedSamples", estimated);
        }
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

    private static String key(String flightId) { return KEY_PREFIX + flightId; }
}
