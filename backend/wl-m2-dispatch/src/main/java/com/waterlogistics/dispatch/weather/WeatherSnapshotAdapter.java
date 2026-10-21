package com.waterlogistics.dispatch.weather;

import com.waterlogistics.contract.mq.WeatherData;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 最新风况快照的仓取实现：按位置与当前时刻从气象快照仓取最近的新鲜观测。
 *
 * <p>取数纪律：只读、无副作用；观测过期或缺失即返回 {@code null}，由调用方回落
 * {@code sys_param} 的静态风况，气象链路异常不得中断调度主链。
 */
@Service
public class WeatherSnapshotAdapter implements WeatherSnapshotPort {

    private final WeatherSnapshotStore store;

    public WeatherSnapshotAdapter(WeatherSnapshotStore store) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
    }

    @Override
    public LatestWind latest(double lon, double lat, long nowMs) {
        return store.nearest(lon, lat, nowMs).map(WeatherSnapshotAdapter::toLatest).orElse(null);
    }

    private static LatestWind toLatest(WeatherData data) {
        double speedMs = data.windSpeedMs();
        double rawDir = data.windDirDeg();
        if (!Double.isFinite(speedMs) || speedMs < 0.0
                || !Double.isFinite(rawDir) || data.obsTs() <= 0L) {
            return null;
        }
        double dirDeg = ((rawDir % 360.0) + 360.0) % 360.0;
        return new LatestWind(speedMs, dirDeg, data.obsTs());
    }
}
