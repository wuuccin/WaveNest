package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.waterlogistics.storage.entity.SysParamEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.SysParamMapper;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 6a 八键读表与冷却／迟滞／死区／决策截止判据。 */
class RecomputeDecisionGateTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final String ORDER = "ORD-1";
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void params_shouldReadAllEightKeysFromTable() {
        RecomputeDecisionGate gate = new RecomputeDecisionGate(loader(defaultParams()));

        RecomputeDecisionGate.Params p = gate.params();
        assertEquals(20.0, p.thetaThDeg());
        assertEquals(60, p.minTriggerIntervalSec());
        assertEquals(120, p.cooldownSec());
        assertEquals(1.0, p.hystEnterRatio());
        assertEquals(0.6, p.hystExitRatio());
        assertEquals(1.0, p.deadEnterRatio());
        assertEquals(0.6, p.deadExitRatio());
        assertEquals(120, p.decisionDeadlineSec());
    }

    @Test
    void tableValue_shouldChangeThresholdWithoutCodeChange() {
        Map<String, String> values = defaultParams();
        values.put("thetaThDeg", "30");
        RecomputeDecisionGate gate = new RecomputeDecisionGate(loader(values));

        assertEquals(RecomputeDecisionGate.Decision.BELOW_THRESHOLD,
                gate.evaluate(ORDER, 25.0, null, NOW));
        assertEquals(RecomputeDecisionGate.Decision.ALLOW,
                gate.evaluate(ORDER, 35.0, null, NOW));
    }

    @Test
    void firstJump_shouldAllow_thenMinIntervalAndHysteresisLock() {
        RecomputeDecisionGate gate = new RecomputeDecisionGate(loader(defaultParams()));

        assertEquals(RecomputeDecisionGate.Decision.ALLOW,
                gate.evaluate(ORDER, 25.0, null, NOW));
        gate.markTriggered(ORDER, NOW);

        assertEquals(RecomputeDecisionGate.Decision.MIN_INTERVAL,
                gate.evaluate(ORDER, 25.0, null, NOW + 1_000L));
        assertEquals(RecomputeDecisionGate.Decision.HYSTERESIS_LOCK,
                gate.evaluate(ORDER, 25.0, null, NOW + 61_000L));
    }

    @Test
    void deadZone_shouldResetHysteresisAndAllowNextJump() {
        RecomputeDecisionGate gate = new RecomputeDecisionGate(loader(defaultParams()));
        gate.evaluate(ORDER, 25.0, null, NOW);
        gate.markTriggered(ORDER, NOW);

        assertEquals(RecomputeDecisionGate.Decision.DEAD_ZONE,
                gate.evaluate(ORDER, 8.0, null, NOW + 61_000L));
        assertEquals(RecomputeDecisionGate.Decision.ALLOW,
                gate.evaluate(ORDER, 25.0, null, NOW + 122_000L));
    }

    @Test
    void cooldown_shouldBlockAfterCompletedReschedule() {
        RecomputeDecisionGate gate = new RecomputeDecisionGate(loader(defaultParams()));
        gate.markTriggered(ORDER, NOW);
        gate.markCompleted(ORDER, NOW + 61_000L);

        assertEquals(RecomputeDecisionGate.Decision.COOLDOWN,
                gate.evaluate(ORDER, 25.0, null, NOW + 62_000L));
        assertEquals(RecomputeDecisionGate.Decision.ALLOW,
                gate.evaluate(ORDER, 25.0, null, NOW + 182_000L));
    }

    @Test
    void decisionDeadline_shouldBlockAndBeReported() {
        RecomputeDecisionGate gate = new RecomputeDecisionGate(loader(defaultParams()));
        LocalDateTime meetTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(NOW + 60_000L), DB_ZONE);

        assertEquals(RecomputeDecisionGate.Decision.DECISION_DEADLINE,
                gate.evaluate(ORDER, 40.0, meetTime, NOW));
        gate.noteDeadlineReport();
        assertEquals(1L, gate.deadlineReportCount());
    }

    private static Map<String, String> defaultParams() {
        Map<String, String> values = new HashMap<>();
        values.put("thetaThDeg", "20");
        values.put("minTriggerIntervalSec", "60");
        values.put("cooldownSec", "120");
        values.put("hystEnterRatio", "1.0");
        values.put("hystExitRatio", "0.6");
        values.put("deadEnterRatio", "1.0");
        values.put("deadExitRatio", "0.6");
        values.put("rendezvousWindowMarginSec", "120");
        return values;
    }

    private static SysParamLoader loader(Map<String, String> values) {
        List<SysParamEntity> rows = values.entrySet().stream().map(e -> {
            SysParamEntity row = new SysParamEntity();
            row.setParamKey(e.getKey());
            row.setParamValue(e.getValue());
            return row;
        }).toList();
        SysParamMapper mapper = (SysParamMapper) Proxy.newProxyInstance(
                SysParamMapper.class.getClassLoader(), new Class<?>[]{SysParamMapper.class},
                (proxy, method, args) -> "selectList".equals(method.getName()) ? rows : null);
        SysParamLoader loader = new SysParamLoader(mapper);
        loader.refresh();
        return loader;
    }
}
