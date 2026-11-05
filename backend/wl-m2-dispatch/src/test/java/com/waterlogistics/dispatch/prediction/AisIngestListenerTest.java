package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AisMessage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/**
 * {@link AisIngestListener} 的失败口径单测（M2 · 卢肄文 · 11-05 序号 1）。
 *
 * <p>守的是三条评审项的<b>行为分界</b>，而不是「日志里写了什么」：
 * <ol>
 *   <li>{@code HIGH.20}——不可预期异常<b>不得</b>原样抛给容器。Spring AMQP 的
 *       {@code defaultRequeueRejected} 缺省为 {@code true}，原样抛出＝无限重投，
 *       一条毒帧就能把整条 {@code data.ais} 队列堵死。改后必须抛
 *       {@link AmqpRejectAndDontRequeueException}（一次进死信、不重投）。</li>
 *   <li>{@code LOW-BUG.5}——{@code message == null} 时 {@code catch} 块不得再解引用它。
 *       改前这一步会在异常处理路径里抛 NPE，把「丢一帧」升级成「消费中断」。</li>
 *   <li>{@code MED-MAINT.9}——契约内的 {@code IllegalArgumentException}（越界观测）
 *       仍然吞掉但不再静默：断言不抛、且不升级成死信。</li>
 * </ol>
 *
 * <p>不驱动真实 {@link PredictionService}：被测的是<b>监听器的异常分类</b>，
 * 用「抛指定异常的桩服务」能把分界钉到确定的异常类型上。
 */
class AisIngestListenerTest {

    private static final AisMessage OBSERVATION =
            new AisMessage("413000001", "测试船", 115.9000, 28.6500, 8.0, 30.0, 1_760_000_000_000L);

    private final PredictionSettings settings =
            new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);

    /** 抛指定异常的服务桩（{@code refresh} 是唯一被监听器调用的入口）。 */
    private PredictionService serviceThrowing(RuntimeException toThrow) {
        return new PredictionService(settings, mmsi -> Optional.empty(), event -> { }) {
            @Override
            public PredictionSnapshot refresh(AisMessage message) {
                throw toThrow;
            }
        };
    }

    @Test
    @DisplayName("★ HIGH.20：不可预期异常转死信，不再原样抛出（否则无限重投堵队列）")
    void unexpectedFailure_shouldGoToDeadLetter() {
        AisIngestListener listener = new AisIngestListener(
                serviceThrowing(new IllegalStateException("下游端口错乱")));

        AmqpRejectAndDontRequeueException thrown = assertThrows(
                AmqpRejectAndDontRequeueException.class,
                () -> listener.onAisMessage(OBSERVATION),
                "必须转成「拒绝且不重投」，而不是让容器按缺省策略重投");

        assertInstanceOf(IllegalStateException.class, thrown.getCause(),
                "原始异常要挂成 cause，否则死信里查不到根因");
    }

    @Test
    @DisplayName("★ LOW-BUG.5：消息为 null 时也要走「转死信」，不能在 catch 块里抛 NPE")
    void nullMessage_shouldRejectCleanlyWithoutNpe() {
        // 真实链路里 null 会先在第 N 处解引用抛 NPE——正是改前 catch 块里
        // message.mmsi() 那个 NPE 的来源。这里用 NPE 桩复现同一条路径。
        AisIngestListener listener = new AisIngestListener(
                serviceThrowing(new NullPointerException("message 为 null")));

        AmqpRejectAndDontRequeueException thrown = assertThrows(
                AmqpRejectAndDontRequeueException.class,
                () -> listener.onAisMessage(null));
        assertInstanceOf(NullPointerException.class, thrown.getCause());
    }

    @Test
    @DisplayName("★ MED-MAINT.9：越界观测（IAE）只丢本帧，不抛、不进死信")
    void invalidObservation_shouldBeDroppedSilently() {
        AisIngestListener listener = new AisIngestListener(
                serviceThrowing(new IllegalArgumentException("经度超出范围：200.0")));

        assertDoesNotThrow(() -> listener.onAisMessage(OBSERVATION),
                "契约内的脏观测必须吞掉：1 Hz 数据流里它是常态，不该占死信、也不该停消费");
    }

    @Test
    @DisplayName("正常观测直接下传，不产生任何异常或拦截")
    void healthyObservation_shouldPassThrough() {
        boolean[] called = {false};
        PredictionService service = new PredictionService(settings,
                mmsi -> Optional.empty(), event -> { }) {
            @Override
            public PredictionSnapshot refresh(AisMessage message) {
                called[0] = true;
                return null;
            }
        };

        assertDoesNotThrow(() -> new AisIngestListener(service).onAisMessage(OBSERVATION));
        assertTrue(called[0], "监听器不得在前置环节擅自丢弃正常观测");
    }
}
