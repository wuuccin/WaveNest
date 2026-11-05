package com.waterlogistics.collector.scheduler;

import com.waterlogistics.collector.connector.DataSourceConnector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.AbstractEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集调度器单测：启动/节拍/开关/容错四条行为。
 *
 * <p>用最小 Environment 实现代替 Spring 容器，直接用构造器装配。
 */
class CollectionSchedulerTest {

    /** 最小 Environment 实现：只回答仿真开关键，避免引入 spring-test 依赖。 */
    private static Environment envWith(String enabled) {
        return new AbstractEnvironment() {
            @Override
            public String getProperty(String key) {
                return "wavenest.sim.enabled".equals(key) ? enabled : null;
            }

            @Override
            public String getProperty(String key, String defaultValue) {
                String v = getProperty(key);
                return v != null ? v : defaultValue;
            }
        };
    }

    private static Environment defaultEnv() {
        return envWith("true");
    }

    /** 记录调用次数的假连接器 */
    private static final class FakeConnector implements DataSourceConnector {
        private final String name;
        private int startCount;
        private int stopCount;
        private final List<Long> ticks = new ArrayList<>();
        private boolean throwOnPoll;

        FakeConnector(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void start() {
            startCount++;
        }

        @Override
        public void pollOnce(long tickTs) {
            if (throwOnPoll) {
                throw new IllegalStateException("boom");
            }
            ticks.add(tickTs);
        }

        @Override
        public void stop() {
            stopCount++;
        }
    }

    @Test
    @DisplayName("启动时逐个 start；关机时逐个 stop")
    void startsAndStopsAllConnectors() {
        FakeConnector a = new FakeConnector("a");
        FakeConnector b = new FakeConnector("b");
        CollectionScheduler scheduler = new CollectionScheduler(List.of(a, b), defaultEnv());

        scheduler.startup();
        assertEquals(1, a.startCount);
        assertEquals(1, b.startCount);

        scheduler.shutdown();
        assertEquals(1, a.stopCount);
        assertEquals(1, b.stopCount);
    }

    @Test
    @DisplayName("一个节拍驱动所有连接器，且传入同一时间戳")
    void oneTickDrivesAllConnectorsWithSameTs() {
        FakeConnector a = new FakeConnector("a");
        FakeConnector b = new FakeConnector("b");
        CollectionScheduler scheduler = new CollectionScheduler(List.of(a, b), defaultEnv());
        scheduler.startup();

        scheduler.tick();

        assertEquals(1, a.ticks.size());
        assertEquals(1, b.ticks.size());
        assertEquals(a.ticks.get(0), b.ticks.get(0), "同一批数据须共享时间戳");
    }

    @Test
    @DisplayName("仿真开关关闭：不启动任何连接器，节拍空转")
    void disabledSimDoesNothing() {
        FakeConnector a = new FakeConnector("a");
        CollectionScheduler scheduler = new CollectionScheduler(List.of(a), envWith("false"));

        scheduler.startup();
        scheduler.tick();

        assertEquals(0, a.startCount, "关闭时不应启动连接器");
        assertTrue(a.ticks.isEmpty(), "关闭时不应采数");
    }

    @Test
    @DisplayName("单个连接器抛异常不拖垮其他连接器")
    void singleFailureDoesNotBlockOthers() {
        FakeConnector bad = new FakeConnector("bad");
        bad.throwOnPoll = true;
        FakeConnector good = new FakeConnector("good");
        CollectionScheduler scheduler = new CollectionScheduler(List.of(bad, good), defaultEnv());
        scheduler.startup();

        scheduler.tick();

        assertEquals(1, good.ticks.size(), "坏连接器不应阻断好连接器");
    }

    @Test
    @DisplayName("仿真默认开启（无配置键时按 true 处理）")
    void enabledByDefault() {
        FakeConnector a = new FakeConnector("a");
        CollectionScheduler scheduler = new CollectionScheduler(List.of(a), defaultEnv());

        scheduler.startup();

        assertFalse(a.startCount == 0, "缺省应视为开启");
    }
}
