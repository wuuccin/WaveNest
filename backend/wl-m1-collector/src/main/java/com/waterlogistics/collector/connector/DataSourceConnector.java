package com.waterlogistics.collector.connector;

/**
 * 数据源连接器 SPI（照 30 附录 B：接口不加 I 前缀）。
 *
 * <p>仿真器与将来的真实 AIS/气象接入都是本接口的实现——换真实源时只换实现类，下游零改动。
 * 生命周期：{@link #start()} 后由调度器按固定节拍调 {@link #pollOnce(long)}，
 * {@link #stop()} 释放资源。
 */
public interface DataSourceConnector {

    /** 连接器标识（如 "sim-ais"），用于日志与配置绑定。 */
    String name();

    /** 启动连接器（演示期仿真器为空操作或重置内部状态）。 */
    default void start() {
    }

    /**
     * 采集一个节拍：产出 0..n 条数据并交给发布器。
     *
     * @param tickTs 本节拍时间戳（epoch ms），仿真器据此生成同批次数据的 ts
     */
    void pollOnce(long tickTs);

    /** 停止连接器。 */
    default void stop() {
    }
}
