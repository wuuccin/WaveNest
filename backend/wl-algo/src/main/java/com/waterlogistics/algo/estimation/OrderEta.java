package com.waterlogistics.algo.estimation;

/**
 * 预计送达时刻 {@code etaTs}——三个阶段三套口径，集中在本类，避免各处各算一套。
 *
 * <h3>三套口径（《11-05 序号 2 解锁材料 · ETA 输出契约》§二 第 2 条）</h3>
 * <table border="1">
 *   <caption>阶段与算法</caption>
 *   <tr><th>阶段</th><th>算法</th><th>方法</th></tr>
 *   <tr><td>未派单</td><td>时长下界换算：{@code now + etaMin}</td>
 *       <td>{@link #beforeDispatch}</td></tr>
 *   <tr><td>已派单</td><td>{@code meet_time + 交接时长}</td>
 *       <td>{@link #dispatched}</td></tr>
 *   <tr><td>飞中</td><td>{@code now + 剩余距离 / vMaxCruise + 交接时长}</td>
 *       <td>{@link #inFlightOr}</td></tr>
 * </table>
 *
 * <p><b>为什么未派单阶段必然偏早</b>：{@code etaMin} 是「2 × 最近距离 / 上限速度」，
 * 它不含选机排队、不含实际航迹绕行、不含作业前的准备时间；它是下界，不是估计。
 * 对外展示必须允许「偏早」，但<b>不得</b>据此对外承诺最迟送达——那是 {@code deadline} 的事。
 *
 * <p><b>飞中为什么用机队上限速度而不是该机实际速度</b>：契约已冻结该口径
 * （{@code vMaxCruiseMs}，{@code sys_param} 值 20 m/s）。用实际速度确实更贴近真实到达，
 * 但会让「同一订单的 ETA 随某台机的健康度变化」——展示口径与受理判据同源，
 * 便于对账；偏差由后续阶段推进不断重算吸收。
 *
 * <p><b>交接时长取哪一个</b>：用 {@code sys_param.tHandoverMinSec}（默认 120 s），
 * 与飞行阶段推进的「交接完成」时刻同源。受理判定里的
 * {@code wavenest.m2.admission.t-handover-min-sec}（60 s）是<b>最短</b>交接耗时，
 * 专用于下界 {@code etaMin}；两者语义不同，不互换。
 */
public final class OrderEta {

    private OrderEta() {
    }

    /**
     * 未派单阶段：由时长下界换算送达时刻。
     *
     * @param nowTsMs          当前时刻，Unix 毫秒
     * @param etaLowerBoundSec 时长下界 {@code etaMin}，秒（非负有限数）
     * @return 送达时刻下界，Unix 毫秒
     * @throws IllegalArgumentException 下界为负或非有限数时抛出
     */
    public static long beforeDispatch(long nowTsMs, double etaLowerBoundSec) {
        return EtaLowerBound.etaTsMs(nowTsMs, etaLowerBoundSec);
    }

    /**
     * 已派单（含末段交接中、返航中）：送达时刻等于「会面时刻 ＋ 交接时长」。
     *
     * <p>该值在本次派发周期内是<b>确定值</b>，不再随时间漂移；重复调用返回同一结果。
     *
     * @param meetTsMs    会面时刻，Unix 毫秒
     * @param handoverSec 交接时长，秒（非负）
     * @return 送达时刻，Unix 毫秒
     * @throws IllegalArgumentException 会面时刻非正或交接时长为负时抛出
     */
    public static long dispatched(long meetTsMs, int handoverSec) {
        if (meetTsMs <= 0L) {
            throw new IllegalArgumentException("会面时刻须为 Unix 毫秒值，实际 " + meetTsMs);
        }
        if (handoverSec < 0) {
            throw new IllegalArgumentException("交接时长不得为负，实际 " + handoverSec);
        }
        return meetTsMs + handoverSec * 1000L;
    }

    /**
     * 飞中阶段：剩余距离 ÷ 巡航速度 ＋ 交接时长。
     *
     * <p>剩余距离不可用（位置缺失、坐标为非法值）时返回 {@code fallbackTsMs}，
     * <b>不抛异常</b>——遥测缺失是常态，缺位置只该让 ETA 退化成"已派单值"，
     * 不该让飞行阶段推进失败。
     *
     * @param nowTsMs       当前时刻，Unix 毫秒
     * @param remainingM    机当前位置到会面点的剩余距离，米；{@code NaN}／负值视为不可用
     * @param vMaxCruiseMs  最大巡航速度，m/s（须为正）
     * @param handoverSec   交接时长，秒（非负）
     * @param fallbackTsMs  剩余距离不可用时的回落值，Unix 毫秒
     * @return 预计送达时刻，Unix 毫秒
     * @throws IllegalArgumentException 速度非正或交接时长为负时抛出
     */
    public static long inFlightOr(long nowTsMs, double remainingM, double vMaxCruiseMs,
            int handoverSec, long fallbackTsMs) {
        if (!Double.isFinite(vMaxCruiseMs) || vMaxCruiseMs <= 0.0) {
            throw new IllegalArgumentException("最大巡航速度须为正的有限值：" + vMaxCruiseMs);
        }
        if (handoverSec < 0) {
            throw new IllegalArgumentException("交接时长不得为负，实际 " + handoverSec);
        }
        if (!Double.isFinite(remainingM) || remainingM < 0.0) {
            return fallbackTsMs;
        }
        long flightMs = Math.round(remainingM / vMaxCruiseMs * 1000.0);
        return nowTsMs + flightMs + handoverSec * 1000L;
    }

    /**
     * 单调收敛：取候选值与已发布值的较大者。
     *
     * <p><b>为什么需要</b>：{@code eta_ts} 一旦对外发布，回退会让前端「预计送达」往回跳，
     * 看起来像系统算错；且《11-05 任务清单》§八 硬判据 4 要求「单调收敛」。
     * 飞中阶段剩余距离的抖动（GPS 漂移、航向修正）会带来秒级回退，由本方法吸收。
     *
     * <p>实际送达时刻写库时<b>不走本方法</b>——那是本周期唯一确定的终值，必须如实落库。
     *
     * @param candidateTsMs 本次算出的时刻，Unix 毫秒
     * @param previousTsMs  已发布值，Unix 毫秒；{@code null} 表示尚未发布
     * @return 应写入的时刻，Unix 毫秒
     */
    public static long notEarlierThan(long candidateTsMs, Long previousTsMs) {
        if (previousTsMs == null) {
            return candidateTsMs;
        }
        return Math.max(candidateTsMs, previousTsMs);
    }
}
