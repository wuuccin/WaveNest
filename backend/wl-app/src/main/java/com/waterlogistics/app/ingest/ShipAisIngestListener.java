package com.waterlogistics.app.ingest;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.storage.entity.ShipAisEntity;
import com.waterlogistics.storage.repository.ShipAisMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 把 {@code data.ais} 的船位观测落库到 ship_ais（T2-4 · 杨添爻）。
 *
 * <p><b>为什么要有本类</b>：ship_ais 表（37 §5.10）在 001_init.sql 里建好、
 * {@link ShipAisEntity}/{@link ShipAisMapper} 也早已就位，但全仓没有任何消费者
 * 把 AIS 帧写进库——M1 发的帧只被 M2 预测链与前端实时出口消费，
 * 库里查不到轨迹点，「实测轨迹」三字落空。本类补上这条最短的落库链：
 *
 * <pre>
 *   M1 --data.ais--> queue: wavenest.m6.data-ais --> 归一 --> ship_ais
 * </pre>
 *
 * <p><b>落点归属（10-28 分工表）</b>：M4 的 {@code ingest/}、{@code track/}
 * 今天归彭紫情，M2 的 {@code prediction/}（已有 {@code AisIngestListener} 消费
 * 同一主题做预测刷新）归卢肄文——本类放 wl-app 新包 {@code app/ingest/}，
 * 与上述包零重叠。两个消费侧队列各挂各的，互不竞争。
 *
 * <p><b>归一口径（契约 22 §2 与 ship_ais 列注释的要求）</b>：
 * {@link AisMessage} 字段保持源原始单位，<b>归一由落库方完成</b>：
 * <ul>
 *   <li>sog：契约单位是<b>节</b>，ship_ais.sog 注释为 m/s → ×0.514444；</li>
 *   <li>lon/lat：M1 仿真源已是 WGS84 十进制度，按 DECIMAL(10,7) 定标；</li>
 *   <li>ts：Unix 毫秒 → 数据库时区 Asia/Shanghai 的 DATETIME。</li>
 * </ul>
 *
 * <p><b>异常口径</b>：单条落库失败只记警告、不抛出——与 M2/M4 的消费侧一致，
 * 一条脏帧不得把整条采集链带停。
 *
 * <p><b>边界（10-28 清单）</b>：不改采集与清洗逻辑，不新增消息类型、不改契约；
 * 队列名与交换器名可由属性覆盖（{@code wavenest.mq.m6-data-ais-queue} /
 * {@code wavenest.mq.exchange}），默认值即可用，不动 application.yml
 * （今日该文件归卢肄文）。
 */
@Component
public class ShipAisIngestListener {

    private static final Logger log = LoggerFactory.getLogger(ShipAisIngestListener.class);

    /** 数据库时区——与 M4 DatabaseFlightPointSink 的 DB_ZONE 同一口径。 */
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 节 → m/s：1 kn = 1852 m / 3600 s = 0.514444…，取 6 位足够 DECIMAL(8,2) 定标。 */
    private static final BigDecimal KNOT_TO_MS = new BigDecimal("0.514444");

    private final ShipAisMapper shipAisMapper;

    public ShipAisIngestListener(ShipAisMapper shipAisMapper) {
        this.shipAisMapper = shipAisMapper;
    }

    /** 消费一条 AIS 船位并写入 ship_ais；单条异常不中断后续消息。 */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "${wavenest.mq.m6-data-ais-queue:wavenest.m6.data-ais}",
                    durable = "true"),
            exchange = @Exchange(
                    name = "${wavenest.mq.exchange:wavenest.data}",
                    type = ExchangeTypes.TOPIC,
                    durable = "true"),
            key = TopicName.DATA_AIS))
    public void onAisMessage(AisMessage message) {
        if (message == null || message.mmsi() == null || message.mmsi().isBlank()) {
            log.warn("丢弃无 MMSI 的 AIS 帧，不落库");
            return;
        }
        try {
            shipAisMapper.insert(toEntity(message));
        } catch (RuntimeException error) {
            log.warn("AIS 轨迹点落库失败，不阻断消费：mmsi={} ts={} err={}",
                    message.mmsi(), message.ts(), error.getMessage());
        }
    }

    /** 契约消息 → ship_ais 行（含单位/时区归一，见类注释）。 */
    ShipAisEntity toEntity(AisMessage message) {
        ShipAisEntity entity = new ShipAisEntity();
        entity.setShipMmsi(message.mmsi());
        entity.setTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(message.ts()), DB_ZONE));
        entity.setLon(scale(message.lon(), 7));
        entity.setLat(scale(message.lat(), 7));
        entity.setSog(scale(message.sog() * KNOT_TO_MS.doubleValue(), 2));
        entity.setCog(scale(message.cog(), 2));
        return entity;
    }

    private static BigDecimal scale(double value, int digits) {
        return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP);
    }
}
