package com.waterlogistics.algo.detection;

/**
 * sys_param 阈值提供者接口（G组所有阈值经此注入）。
 *
 * <p>实现方是 wl-storage 的 SysParamLoader（张睿哲），
 * 算法层不直接访问 DB，避免耦合（包结构设计§二铁律 4）。</p>
 *
 * <p>阈值键名权威在 G 组文档 {@code M4-键名与初值补充知会}，本接口只列出算法层使用的键。</p>
 */
public interface SysParamProvider {

    // ===== 偏航 5 键 =====
    /** 偏航预警阈值（米） */
    double getOfftrackWarningThreshold();
    /** 偏航严重阈值（米） */
    double getOfftrackCriticalThreshold();
    /** 偏航触发去抖点数 */
    int getOfftrackDebounceCount();
    /** 偏航解除去抖点数 */
    int getOfftrackClearCount();
    /** 偏航严重→预警降级去抖点数 */
    int getOfftrackDowngradeCount();

    // ===== 低电量 4 键 =====
    /** 低电量触发阈值（%） */
    double getLowBatteryTrigger();
    /** 低电量清除阈值（%，> trigger） */
    double getLowBatteryClear();
    /** 低电量严重阈值（%，≤ trigger） */
    double getLowBatterySevere();
    /** 低电量严重→警告降级去抖点数 */
    int getLowBatteryDowngradeDebounce();

    // ===== 失联 3 键 =====
    /** 失联判定窗口（秒） */
    long getHeartbeatLostWindowSec();
    /** 灭失判定窗口（秒，> 失联窗） */
    long getHeartbeatLossWindowSec();
    /** 检测轮询间隔（秒） */
    long getHeartbeatCheckIntervalSec();

    // ===== 气象 5 键 =====
    /** 风速预警阈值（m/s） */
    double getWeatherWindWarningThreshold();
    /** 风速严重阈值（m/s） */
    double getWeatherWindCriticalThreshold();
    /** 风速突变阈值（(m/s)/s） */
    double getWeatherWindSurgeThreshold();
    /** 突变检测窗口（秒） */
    int getWeatherSurgeWindowSec();
    /** 清除防抖采样点数 */
    int getWeatherClearDebounce();

    /**
     * 简易实现：返回约定默认值，sys_param 链路（wl-storage）就绪前使用。
     * 真实实现统一从 sys_param 表加载，初值照 {@code M4-键名与初值补充知会}。
     */
    static SysParamProvider defaults() {
        return new SysParamProvider() {
            @Override public double getOfftrackWarningThreshold() { return 30.0; }
            @Override public double getOfftrackCriticalThreshold() { return 50.0; }
            @Override public int getOfftrackDebounceCount() { return 3; }
            @Override public int getOfftrackClearCount() { return 5; }
            @Override public int getOfftrackDowngradeCount() { return 3; }

            @Override public double getLowBatteryTrigger() { return 20.0; }
            @Override public double getLowBatteryClear() { return 30.0; }
            @Override public double getLowBatterySevere() { return 10.0; }
            @Override public int getLowBatteryDowngradeDebounce() { return 3; }

            @Override public long getHeartbeatLostWindowSec() { return 30; }
            @Override public long getHeartbeatLossWindowSec() { return 120; }
            @Override public long getHeartbeatCheckIntervalSec() { return 5; }

            @Override public double getWeatherWindWarningThreshold() { return 8.0; }
            @Override public double getWeatherWindCriticalThreshold() { return 12.0; }
            @Override public double getWeatherWindSurgeThreshold() { return 0.5; }
            @Override public int getWeatherSurgeWindowSec() { return 10; }
            @Override public int getWeatherClearDebounce() { return 5; }
        };
    }
}
