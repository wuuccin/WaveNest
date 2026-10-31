package com.waterlogistics.monitor.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.contract.mq.UavTelemetry;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/** Redis operations replacement keeps the same data across store instances (a process restart). */
class FlightRecordStoreRedisTest {
    @Test
    void replayAndStatisticsSurviveNewStoreInstance() {
        InMemoryRedis redis = new InMemoryRedis();
        FlightRecordStore beforeRestart = new FlightRecordStore(redis);
        beforeRestart.append(state("UAV-RESTART", 2_000, false));
        beforeRestart.append(state("UAV-RESTART", 1_000, true));

        FlightRecordStore afterRestart = new FlightRecordStore(redis);
        afterRestart.append(state("UAV-RESTART", 3_000, false));

        List<TelemetryState> replay = afterRestart.replay("UAV-RESTART");
        assertEquals(List.of(1_000L, 2_000L, 3_000L),
                replay.stream().map(TelemetryState::timestamp).toList());
        assertTrue(replay.get(0).imputed());
        assertEquals("UAV-RESTART", replay.get(0).telemetry().uavId());
        assertEquals(Map.of("battery", 31.2, "source", "mqtt"), replay.get(0).rawValues());
        assertEquals(List.of("UAV-RESTART"), afterRestart.flightIds());
        assertEquals(Map.of("flights", 1L, "samples", 3L, "imputedSamples", 1L),
                afterRestart.statistics());
    }

    @Test
    void equalTimestampFramesAreNotDeduplicated() {
        InMemoryRedis redis = new InMemoryRedis();
        FlightRecordStore store = new FlightRecordStore(redis);
        TelemetryState sample = state("UAV-SAME-TS", 1_000, false);
        store.append(sample);
        store.append(sample);

        assertEquals(2, new FlightRecordStore(redis).replay("UAV-SAME-TS").size());
    }

    private static TelemetryState state(String id, long ts, boolean imputed) {
        return new TelemetryState(new UavTelemetry(id, 115.86, 28.67, 100, 10, 72, 31.2, ts),
                "ok", imputed, "good", List.of(), null, ts, "WGS-84", Map.of("battery", 31.2, "source", "mqtt"));
    }

    private static final class InMemoryRedis extends StringRedisTemplate {
        private final Map<String, TreeMap<Double, Set<String>>> values = new TreeMap<>();
        @SuppressWarnings("unchecked")
        private final ZSetOperations<String, String> sorted = (ZSetOperations<String, String>) Proxy.newProxyInstance(
                ZSetOperations.class.getClassLoader(), new Class<?>[] { ZSetOperations.class }, (proxy, method, args) -> {
                    String key = (String) args[0];
                    if (method.getName().equals("add")) {
                        return values.computeIfAbsent(key, ignored -> new TreeMap<>())
                                .computeIfAbsent((Double) args[2], ignored -> new LinkedHashSet<>())
                                .add((String) args[1]);
                    }
                    List<String> ordered = values.getOrDefault(key, new TreeMap<>()).values().stream()
                            .flatMap(Set::stream).toList();
                    if (method.getName().equals("size")) return (long) ordered.size();
                    if (method.getName().equals("range")) {
                        int from = ((Long) args[1]).intValue();
                        int to = ((Long) args[2]).intValue();
                        return new LinkedHashSet<>(ordered.subList(from, to < 0 ? ordered.size() : Math.min(to + 1, ordered.size())));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        @Override public ZSetOperations<String, String> opsForZSet() { return sorted; }

        @Override public Cursor<String> scan(ScanOptions options) {
            List<String> keys = new ArrayList<>(values.keySet());
            var iterator = keys.iterator();
            return new Cursor<>() {
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public String next() { return iterator.next(); }
                @Override public void close() { }
                @Override public Cursor.CursorId getId() { return Cursor.CursorId.initial(); }
                @Override public long getCursorId() { return 0L; }
                @Override public boolean isClosed() { return false; }
                @Override public long getPosition() { return 0L; }
            };
        }
    }
}
