package com.waterlogistics.monitor.ingest;

import com.waterlogistics.algo.measurement.TelemetryCleaner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M4 遥测清洗配置：提供 {@link TelemetryService} 单例。
 *
 * <p>限值参数与 MAD 阈值均可由属性覆盖，默认与 10-15 单测口径一致：
 * <pre>
 *   wavenest:
 *     m4:
 *       telemetry:
 *         max-speed-ms: 50
 *         max-altitude-m: 5000
 *         max-vertical-rate-ms: 15
 *         max-acceleration-ms2: 20
 *         max-turn-rate-deg-sec: 30
 *         sampling-ms: 1000
 *         mad-threshold: 4.5
 * </pre></p>
 */
@Configuration
public class TelemetryConfig {

    @Value("${wavenest.m4.telemetry.max-speed-ms:50}")
    private double maxSpeedMs;

    @Value("${wavenest.m4.telemetry.max-altitude-m:5000}")
    private double maxAltitudeM;

    @Value("${wavenest.m4.telemetry.max-vertical-rate-ms:15}")
    private double maxVerticalRateMs;

    @Value("${wavenest.m4.telemetry.max-acceleration-ms2:20}")
    private double maxAccelerationMs2;

    @Value("${wavenest.m4.telemetry.max-turn-rate-deg-sec:30}")
    private double maxTurnRateDegSec;

    @Value("${wavenest.m4.telemetry.sampling-ms:1000}")
    private long samplingMs;

    @Value("${wavenest.m4.telemetry.mad-threshold:4.5}")
    private double madThreshold;

    @Bean
    public TelemetryService telemetryService() {
        TelemetryCleaner.Limits limits = new TelemetryCleaner.Limits(
                maxSpeedMs, maxAltitudeM, maxVerticalRateMs,
                maxAccelerationMs2, maxTurnRateDegSec, samplingMs);
        return new TelemetryService(limits, madThreshold);
    }
}
