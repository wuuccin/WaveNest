package com.waterlogistics.monitor.store;

import com.waterlogistics.contract.ws.ShipTrajFrame;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 保存真实预测事件的紧凑载荷，供订单时间窗回放使用。 */
@Component
public final class ShipPredictionReplayStore {
    private static final String PREFIX = "replay:ship-prediction:";
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();

    public ShipPredictionReplayStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void append(ShipTrajFrame frame) {
        redis.opsForZSet().add(PREFIX + frame.mmsi(),
                frame.version() + "|" + json.writeValueAsString(frame), frame.t0());
    }

    public List<ShipTrajFrame> range(String mmsi, long start, long end) {
        var values = redis.opsForZSet().rangeByScore(PREFIX + mmsi, start, end);
        if (values == null) return List.of();
        return values.stream().map(value -> json.readValue(value.substring(value.indexOf('|') + 1), ShipTrajFrame.class)).toList();
    }
}
