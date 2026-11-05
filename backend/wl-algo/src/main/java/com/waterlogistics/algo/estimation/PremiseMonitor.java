package com.waterlogistics.algo.estimation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 步骤 F 的<b>事实层与统计层</b>：预测前提监测（{@code 80-A组算法方案} §3.2.8）。
 *
 * <p>职责边界必须先说清（这是与 E 组功能 17 的分工，越界就会双头维护）：
 * <table border="1">
 *   <caption>三层拆解</caption>
 *   <tr><th>层</th><th>本类做不做</th><th>内容</th></tr>
 *   <tr><td>事实层</td><td><b>做</b></td><td>P0–P4 确定性判据 ＋ 数据质量</td></tr>
 *   <tr><td>统计层</td><td><b>做</b></td><td>归一化偏差序列 {@code zSeries} ＋ <b>保守粗标记</b>（M-of-N）</td></tr>
 *   <tr><td>确认层</td><td><b>不做</b></td><td>累积和检验、6a/6b 分档、阶段状态机、与纠偏截止时刻比对
 *       —— 归 E 组功能 17（阈值比较与指令下发都在纠偏侧）</td></tr>
 * </table>
 *
 * <h3>为什么粗标记用 M-of-N 而不是双阈值迟滞</h3>
 * <p>迟滞管的是<b>输出端复位</b>，并不保证「持续成立才触发」——单帧尖峰照样会触发。
 * 真正防误触发的是<b>累积逻辑</b>；M-of-N 可解释性最强、检测概率可解析、响应快，
 * 适合做「不追求最优」的粗门槛。更灵敏的主判据由下游直接在 {@code zSeries} 上累积。
 *
 * <h3>方向参考必须锁初始值（最容易写错的一条）</h3>
 * <p>P1 的 {@code courseRef} 以 {@code dirRef0}（接单／进入本单时锁定的方向）为准，
 * <b>不能跟着 {@code v_s} 实时翻转</b>：若 {@code courseRef} 随方向实时跟随，真掉头时
 * {@code cog} 与 {@code courseRef} 会同时翻转、{@code dCogDeg} 反而 ≈ 0，掉头就测不出来。
 * {@code dirRef} 的实时变化只用于 P0 与投影方向。
 *
 * <h3>判据优先级与语义</h3>
 * <p>{@code P4 > P3 > P0 > P2 > P1}（P1 是疑似，排在确定性事实之后）。
 * {@link Verdict#NOT_APPLICABLE} 表示<b>预测在此情形下不适用</b>（船已进锚地／港区，
 * 本单不可能按原方案完成），与 {@link Verdict#INVALID}（可重算）在语义上分开，
 * 供 M2 区别处置。{@link Verdict#SUSPECT} 表示前提<b>仍成立</b>（偏航属量变），
 * 只是触发下游「重新获取预测」的粗门槛。
 *
 * <p>本类<b>纯计算</b>：无 Spring、无 DB；一艘船一个实例，单线程使用。
 */
public final class PremiseMonitor {

    /** 事实层结论。{@link #contractCode()} 可原样写入 {@code ShipTrajFrame.premise}（该字段为 String）。 */
    public enum Verdict {
        /** 前提成立，轨迹可采信 */
        VALID("VALID"),
        /** 疑似偏航：前提仍成立，但触发下游「重新获取预测」的粗门槛 */
        SUSPECT("SUSPECT"),
        /** 前提已被推翻，轨迹不应被下游采信，可重算 */
        INVALID("INVALID"),
        /** 预测在此情形下不适用（已进锚地／港区），与 INVALID 语义分开 */
        NOT_APPLICABLE("NOT_APPLICABLE"),
        /** 证据不足（冷启动／观测中断） */
        UNKNOWN("UNKNOWN");

        private final String code;

        Verdict(String code) {
            this.code = code;
        }

        /** 写入 {@code ShipTrajFrame.premise} 的字符串编码。 */
        public String contractCode() {
            return code;
        }

        /**
         * 映射到当前三值契约枚举 {@link PremiseStatus}。
         *
         * <p><b>有损映射，仅用于仍只认三值的老口径</b>：{@link #SUSPECT} → {@code VALID}
         * （前提仍成立）、{@link #NOT_APPLICABLE} → {@code INVALID}（不应采信）。
         * 新口径请直接用 {@link #contractCode()}，{@code premise} 字段本身是 String，
         * 能承载五值，不必压缩。
         */
        public PremiseStatus toStatus() {
            return switch (this) {
                case VALID, SUSPECT -> PremiseStatus.VALID;
                case INVALID, NOT_APPLICABLE -> PremiseStatus.INVALID;
                case UNKNOWN -> PremiseStatus.UNKNOWN;
            };
        }
    }

    /** 触发原因。 */
    public enum Reason {
        /** 无异常 */
        NONE("NONE"),
        /** P0 方向翻转 */
        REVERSED("REVERSED"),
        /** P1 偏航（粗标记） */
        DEVIATED("DEVIATED"),
        /** P2 持续趋零／停车 */
        STOPPED("STOPPED"),
        /** P3 分汊歧义 */
        BRANCH_AMBIGUOUS("BRANCH_AMBIGUOUS"),
        /** P4 受限水域 */
        RESTRICTED_AREA("RESTRICTED_AREA"),
        /** 证据不足 */
        INSUFFICIENT_DATA("INSUFFICIENT_DATA");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        /** 原因码。 */
        public String code() {
            return code;
        }
    }

    /**
     * 判据参数（取值归 {@code sys_param}；{@link #defaults()} 为附录 B 仿真初值）。
     *
     * @param vTurnMinMs   P0 转向判定速度下限，m/s（低于此速度时方向参考与航向判据<b>冻结</b>）
     * @param tDirSec      P0 方向翻转最短驻留，s
     * @param psiThrDeg    P1 偏航角阈值，度
     * @param tPsiSec      P1 驻留时长，s
     * @param sigmaCogDeg  航向差归一化尺度 {@code sigmaCogDeg}，度（算 {@code zCog}）
     * @param mOfN_M       统计层粗标记的 M
     * @param mOfN_N       统计层粗标记的 N
     * @param bpInM        P3 分汊点前邻域，m
     * @param bpOutM       P3 分汊点后邻域，m
     * @param tBpSec       P3 驻留时长，s
     * @param tRaSec       P4 受限水域驻留，s
     * @param zGate        统计层单点判「超限」的归一化门限（通常 1.0，即 1σ）
     * @param confirmWindow 归一化偏差序列窗长（随快照下发 {@code zSeries}，与下游 {@code confirmWindow} 对齐）
     */
    public record Params(double vTurnMinMs, double tDirSec, double psiThrDeg, double tPsiSec,
            double sigmaCogDeg, int mOfN_M, int mOfN_N, double bpInM, double bpOutM, double tBpSec,
            double tRaSec, double zGate, int confirmWindow) {

        public Params {
            if (!(vTurnMinMs > 0.0) || !(tDirSec > 0.0)) {
                throw new IllegalArgumentException("vTurnMinMs / tDirSec 必须为正");
            }
            if (!(psiThrDeg > 0.0) || !(tPsiSec > 0.0) || !(sigmaCogDeg > 0.0)) {
                throw new IllegalArgumentException("ψ 阈值 / tPsi / sigmaCog 必须为正");
            }
            if (mOfN_M < 1 || mOfN_N < 1 || mOfN_M > mOfN_N) {
                throw new IllegalArgumentException("M-of-N 非法：" + mOfN_M + "/" + mOfN_N);
            }
            if (!(bpInM >= 0.0) || !(bpOutM >= 0.0) || !(tBpSec > 0.0) || !(tRaSec > 0.0)) {
                throw new IllegalArgumentException("P3/P4 参数非法");
            }
            if (!(zGate > 0.0) || confirmWindow < 1) {
                throw new IllegalArgumentException("zGate / confirmWindow 非法：" + zGate + "/" + confirmWindow);
            }
        }

        /** 附录 B 仿真初值：{@code vTurn=0.5}、{@code tDir=30}、{@code psi=20}、{@code tPsi=120}、
         *  {@code sigmaCog=8}、{@code mOfN=3/5}、{@code bpIn=bpOut=300}、{@code tBp=60}、
         *  {@code tRa=60}、{@code zGate=1}、{@code confirmWindow=5}。 */
        public static Params defaults() {
            return new Params(0.5, 30.0, 20.0, 120.0, 8.0, 3, 5, 300.0, 300.0, 60.0, 60.0, 1.0, 5);
        }
    }

    /**
     * 一帧观测（由常驻层的状态估计与地理围栏给出）。
     *
     * @param tsMs              观测时刻，Unix 毫秒
     * @param s                 沿航道弧长估计，m（判 P3 邻域）
     * @param vAlongMs          沿航道速度估计，m/s（<b>带符号</b>，判据内部取绝对值）
     * @param cogDeg            对地航向，度（判 P1 偏航，相对锁定的 {@code courseRef}）
     * @param navPhase          {@link NavPhaseTracker} 给出的航行阶段；{@code null} 表示数据不足
     * @param branchAmbiguous   分汊是否歧义（由步骤 B 的分支判定给出）
     * @param inRestrictedArea  估计位置是否落入受限水域多边形（射线法结果）
     */
    public record Observation(long tsMs, double s, double vAlongMs, double cogDeg,
            NavPhaseTracker.Phase navPhase, boolean branchAmbiguous, boolean inRestrictedArea) {

        public Observation {
            if (!Double.isFinite(s) || !Double.isFinite(vAlongMs) || !Double.isFinite(cogDeg)) {
                throw new IllegalArgumentException("s / v_s / cog 必须为有限值");
            }
        }
    }

    /**
     * 归一化偏差样本（§2.6 第（2）项的口径）。
     *
     * @param zAlong {@code |Δs| / σ_along}
     * @param zCross {@code xteM / σ_cross}
     * @param zCog   {@code |ΔCOG| / sigmaCogDeg}
     * @param zRho   {@code ρ / rhoTh}（ρ 本身无量纲，按其阈值归一）
     */
    public record ZSample(double zAlong, double zCross, double zCog, double zRho) {

        /** 四路特征的最大值——粗标记就看它是否超限。 */
        public double maxAbs() {
            return Math.max(Math.max(zAlong, zCross), Math.max(zCog, zRho));
        }
    }

    /**
     * 前提监测的产出。
     *
     * @param verdict    事实层结论
     * @param reason     触发原因
     * @param coarseFlag 统计层粗标记（M-of-N），供功能 9 的「重新获取预测」门槛
     * @param zSeries    最近 {@code confirmWindow} 个归一化偏差样本（时间递增）
     */
    public record Assessment(Verdict verdict, Reason reason, boolean coarseFlag,
            List<ZSample> zSeries) {

        public Assessment {
            zSeries = List.copyOf(zSeries);
        }

        /** 是否需要下游重新获取预测（粗门槛）。 */
        public boolean needsReacquire() {
            return coarseFlag || verdict == Verdict.INVALID;
        }
    }

    private final Params params;

    /** 锁定的初始方向符号：+1 顺航道正向、−1 逆向（上行）。 */
    private final int dirRef0Sign;

    /** 分汊点弧长，m。 */
    private final List<Double> branchPointS;

    private long reversedSinceMs = Long.MIN_VALUE;
    private long deviatedSinceMs = Long.MIN_VALUE;
    private long branchAmbiguousSinceMs = Long.MIN_VALUE;
    private long restrictedSinceMs = Long.MIN_VALUE;

    /** P1 的 M-of-N 滑窗：每帧一个「偏航超阈」的布尔。 */
    private final Deque<Boolean> deviationWindow = new ArrayDeque<>();

    /** 统计层归一化偏差窗。 */
    private final Deque<ZSample> zWindow = new ArrayDeque<>();

    private boolean seen;

    /**
     * @param params        判据参数
     * @param dirRef0Sign   接单／进入本单时锁定的方向符号：{@code +1} 顺航道正向、{@code -1} 逆向
     * @param branchPointS  本航段上的分汊点弧长列表（无分汊传空列表）
     */
    public PremiseMonitor(Params params, int dirRef0Sign, List<Double> branchPointS) {
        if (params == null) {
            throw new IllegalArgumentException("参数不得为 null");
        }
        if (dirRef0Sign != 1 && dirRef0Sign != -1) {
            throw new IllegalArgumentException("方向符号只能是 +1 或 −1：" + dirRef0Sign);
        }
        this.params = params;
        this.dirRef0Sign = dirRef0Sign;
        this.branchPointS = branchPointS == null ? List.of() : List.copyOf(branchPointS);
    }

    /** 锁定的航向参考 {@code courseRefDeg}，度（由 {@code dirRef0} 决定，不随实时方向翻转）。 */
    private Double lockedCourseRefDeg;

    /**
     * 设置锁定的航向参考 {@code courseRef}（由航道走向按 {@code dirRef0} 取号得到）。
     *
     * <p>必须在首帧 {@link #observe} 之前调用；不设置则退化为用首帧 {@code cogDeg} 锁定。
     *
     * @param waterwayCourseDeg 该处航道走向（中心线固定正向），度
     * @return 本对象（链式）
     */
    public PremiseMonitor lockCourseRef(double waterwayCourseDeg) {
        this.lockedCourseRefDeg = ConstantVelocityPredictor.normalizeDeg(
                waterwayCourseDeg + (dirRef0Sign < 0 ? 180.0 : 0.0));
        return this;
    }

    /**
     * 推进一步事实层判据。
     *
     * @param observation 本帧观测
     * @return 本帧结论
     */
    public Assessment observe(Observation observation) {
        long tsMs = observation.tsMs();
        double absV = Math.abs(observation.vAlongMs());
        boolean headingUsable = absV > params.vTurnMinMs();
        if (lockedCourseRefDeg == null) {
            lockedCourseRefDeg = ConstantVelocityPredictor.normalizeDeg(observation.cogDeg());
        }

        // ── P0 方向翻转：与锁定的 dirRef0 比，且需持续 tDirSec；低速时冻结
        boolean flipped = headingUsable && signOf(observation.vAlongMs()) != dirRef0Sign;
        reversedSinceMs = advanceTimer(reversedSinceMs, flipped, tsMs);
        boolean reversed = elapsedSec(reversedSinceMs, tsMs) >= params.tDirSec();

        // ── P1 偏航（粗标记）：M-of-N ＋ 驻留 tPsiSec；低速时航向无意义，冻结
        boolean deviatedFrame = false;
        if (headingUsable) {
            double dCog = AlongTrackKalmanFilter.shortestAngleDiffDeg(observation.cogDeg(),
                    lockedCourseRefDeg);
            deviatedFrame = dCog > params.psiThrDeg();
        }
        deviationWindow.addLast(deviatedFrame);
        while (deviationWindow.size() > params.mOfN_N()) {
            deviationWindow.pollFirst();
        }
        int hits = 0;
        for (Boolean flag : deviationWindow) {
            if (Boolean.TRUE.equals(flag)) {
                hits++;
            }
        }
        boolean mOfNSatisfied = hits >= params.mOfN_M();
        deviatedSinceMs = advanceTimer(deviatedSinceMs, mOfNSatisfied, tsMs);
        boolean deviated = elapsedSec(deviatedSinceMs, tsMs) >= params.tPsiSec();

        // ── P3 分汊歧义：落在任一分汊点邻域内 且 歧义持续 tBpSec
        boolean inBranchNeighbourhood = inBranchNeighbourhood(observation.s());
        boolean branchCondition = inBranchNeighbourhood && observation.branchAmbiguous();
        branchAmbiguousSinceMs = advanceTimer(branchAmbiguousSinceMs, branchCondition, tsMs);
        boolean branchAmbiguous = elapsedSec(branchAmbiguousSinceMs, tsMs) >= params.tBpSec();

        // ── P4 受限水域：落入多边形并持续 tRaSec
        restrictedSinceMs = advanceTimer(restrictedSinceMs, observation.inRestrictedArea(), tsMs);
        boolean restricted = elapsedSec(restrictedSinceMs, tsMs) >= params.tRaSec();

        seen = true;

        // ── 数据有效性：无航行阶段即证据不足
        if (observation.navPhase() == null) {
            return assessment(Verdict.UNKNOWN, Reason.INSUFFICIENT_DATA);
        }
        // ── 优先级 P4 > P3 > P0 > P2 > P1
        if (restricted) {
            return assessment(Verdict.NOT_APPLICABLE, Reason.RESTRICTED_AREA);
        }
        if (branchAmbiguous) {
            return assessment(Verdict.INVALID, Reason.BRANCH_AMBIGUOUS);
        }
        if (reversed) {
            return assessment(Verdict.INVALID, Reason.REVERSED);
        }
        if (observation.navPhase() == NavPhaseTracker.Phase.STOPPED
                || observation.navPhase() == NavPhaseTracker.Phase.ANCHORED) {
            return assessment(Verdict.INVALID, Reason.STOPPED);
        }
        if (deviated) {
            return assessment(Verdict.SUSPECT, Reason.DEVIATED);
        }
        return assessment(Verdict.VALID, Reason.NONE);
    }

    /**
     * 计入一个归一化偏差样本（统计层）。在「实测 vs 预测」比对处调用。
     *
     * @param sample 归一化偏差样本
     */
    public void addZSample(ZSample sample) {
        if (sample == null) {
            return;
        }
        zWindow.addLast(sample);
        while (zWindow.size() > params.confirmWindow()) {
            zWindow.pollFirst();
        }
    }

    /**
     * 按 §2.6 口径构造归一化偏差样本。
     *
     * @param deltaS       实测与预测的纵向偏差 {@code s_est − s_pred}，m（取绝对值归一）
     * @param sigmaAlong   {@code σ_along}，m
     * @param crossTrackM  横向偏差（XTE），m
     * @param sigmaCross   {@code σ_cross}，m
     * @param deltaCogDeg  相对锁定 {@code courseRef} 的航向差，度
     * @param sigmaCogDeg  航向差归一化尺度，度
     * @param rho          速度缺口比
     * @param rhoTh        缺口比阈值
     * @return 归一化偏差样本
     */
    public static ZSample zSample(double deltaS, double sigmaAlong, double crossTrackM,
            double sigmaCross, double deltaCogDeg, double sigmaCogDeg, double rho, double rhoTh) {
        return new ZSample(
                sigmaAlong > 0.0 ? Math.abs(deltaS) / sigmaAlong : 0.0,
                sigmaCross > 0.0 ? Math.abs(crossTrackM) / sigmaCross : 0.0,
                sigmaCogDeg > 0.0 ? Math.abs(deltaCogDeg) / sigmaCogDeg : 0.0,
                rhoTh > 0.0 ? rho / rhoTh : 0.0);
    }

    /** 当前归一化偏差序列（时间递增，长度 ≤ {@code confirmWindow}）。 */
    public List<ZSample> zSeries() {
        return List.copyOf(zWindow);
    }

    /**
     * 统计层粗标记：最近 {@code mOfN_N} 个样本中，超 {@code zGate} 的个数 ≥ {@code mOfN_M}。
     */
    public boolean coarseFlag() {
        List<ZSample> all = new ArrayList<>(zWindow);
        int from = Math.max(0, all.size() - params.mOfN_N());
        int hits = 0;
        for (int i = from; i < all.size(); i++) {
            if (all.get(i).maxAbs() > params.zGate()) {
                hits++;
            }
        }
        return hits >= params.mOfN_M();
    }

    /** 是否已收到过观测（冷启动判据）。 */
    public boolean isInitialized() {
        return seen;
    }

    /** 锁定的方向符号 {@code dirRef0}。 */
    public int lockedDirectionSign() {
        return dirRef0Sign;
    }

    // ───────────── 内部 ─────────────

    private Assessment assessment(Verdict verdict, Reason reason) {
        return new Assessment(verdict, reason, coarseFlag(), zSeries());
    }

    /**
     * 计时器推进：条件成立则保留（首次成立时以本帧为起点），不成立则复位。
     *
     * <p>「持续成立才触发」全靠这层：单帧尖峰会在下一帧把计时清零，绝不会置位。
     *
     * @param sinceMs 原计时起点（{@link Long#MIN_VALUE} 表示未在计时）
     * @param holds   本帧条件是否成立
     * @param nowMs   本帧时刻
     * @return 新的计时起点
     */
    private static long advanceTimer(long sinceMs, boolean holds, long nowMs) {
        if (!holds) {
            return Long.MIN_VALUE;
        }
        return sinceMs == Long.MIN_VALUE ? nowMs : sinceMs;
    }

    private static double elapsedSec(long sinceMs, long nowMs) {
        if (sinceMs == Long.MIN_VALUE) {
            return 0.0;
        }
        return (nowMs - sinceMs) / 1000.0;
    }

    private boolean inBranchNeighbourhood(double s) {
        for (double branchPoint : branchPointS) {
            if (s >= branchPoint - params.bpInM() && s <= branchPoint + params.bpOutM()) {
                return true;
            }
        }
        return false;
    }

    private static int signOf(double value) {
        return value > 0.0 ? 1 : (value < 0.0 ? -1 : 0);
    }
}
