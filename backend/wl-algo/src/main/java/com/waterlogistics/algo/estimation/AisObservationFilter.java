package com.waterlogistics.algo.estimation;

import com.waterlogistics.contract.mq.AisMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 步骤 A：AIS 清洗与去重（{@code 80} §3.2.3）。
 *
 * <h3>三件事</h3>
 * <ol>
 *   <li><b>去重</b>：滑动窗口 {@code dupWindowSec}，键 {@code (mmsi, floor(ts/1000))}，
 *       窗口内重复即丢弃（逐船有界 LRU；跨实例兜底由 Redis 承担，本类只做单机部分）。</li>
 *   <li><b>跳点剔除</b>（对相邻有效点 {@code k−1 → k}）：
 *       <pre>
 *   vImplied  = haversine(p_{k-1}, p_k) / Δt
 *   suspicious = (vImplied > vGateMs)                                    // ① 位移过快
 *             || (|ΔCOG| > dcogGateDeg && vImplied &lt; vTurnMinMs)         // ② 低速大转向
 *             || (|sog_k − vImplied| > epsSogMs)                         // ③ 速度不自洽
 *       </pre>
 *       可疑点进 {@code pending} <b>而不立即丢弃</b>——因为 {@code 21} §5.1 把
 *       「减速、加速、避让、换道」列为<b>正常的量变</b>，单点门限会把真实的急减速误剔。</li>
 *   <li><b>{@code pending} 的两条出口</b>：
 *       <ul>
 *         <li>长度 ≥ {@code confirmCount} 且<b>内部互相自洽</b>（相邻点之间不再超 {@code vGateMs}）
 *             → 判为<b>真实机动</b>，整段接受；</li>
 *         <li>长度 &gt; {@code pendingMax} 仍不自洽 → 整段判为<b>野值</b>丢弃。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>丢包外推与数据质量</h3>
 * <p>若相邻有效点间隔 {@code Δt > gapFactor × ΔtNominalSec}，进入 gap：本类<b>不</b>做外推
 * （外推属 §3.2.5 的预测步），只标 {@link Quality#EXTRAPOLATED} 并累计 gap 时长；
 * 累计超过 {@code gapMaxSec} 置 {@link Quality#INSUFFICIENT}——功能 1 的「数据有效性」判据
 * 据此暂缓（{@code DEFER(DATA_STALE)}）。
 *
 * <h3>参数与键名纪律</h3>
 * <p>所有门限由 {@link Params} 注入，<b>本类不读配置、不硬编码业务阈值</b>；
 * {@link Params#defaults()} 只是演示缺省值——{@code sys_param} 尚无这些键，
 * 按纪律<b>不臆造键名</b>，待《37》§5.14.1 登记后由模块层改为读表。
 */
public final class AisObservationFilter {

    private AisObservationFilter() {
    }

    /** 地球平均半径（haversine 用），m。 */
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /** 数据质量。 */
    public enum Quality {
        /** 采样连续，可直接用于估计 */
        OK,
        /** 存在丢包填充段（本类不外推，仅标记） */
        EXTRAPOLATED,
        /** 丢包累计过长，纵向估计不可信 → 功能 1 应暂缓 */
        INSUFFICIENT
    }

    /**
     * 清洗参数（全部由调用方注入）。
     *
     * @param dupWindowSec   去重滑动窗口，s
     * @param vGateMs        位移过快门限，m/s
     * @param dcogGateDeg    大转向门限，度
     * @param vTurnMinMs     低速阈值，m/s（低速大转向判可疑；高速转向属正常机动）
     * @param epsSogMs       速度自洽容差，m/s
     * @param confirmCount   {@code pending} 达到该长度即做「真实机动 vs 野值」裁决
     * @param pendingMax     {@code pending} 超过该长度仍不自洽 → 判野值
     * @param gapFactor      丢包判定倍数（{@code Δt > gapFactor × 标称间隔} 视为 gap）
     * @param nominalIntervalSec 标称采样间隔，s
     * @param gapMaxSec      丢包累计上限，s；超过则 {@link Quality#INSUFFICIENT}
     */
    public record Params(
            long dupWindowSec,
            double vGateMs,
            double dcogGateDeg,
            double vTurnMinMs,
            double epsSogMs,
            int confirmCount,
            int pendingMax,
            double gapFactor,
            double nominalIntervalSec,
            long gapMaxSec) {

        public Params {
            if (dupWindowSec < 0 || confirmCount < 1 || pendingMax < confirmCount) {
                throw new IllegalArgumentException(
                        "参数非法：dupWindowSec≥0、confirmCount≥1、pendingMax≥confirmCount");
            }
            if (!(vGateMs > 0) || !(gapFactor > 0) || !(nominalIntervalSec > 0)) {
                throw new IllegalArgumentException("vGateMs/gapFactor/nominalIntervalSec 必须为正");
            }
        }

        /**
         * 演示缺省值（{@code sys_param} 尚无对应键，按纪律不臆造键名）。
         *
         * <p>取值思路：内河货船通常 &lt; 8 节（≈4.1 m/s），取 25 m/s 作位移门限可容忍
         * 短时定位跳变而不误剔真实加速；20° 是「大转向」的演示阈值；5 s 标称间隔对应
         * 演示仿真节拍量级。
         */
        public static Params defaults() {
            return new Params(30L, 25.0, 20.0, 1.5, 5.0, 3, 6, 3.0, 5.0, 120L);
        }
    }

    /**
     * 清洗结果。
     *
     * @param accepted   通过清洗的观测，按时间升序
     * @param dropped    被丢弃的观测（去重或判野值），供排查
     * @param quality    数据质量
     * @param gapAccumMs 累计丢包时长，ms
     */
    public record Result(List<AisMessage> accepted, List<AisMessage> dropped,
            Quality quality, long gapAccumMs) {
    }

    /**
     * 清洗一批观测（单船）。
     *
     * @param raw    原始观测（可乱序、可重复、可含野值）
     * @param params 参数
     * @return 清洗结果
     * @throws IllegalArgumentException 入参为 null 时抛出
     */
    public static Result filter(List<AisMessage> raw, Params params) {
        if (raw == null) {
            throw new IllegalArgumentException("观测列表不得为 null");
        }
        if (params == null) {
            throw new IllegalArgumentException("清洗参数不得为 null");
        }
        if (raw.isEmpty()) {
            return new Result(List.of(), List.of(), Quality.OK, 0L);
        }

        // ① 按时间排序（乱序报文先排；同 ts 保持原序，稳定排序）
        List<AisMessage> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingLong(AisMessage::ts));

        List<AisMessage> accepted = new ArrayList<>();
        List<AisMessage> dropped = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();          // 去重窗口（有界 LRU 的简化）
        List<AisMessage> pending = new ArrayList<>();
        long gapAccumMs = 0L;

        for (AisMessage message : sorted) {
            // ② 去重：键 (mmsi, floor(ts/1000))
            String key = message.mmsi() + "@" + Math.floorDiv(message.ts(), 1000L);
            if (seen.containsKey(key)) {
                dropped.add(message);
                continue;
            }
            seen.put(key, Boolean.TRUE);
            trimSeen(seen, message.ts(), params.dupWindowSec());

            if (accepted.isEmpty()) {
                accepted.add(message);
                continue;
            }

            AisMessage previous = accepted.get(accepted.size() - 1);
            long deltaTMs = message.ts() - previous.ts();
            double deltaTSec = deltaTMs / 1000.0;

            // ③ 丢包标记（不外推，只记录质量）
            if (deltaTSec > params.gapFactor() * params.nominalIntervalSec()) {
                gapAccumMs += deltaTMs;
            }

            if (deltaTSec <= 0.0) {
                dropped.add(message);           // 时间回退（重复/乱序残留）
                continue;
            }

            double distanceM = haversineM(previous.lon(), previous.lat(),
                    message.lon(), message.lat());
            double vImplied = distanceM / deltaTSec;
            double sogMs = ConstantVelocityPredictor.knotsToMs(message.sog());
            double dcogDeg = shortestAngleDiffDeg(message.cog(), previous.cog());

            boolean suspicious =
                    vImplied > params.vGateMs()                                        // ①
                            || (dcogDeg > params.dcogGateDeg()
                                    && vImplied < params.vTurnMinMs())                     // ②
                            || Math.abs(sogMs - vImplied) > params.epsSogMs();              // ③

            if (!suspicious) {
                // 本次与上一个已接受点自洽：若 pending 里还压着可疑点，先裁决
                if (!pending.isEmpty()) {
                    resolvePending(pending, accepted, dropped, params);
                    pending.clear();
                }
                accepted.add(message);
                continue;
            }

            pending.add(message);

            if (pending.size() >= params.confirmCount()) {
                if (pendingIsConsistent(pending, params)) {
                    // 真实机动：整段接受
                    accepted.addAll(pending);
                    pending.clear();
                } else if (pending.size() > params.pendingMax()) {
                    // 野值：整段丢弃
                    dropped.addAll(pending);
                    pending.clear();
                }
            }
        }

        // 收尾：还在 pending 里的，按同一裁决规则处理
        if (!pending.isEmpty()) {
            resolvePending(pending, accepted, dropped, params);
        }

        Quality quality = gapAccumMs > params.gapMaxSec() * 1000L
                ? Quality.INSUFFICIENT
                : (gapAccumMs > 0L ? Quality.EXTRAPOLATED : Quality.OK);

        return new Result(List.copyOf(accepted), List.copyOf(dropped), quality, gapAccumMs);
    }

    // ───────────── 内部工具 ─────────────

    /** 收尾裁决：pending 自洽则接受，否则丢弃。 */
    private static void resolvePending(List<AisMessage> pending, List<AisMessage> accepted,
            List<AisMessage> dropped, Params params) {
        if (pendingIsConsistent(pending, params)) {
            accepted.addAll(pending);
        } else {
            dropped.addAll(pending);
        }
    }

    /** pending 内部是否互相自洽：相邻点之间位移速度不再超过门限（即机动本身是连续的）。 */
    private static boolean pendingIsConsistent(List<AisMessage> pending, Params params) {
        if (pending.size() < 2) {
            return pending.size() >= params.confirmCount();
        }
        for (int i = 1; i < pending.size(); i++) {
            AisMessage previous = pending.get(i - 1);
            AisMessage current = pending.get(i);
            double deltaTSec = (current.ts() - previous.ts()) / 1000.0;
            if (deltaTSec <= 0.0) {
                return false;
            }
            double vImplied = haversineM(previous.lon(), previous.lat(),
                    current.lon(), current.lat()) / deltaTSec;
            if (vImplied > params.vGateMs()) {
                return false;
            }
        }
        return true;
    }

    /** 去重窗口收缩：移除早于 {@code nowTs − window} 的键。 */
    private static void trimSeen(Map<String, Boolean> seen, long nowTs, long windowSec) {
        long cutoff = nowTs - windowSec * 1000L;
        seen.entrySet().removeIf(entry -> {
            int at = entry.getKey().indexOf('@');
            if (at < 0) {
                return true;
            }
            long tsSec = Long.parseLong(entry.getKey().substring(at + 1));
            return tsSec * 1000L < cutoff;
        });
    }

    /** 两点大圆距离（haversine），m。 */
    static double haversineM(double lon1, double lat1, double lon2, double lat2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = phi2 - phi1;
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                        * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** 两个航向之间的最短夹角，度（{@code [0,180]}）。 */
    static double shortestAngleDiffDeg(double cogA, double cogB) {
        double normalizedA = ConstantVelocityPredictor.normalizeDeg(cogA);
        double normalizedB = ConstantVelocityPredictor.normalizeDeg(cogB);
        double raw = Math.abs(normalizedA - normalizedB) % 360.0;
        return raw > 180.0 ? 360.0 - raw : raw;
    }
}
