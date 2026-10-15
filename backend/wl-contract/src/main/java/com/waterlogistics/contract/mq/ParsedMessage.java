package com.waterlogistics.contract.mq;

/**
 * 模块间异步消息的统一信封——《22-接口契约》v1.2 §1.1。
 *
 * <p>所有解析后的异步消息一律按本信封封装，{@code type} 决定下游走哪套处理逻辑，
 * {@code payload} 的有效类型见 {@code mq} 包内的各数据对象。
 *
 * @param <T> 载荷类型
 */
public record ParsedMessage<T>(

        /** 来源标识（数据源或模块） */
        String sourceId,

        /** 消息类型，决定下游走哪套处理逻辑 */
        String type,

        /** 打点时间，Unix 毫秒 */
        long ts,

        /** 结构化载荷对象 */
        T payload) {
}
