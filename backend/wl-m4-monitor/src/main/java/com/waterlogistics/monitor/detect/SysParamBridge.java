package com.waterlogistics.monitor.detect;

import com.waterlogistics.algo.detection.SysParamProvider;
import com.waterlogistics.storage.param.SysParamLoader;
import org.springframework.stereotype.Component;

/**
 * sys_param → 检测阈值适配桥（M4 detect 侧，2026-10-16）。
 *
 * <p>算法层只依赖 {@link SysParamProvider} 接口（wl-algo 不碰 DB）；
 * 本桥只读 wl-storage 的 {@link SysParamLoader}（张睿哲所有，不改其文件），
 * 按 G 组 18 个 camelCase 键取值（与 37 §5.14.1 已灌库 A 组键同风格），
 * 键缺失/未灌库/值非法时回落 {@link SysParamProvider#defaults()} 兜底值——
 * G 组键灌库前全链路可用默认值跑通，灌库后零改动生效。</p>
 */
@Component
public class SysParamBridge implements SysParamProvider {

    // ===== 偏航 5 键 =====
    public static final String OFFTRACK_WARNING_THRESHOLD = "offtrackWarningThreshold";
    public static final String OFFTRACK_CRITICAL_THRESHOLD = "offtrackCriticalThreshold";
    public static final String OFFTRACK_DEBOUNCE_COUNT = "offtrackDebounceCount";
    public static final String OFFTRACK_CLEAR_COUNT = "offtrackClearCount";
    public static final String OFFTRACK_DOWNGRADE_COUNT = "offtrackDowngradeCount";

    // ===== 低电量 4 键 =====
    public static final String LOW_BATTERY_TRIGGER = "lowBatteryTrigger";
    public static final String LOW_BATTERY_CLEAR = "lowBatteryClear";
    public static final String LOW_BATTERY_SEVERE = "lowBatterySevere";
    public static final String LOW_BATTERY_DOWNGRADE_DEBOUNCE = "lowBatteryDowngradeDebounce";

    // ===== 失联 3 键 =====
    public static final String HEARTBEAT_LOST_WINDOW_SEC = "heartbeatLostWindowSec";
    public static final String HEARTBEAT_LOSS_WINDOW_SEC = "heartbeatLossWindowSec";
    public static final String HEARTBEAT_CHECK_INTERVAL_SEC = "heartbeatCheckIntervalSec";

    // ===== 气象 5 键 =====
    public static final String WEATHER_WIND_WARNING_THRESHOLD = "weatherWindWarningThreshold";
    public static final String WEATHER_WIND_CRITICAL_THRESHOLD = "weatherWindCriticalThreshold";
    public static final String WEATHER_WIND_SURGE_THRESHOLD = "weatherWindSurgeThreshold";
    public static final String WEATHER_SURGE_WINDOW_SEC = "weatherSurgeWindowSec";
    public static final String WEATHER_CLEAR_DEBOUNCE = "weatherClearDebounce";

    private final SysParamLoader loader;
    private final SysParamProvider fallback = SysParamProvider.defaults();

    public SysParamBridge(SysParamLoader loader) {
        this.loader = loader;
    }

    @Override public double getOfftrackWarningThreshold() {
        return loader.getDouble(OFFTRACK_WARNING_THRESHOLD, fallback.getOfftrackWarningThreshold());
    }
    @Override public double getOfftrackCriticalThreshold() {
        return loader.getDouble(OFFTRACK_CRITICAL_THRESHOLD, fallback.getOfftrackCriticalThreshold());
    }
    @Override public int getOfftrackDebounceCount() {
        return loader.getInt(OFFTRACK_DEBOUNCE_COUNT, fallback.getOfftrackDebounceCount());
    }
    @Override public int getOfftrackClearCount() {
        return loader.getInt(OFFTRACK_CLEAR_COUNT, fallback.getOfftrackClearCount());
    }
    @Override public int getOfftrackDowngradeCount() {
        return loader.getInt(OFFTRACK_DOWNGRADE_COUNT, fallback.getOfftrackDowngradeCount());
    }

    @Override public double getLowBatteryTrigger() {
        return loader.getDouble(LOW_BATTERY_TRIGGER, fallback.getLowBatteryTrigger());
    }
    @Override public double getLowBatteryClear() {
        return loader.getDouble(LOW_BATTERY_CLEAR, fallback.getLowBatteryClear());
    }
    @Override public double getLowBatterySevere() {
        return loader.getDouble(LOW_BATTERY_SEVERE, fallback.getLowBatterySevere());
    }
    @Override public int getLowBatteryDowngradeDebounce() {
        return loader.getInt(LOW_BATTERY_DOWNGRADE_DEBOUNCE, fallback.getLowBatteryDowngradeDebounce());
    }

    @Override public long getHeartbeatLostWindowSec() {
        return loader.getInt(HEARTBEAT_LOST_WINDOW_SEC, (int) fallback.getHeartbeatLostWindowSec());
    }
    @Override public long getHeartbeatLossWindowSec() {
        return loader.getInt(HEARTBEAT_LOSS_WINDOW_SEC, (int) fallback.getHeartbeatLossWindowSec());
    }
    @Override public long getHeartbeatCheckIntervalSec() {
        return loader.getInt(HEARTBEAT_CHECK_INTERVAL_SEC, (int) fallback.getHeartbeatCheckIntervalSec());
    }

    @Override public double getWeatherWindWarningThreshold() {
        return loader.getDouble(WEATHER_WIND_WARNING_THRESHOLD, fallback.getWeatherWindWarningThreshold());
    }
    @Override public double getWeatherWindCriticalThreshold() {
        return loader.getDouble(WEATHER_WIND_CRITICAL_THRESHOLD, fallback.getWeatherWindCriticalThreshold());
    }
    @Override public double getWeatherWindSurgeThreshold() {
        return loader.getDouble(WEATHER_WIND_SURGE_THRESHOLD, fallback.getWeatherWindSurgeThreshold());
    }
    @Override public int getWeatherSurgeWindowSec() {
        return loader.getInt(WEATHER_SURGE_WINDOW_SEC, fallback.getWeatherSurgeWindowSec());
    }
    @Override public int getWeatherClearDebounce() {
        return loader.getInt(WEATHER_CLEAR_DEBOUNCE, fallback.getWeatherClearDebounce());
    }
}
