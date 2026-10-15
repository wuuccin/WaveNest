package com.waterlogistics.contract.mq;

/**
 * 航道与空域公告抽取结果，对应主题 {@link TopicName#DATA_NOTICE}。
 *
 * <p>依据《22-接口契约》v1.2 §2。
 *
 * @param effectiveWindow 生效时段，字符串表述，具体格式待 M1 最终定（本文档未约束）
 * @param sourceUrl       公告原文链接
 */
public record AirspaceNotice(

        /** 公告标题 */
        String title,

        /** 影响区域 */
        String area,

        /** 公告正文 */
        String content,

        String effectiveWindow,

        String sourceUrl) {
}
