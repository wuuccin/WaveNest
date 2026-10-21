package com.waterlogistics.dispatch.weather;

/**
 * 最新风况快照端口——会面代表风况的取数口（《81-B组算法方案》§3.1 步骤 2）。
 *
 * <p>取数口径：取气象最新一次观测；无观测或数据不可用时返回 {@code null}，
 * 由调用方回落到 {@code sys_param} 的静态风况——气象链路异常不得中断调度主链。
 */
public interface WeatherSnapshotPort {

    /**
     * 最新观测的风况。
     *
     * @return 最新风况；无可用快照时为 {@code null}
     */
    LatestWind latest();

    /**
     * 最新风况观测。
     *
     * @param speedMs 风速，m/s
     * @param dirDeg  风向（来向），度，[0,360)
     * @param obsMs   观测时刻，Unix 毫秒
     */
    record LatestWind(double speedMs, double dirDeg, long obsMs) {
    }
}
