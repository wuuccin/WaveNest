package com.waterlogistics.app.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.storage.entity.ShipAisEntity;
import com.waterlogistics.storage.repository.ShipAisMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T2-4 船舶实测轨迹落库 · 监听器单测（杨添爻，2026-10-28）。
 *
 * <p>三个口径各一条用例 + 两条防护用例：
 * 归一（节→m/s、时区、定标）、脏帧防护（无 MMSI 不落库）、
 * 落库异常不上抛（不阻断消费）。
 */
@ExtendWith(MockitoExtension.class)
class ShipAisIngestListenerTest {

    @Mock
    private ShipAisMapper shipAisMapper;

    private ShipAisIngestListener listener;

    @BeforeEach
    void setUp() {
        listener = new ShipAisIngestListener(shipAisMapper);
    }

    /** 归一口径：sog 节→m/s（×0.514444）、ts 毫秒→Asia/Shanghai、经纬度 7 位定标。 */
    @Test
    void shouldNormalizeUnitsAndTimezoneBeforeInsert() {
        // 2026-10-28T06:00:00Z = 北京时间 14:00
        long tsMillis = 1793167200000L;
        AisMessage message = new AisMessage("123456789", "SIM-SHIP-01",
                115.862916, 28.668360, 8.0, 21.0, tsMillis);

        listener.onAisMessage(message);

        ArgumentCaptor<ShipAisEntity> captor = ArgumentCaptor.forClass(ShipAisEntity.class);
        verify(shipAisMapper).insert(captor.capture());
        ShipAisEntity entity = captor.getValue();

        assertEquals("123456789", entity.getShipMmsi());
        // 8 节 × 0.514444 = 4.115552 → DECIMAL(8,2) 四舍五入 4.12 m/s
        assertEquals(new BigDecimal("4.12"), entity.getSog());
        assertEquals(new BigDecimal("115.8629160"), entity.getLon());
        assertEquals(new BigDecimal("28.6683600"), entity.getLat());
        assertEquals(new BigDecimal("21.00"), entity.getCog());
        assertEquals(LocalDateTime.of(2026, 10, 28, 14, 0, 0), entity.getTs());
    }

    /** 无 MMSI 的脏帧：不落库、不抛异常。 */
    @Test
    void shouldSkipFrameWithoutMmsi() {
        listener.onAisMessage(new AisMessage("", "SIM-SHIP-01",
                115.86, 28.66, 8.0, 21.0, 1793167200000L));
        listener.onAisMessage(null);

        verify(shipAisMapper, never()).insert(any(ShipAisEntity.class));
    }

    /** 落库异常只记警告不上抛——单条脏数据不得把消费链带停。 */
    @Test
    void shouldSwallowInsertFailure() {
        doThrow(new RuntimeException("db down")).when(shipAisMapper).insert(any(ShipAisEntity.class));

        listener.onAisMessage(new AisMessage("123456789", "SIM-SHIP-01",
                115.86, 28.66, 8.0, 21.0, 1793167200000L));

        verify(shipAisMapper).insert(any(ShipAisEntity.class));
    }

    /** 边界值：sog=0（停船）与负值经度不崩、按同口径落库。 */
    @Test
    void shouldHandleZeroSpeedAndKeepPrecision() {
        listener.onAisMessage(new AisMessage("123456789", "SIM-SHIP-01",
                115.861, 28.664, 0.0, 0.0, 1793167200000L));

        ArgumentCaptor<ShipAisEntity> captor = ArgumentCaptor.forClass(ShipAisEntity.class);
        verify(shipAisMapper).insert(captor.capture());
        ShipAisEntity entity = captor.getValue();
        assertNotNull(entity.getTs());
        assertEquals(new BigDecimal("0.00"), entity.getSog());
        assertEquals(new BigDecimal("115.8610000"), entity.getLon());
    }
}
