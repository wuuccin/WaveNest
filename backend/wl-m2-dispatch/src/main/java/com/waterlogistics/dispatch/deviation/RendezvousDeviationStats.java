package com.waterlogistics.dispatch.deviation;

import com.waterlogistics.contract.mq.RendezvousDeviationMessage;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.DoubleAdder;
import org.springframework.stereotype.Component;

/**
 * 会面偏差回流分层统计（M2 侧消费方，《86-G组算法方案》§5）。
 *
 * <p>偏差样本是<b>数据回流</b>不是告警：不写 {@code alert} 表、不产级别、不改
 * {@code sys_param}。本类只按机型／归属机巢／风况段／船速段四层聚合，供纠偏阈值标定
 * 与会面策略偏好分析取数（E 组功能 17、A 组功能 2）。
 *
 * <p>纯内存聚合，不落库：本批只补「消费方接线」，不新增表、不新增非代码文件。
 */
@Component
public class RendezvousDeviationStats {

    /** 分层键：四层取值，空值统一写 {@code "-"}，避免 null 参与拼接。 */
    public record Key(String model, String homeNestId, String windCondition, String shipSpeedRange) {
        static Key of(RendezvousDeviationMessage msg) {
            return new Key(text(msg.model()), text(msg.homeNestId()),
                    text(msg.windCondition()), text(msg.shipSpeedRange()));
        }

        private static String text(String value) {
            return value == null || value.isBlank() ? "-" : value;
        }
    }

    /** 一层的聚合结果。 */
    public record Bucket(Key key, long samples, double meanSpaceM, double meanTimeS, double maxSpaceM) {
    }

    private static final class Accumulator {
        private final LongAdder samples = new LongAdder();
        private final DoubleAdder spaceSum = new DoubleAdder();
        private final DoubleAdder timeSum = new DoubleAdder();
        private volatile double maxSpaceM;

        synchronized void accept(double dSpaceM, double dTimeS) {
            samples.increment();
            spaceSum.add(dSpaceM);
            timeSum.add(dTimeS);
            if (dSpaceM > maxSpaceM) {
                maxSpaceM = dSpaceM;
            }
        }

        synchronized Bucket toBucket(Key key) {
            long n = samples.sum();
            return new Bucket(key, n,
                    n == 0 ? 0.0 : spaceSum.sum() / n,
                    n == 0 ? 0.0 : timeSum.sum() / n,
                    maxSpaceM);
        }
    }

    private final Map<Key, Accumulator> buckets = new ConcurrentHashMap<>();

    public void accept(RendezvousDeviationMessage message) {
        Objects.requireNonNull(message, "偏差样本不能为空");
        double space = Double.isFinite(message.dSpaceM()) && message.dSpaceM() >= 0.0
                ? message.dSpaceM() : 0.0;
        double time = Double.isFinite(message.dTimeS()) ? message.dTimeS() : 0.0;
        buckets.computeIfAbsent(Key.of(message), key -> new Accumulator()).accept(space, time);
    }

    /** 当前各层快照，按层键字典序返回，便于比对。 */
    public List<Bucket> snapshot() {
        return buckets.entrySet().stream()
                .map(e -> e.getValue().toBucket(e.getKey()))
                .sorted((a, b) -> a.key().toString().compareTo(b.key().toString()))
                .toList();
    }

    public long sampleCount() {
        return buckets.values().stream().mapToLong(a -> a.samples.sum()).sum();
    }

    public void clear() {
        buckets.clear();
    }
}
