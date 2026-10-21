package com.waterlogistics.algo.estimation;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 航行阶段 {@code navPhase} 的四态状态机（{@code 80-A组算法方案} §3.2.8）。
 *
 * <pre>
 *   在航 ──|v_s| &lt; vStopMs 且持续 tStopSec，窗口位移 &lt; 一船长──▶ 停车
 *   在航 ──|v_s| 下降但未达停车条件，且 ρ &gt; rhoTh ──────────────▶ 减速
 *   停车 ──位置落入锚地／港区多边形且持续 tRaSec ─────────────────▶ 锚泊
 *   减速 ──|v_s| 回升（缺口比 ρ 回落到阈值下）───────────────────▶ 在航
 *   停车 ──|v_s| 回升至 vStopMs + vHysMs 以上且位移恢复──────────▶ 在航
 * </pre>
 *
 * <h3>两条纪律（写错就会被质疑）</h3>
 * <ul>
 *   <li><b>减速用速度缺口比 ρ 判，不用绝对速度</b>：内河过闸／待闸／交汇避让的临时减速
 *       若按绝对速度判会被误当成「停车」，而船停了本次会面必然失败——误报代价很高。
 *       {@code ρ = 1 − |v_s| / v_nominal}，{@code v_nominal} 取该航段限速（本类不改它）。</li>
 *   <li><b>所有速度判据取 {@code |v_s|}</b>：内河双向航道下上行船 {@code v_s} 稳定为负，
 *       这是正常工况；不取绝对值会把所有上行船判成停车。</li>
 * </ul>
 *
 * <p>三重条件（速度阈值 ＋ 持续时间 ＋ 地理语义）缺一不可：{@code abs(v_s) < vStopMs}
 * 只说明「慢」，还要持续 {@code tStopSec} 且窗口内位移小于一船长 {@code lenOverGroundM}，
 * 才能与过闸／待闸的临时减速区分开。
 *
 * <p>本类<b>纯计算</b>：无 Spring、无 DB、无并发写；一艘船一个实例。
 */
public final class NavPhaseTracker {

    /** 航行阶段四态。{@link #label()} 为中文态名，与 `23` 的状态词体系一致。 */
    public enum Phase {
        /** 在航 */
        UNDERWAY("在航"),
        /** 减速（缺口比超阈但未达停车条件） */
        SLOWED("减速"),
        /** 停车 */
        STOPPED("停车"),
        /** 锚泊（已进入锚地／港区多边形） */
        ANCHORED("锚泊");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        /** 中文态名。 */
        public String label() {
            return label;
        }
    }

    /**
     * 状态机参数（取值归 {@code sys_param}；{@link #defaults()} 为附录 B 的仿真初值）。
     *
     * @param vStopMs        P2 停船速度阈值，m/s（判据取 {@code |v_s|}）
     * @param vHysMs         P2 滞后带，m/s
     * @param rhoTh          速度缺口比阈值，用于「减速」态
     * @param tStopSec       P2 驻留时长，s
     * @param tRaSec         P4 受限水域驻留，s（进入锚地的确认时长）
     * @param lenOverGroundM P2 窗口位移上限（一船长），m；按船型参数化
     */
    public record Params(double vStopMs, double vHysMs, double rhoTh, double tStopSec,
            double tRaSec, double lenOverGroundM) {

        public Params {
            if (!(vStopMs > 0.0) || vHysMs < 0.0) {
                throw new IllegalArgumentException("vStopMs 须为正、vHysMs 不得为负：" + vStopMs + "/" + vHysMs);
            }
            if (rhoTh <= 0.0 || rhoTh >= 1.0) {
                throw new IllegalArgumentException("ρ 阈值须在 (0, 1) 内：" + rhoTh);
            }
            if (!(tStopSec > 0.0) || !(tRaSec > 0.0)) {
                throw new IllegalArgumentException("驻留时长必须为正：" + tStopSec + "/" + tRaSec);
            }
            if (!(lenOverGroundM > 0.0)) {
                throw new IllegalArgumentException("一船长必须为正：" + lenOverGroundM);
            }
        }

        /** 附录 B 初值：{@code vStop = 0.5}、{@code vHys = 0.2}、{@code ρ = 0.25}、
         *  {@code tStop = 180}、{@code tRa = 60}；一船长按 40 m 占位（须按船型参数化）。 */
        public static Params defaults() {
            return new Params(0.5, 0.2, 0.25, 180.0, 60.0, 40.0);
        }
    }

    /**
     * 一帧观测。
     *
     * @param tsMs             观测时刻，Unix 毫秒
     * @param s                沿航道弧长估计，m（算窗口位移用）
     * @param vAlongMs         沿航道速度估计，m/s（<b>带符号</b>，判据内部取绝对值）
     * @param nominalSpeedMs   该处航段限速（名义速度），m/s；算缺口比 ρ 用
     * @param inAnchorageOrPort 估计位置是否落入锚地／港区／受限水域多边形
     */
    public record Observation(long tsMs, double s, double vAlongMs, double nominalSpeedMs,
            boolean inAnchorageOrPort) {

        public Observation {
            if (!Double.isFinite(s) || !Double.isFinite(vAlongMs)) {
                throw new IllegalArgumentException("s / v_s 必须为有限值");
            }
        }
    }

    private final Params params;

    private Phase phase = Phase.UNDERWAY;
    private boolean initialized;

    /** 停船条件的起始时刻；不满足时置 {@link Long#MIN_VALUE}。 */
    private long stoppedSinceMs = Long.MIN_VALUE;

    /** 进入锚地／港区的起始时刻；不在多边形内时置 {@link Long#MIN_VALUE}。 */
    private long anchorageSinceMs = Long.MIN_VALUE;

    /** 近窗位移样本（时间戳, 弧长），只保留 {@code tStopSec} 以内。 */
    private final Deque<double[]> displacementWindow = new ArrayDeque<>();

    /**
     * @param params 状态机参数
     */
    public NavPhaseTracker(Params params) {
        if (params == null) {
            throw new IllegalArgumentException("参数不得为 null");
        }
        this.params = params;
    }

    /**
     * 推进一步。
     *
     * @param observation 本帧观测
     * @return 推进后的阶段
     * @throws IllegalStateException 时间未前进时抛出（与 {@link LateralOuFilter} 口径一致）
     */
    public Phase update(Observation observation) {
        long tsMs = observation.tsMs();
        if (initialized) {
            double windowSec = params.tStopSec();
            while (!displacementWindow.isEmpty()
                    && (tsMs - (long) displacementWindow.peekFirst()[0]) / 1000.0 > windowSec) {
                displacementWindow.pollFirst();
            }
            if (!displacementWindow.isEmpty()
                    && tsMs <= (long) displacementWindow.peekLast()[0]) {
                throw new IllegalStateException("时间未前进：" + tsMs);
            }
        }
        displacementWindow.addLast(new double[] {tsMs, observation.s()});

        double absV = Math.abs(observation.vAlongMs());
        boolean belowStop = absV < params.vStopMs();
        boolean windowsStill = windowDisplacementM() < params.lenOverGroundM();

        // ① 停车候选：速度低 ＋ 位移小 ＋ 持续 tStopSec
        if (belowStop && windowsStill) {
            if (stoppedSinceMs == Long.MIN_VALUE) {
                stoppedSinceMs = tsMs;
            }
        } else {
            stoppedSinceMs = Long.MIN_VALUE;
        }
        boolean stopConfirmed = stoppedSinceMs != Long.MIN_VALUE
                && (tsMs - stoppedSinceMs) / 1000.0 >= params.tStopSec();

        // ② 锚地／港区驻留计时
        if (observation.inAnchorageOrPort()) {
            if (anchorageSinceMs == Long.MIN_VALUE) {
                anchorageSinceMs = tsMs;
            }
        } else {
            anchorageSinceMs = Long.MIN_VALUE;
        }
        boolean anchorageConfirmed = anchorageSinceMs != Long.MIN_VALUE
                && (tsMs - anchorageSinceMs) / 1000.0 >= params.tRaSec();

        double rho = speedDeficitRatio(Math.abs(observation.vAlongMs()), observation.nominalSpeedMs());
        boolean recovered = absV >= params.vStopMs() + params.vHysMs();

        phase = nextPhase(phase, stopConfirmed, anchorageConfirmed, recovered, rho,
                observation.inAnchorageOrPort());
        initialized = true;
        return phase;
    }

    /** 当前阶段。 */
    public Phase phase() {
        return phase;
    }

    /** 是否处于「停车」或「锚泊」——{@code 80} §3.2.8 要求停车作为最高优先级事件置入 {@code premise}。 */
    public boolean isStoppedLike() {
        return phase == Phase.STOPPED || phase == Phase.ANCHORED;
    }

    /** 是否已收到过观测。 */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 速度缺口比 {@code ρ = 1 − |v_s| / v_nominal}，夹到 {@code [0, 1]}。
     *
     * @param absV         {@code |v_s|}，m/s
     * @param nominalSpeed 该航段限速，m/s（≤ 0 时返回 0，避免除零）
     */
    public static double speedDeficitRatio(double absV, double nominalSpeed) {
        if (!(nominalSpeed > 0.0)) {
            return 0.0;
        }
        double ratio = 1.0 - absV / nominalSpeed;
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    // ───────────── 内部 ─────────────

    private Phase nextPhase(Phase current, boolean stopConfirmed, boolean anchorageConfirmed,
            boolean recovered, double rho, boolean inPort) {
        if (current == Phase.ANCHORED) {
            // 离开多边形且速度回升才算复航
            if (!inPort && recovered) {
                return Phase.UNDERWAY;
            }
            return Phase.ANCHORED;
        }
        if (current == Phase.STOPPED) {
            if (anchorageConfirmed) {
                return Phase.ANCHORED;
            }
            if (recovered) {
                return Phase.UNDERWAY;
            }
            return Phase.STOPPED;
        }
        // 在航 / 减速
        if (stopConfirmed) {
            return Phase.STOPPED;
        }
        return rho > params.rhoTh() ? Phase.SLOWED : Phase.UNDERWAY;
    }

    /** 窗口内位移（首末弧长之差的绝对值），m；样本不足时返回 0（不给停车确认加分）。 */
    private double windowDisplacementM() {
        if (displacementWindow.size() < 2) {
            return 0.0;
        }
        double first = displacementWindow.peekFirst()[1];
        double last = displacementWindow.peekLast()[1];
        return Math.abs(last - first);
    }
}
