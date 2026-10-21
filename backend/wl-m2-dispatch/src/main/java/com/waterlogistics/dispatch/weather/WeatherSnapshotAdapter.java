package com.waterlogistics.dispatch.weather;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.storage.entity.WeatherEntity;
import com.waterlogistics.storage.repository.WeatherMapper;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 最新风况快照的库取实现——读 {@code weather} 表最新一条观测。
 *
 * <p>取数纪律：只读、无副作用；风速非负且有限、风向回卷到 [0,360) 才可用；
 * 观测时刻或风要素缺失时返回 {@code null}，由调用方回落 {@code sys_param}。
 */
@Service
public class WeatherSnapshotAdapter implements WeatherSnapshotPort {

    /** 表内时刻按演示期统一东八区解释（与 {@code application.yml} 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final WeatherMapper weatherMapper;

    public WeatherSnapshotAdapter(WeatherMapper weatherMapper) {
        this.weatherMapper = Objects.requireNonNull(weatherMapper, "weatherMapper 不能为空");
    }

    @Override
    public LatestWind latest() {
        WeatherEntity latest = weatherMapper.selectOne(new LambdaQueryWrapper<WeatherEntity>()
                .orderByDesc(WeatherEntity::getTs)
                .last("LIMIT 1"));
        return toLatest(latest);
    }

    private static LatestWind toLatest(WeatherEntity entity) {
        if (entity == null || entity.getTs() == null) {
            return null;
        }
        BigDecimal speed = entity.getWindSpeed();
        BigDecimal dir = entity.getWindDir();
        if (speed == null || dir == null) {
            return null;
        }
        double speedMs = speed.doubleValue();
        double rawDir = dir.doubleValue();
        if (!Double.isFinite(speedMs) || speedMs < 0.0 || !Double.isFinite(rawDir)) {
            return null;
        }
        double dirDeg = ((rawDir % 360.0) + 360.0) % 360.0;
        long obsMs = entity.getTs().atZone(ZONE).toInstant().toEpochMilli();
        return new LatestWind(speedMs, dirDeg, obsMs);
    }
}
