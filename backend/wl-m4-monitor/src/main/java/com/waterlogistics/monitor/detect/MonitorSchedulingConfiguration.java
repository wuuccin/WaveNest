package com.waterlogistics.monitor.detect;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * M4 检测编排定时支持：启用 {@code @Scheduled}（失联 sweep）。
 *
 * <p>wl-app 组装层统一扫描 com.waterlogistics，本配置随 m4 模块自动生效，
 * 无需改动启动类（2026-10-16 全仓仅 M4 失联 sweep 一个定时任务）。</p>
 */
@Configuration
@EnableScheduling
public class MonitorSchedulingConfiguration {
}
