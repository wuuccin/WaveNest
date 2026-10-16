package com.waterlogistics.collector.scheduler;

import com.waterlogistics.collector.connector.DataSourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 固定节拍采集调度器。
 *
 * <p>每节拍驱动所有 {@link DataSourceConnector} 执行一次 {@code pollOnce(tickTs)}。
 * 节拍由 {@code wavenest.sim.tick-millis} 统一配置（连接器内的推进距离也读同一键，保证步长与节拍一致）。
 */
@Component
public class CollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectionScheduler.class);

    private final List<DataSourceConnector> connectors;

    /** 仿真开关：false 时调度器空转（避免联调期误发数据） */
    private final boolean simEnabled;

    public CollectionScheduler(List<DataSourceConnector> connectors,
                               org.springframework.core.env.Environment env) {
        this.connectors = connectors;
        this.simEnabled = Boolean.parseBoolean(env.getProperty("wavenest.sim.enabled", "true"));
    }

    /** 启动时启动所有连接器。 */
    @jakarta.annotation.PostConstruct
    public void startup() {
        if (!simEnabled) {
            log.info("[M1] sim disabled, connectors not started");
            return;
        }
        connectors.forEach(c -> {
            c.start();
            log.info("[M1] connector started: {}", c.name());
        });
    }

    /** 固定节拍：默认 1000ms，与 SimAisConnector 的推进步长同键。 */
    @Scheduled(fixedDelayString = "${wavenest.sim.tick-millis:1000}")
    public void tick() {
        if (!simEnabled) {
            return;
        }
        long tickTs = System.currentTimeMillis();
        for (DataSourceConnector c : connectors) {
            try {
                c.pollOnce(tickTs);
            } catch (Exception e) {
                // 单个连接器异常不拖垮其他连接器
                log.warn("[M1] connector {} tick failed: {}", c.name(), e.getMessage());
            }
        }
    }

    /** 停机时释放连接器。 */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        connectors.forEach(DataSourceConnector::stop);
    }
}
