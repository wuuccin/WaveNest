package com.waterlogistics.contract.ws;

import java.util.List;

/**
 * 会面点帧 {@code rendezvous}——依据《22-接口契约》v1.2 §4。
 *
 * <p>前端表现：十字准星，锁定后加边框高亮。6a 纠偏重算会面点后随新帧下发。
 *
 * <p><b>T1-1 扩字段</b>（10-27 契约与地基）：新增候选数／逐条淘汰原因／预计到达时刻三项，均为可空——
 * 候选数与淘汰原因取候选链已产出的结果（E3 {@code selectFeasibleWithReasons} 透出，
 * 由编排器侧（T1-2）填值，队列侧构造点不参与发帧），预计到达时刻为 {@code meet_time} 的 Unix 毫秒。
 *
 * @param orderId         订单号
 * @param meetLon         会面点经度
 * @param meetLat         会面点纬度
 * @param locked          是否已锁定（定案后不可被常规纠偏改写）
 * @param candidateCount  参与本次会面点决策的候选机数；候选链未产出时为 {@code null}
 * @param rejectionReasons 逐条淘汰原因，与 {@code FeasibilityJudge} 判定结果的
 *                         {@code List<String> reasons} 同构；无记录时为 {@code null}
 * @param etaTs           预计到达（会面）时刻，Unix 毫秒；未定会面时为 {@code null}
 * @param chosenNestId    中选机巢；未选出（无可用候选）时为 {@code null}
 * @param chosenDroneId   中选无人机；未选出时为 {@code null}
 * @param candidates      本轮<b>全部</b>候选（含中选者，中选者 {@code selected=true}）；
 *                        候选链未产出时为 {@code null}
 *
 * <p><b>T1-1 之后的第二次扩字段（10-29 候选可观测性第二步）</b>：在中选机巢／中选无人机／逐条候选三项。
 * 前三项与 {@code rejectionReasons} <b>并存不替换</b>——{@code rejectionReasons} 是<b>只有落选项</b>
 * 的原因文案（彭紫情第一步已上线，前端正在消费，删了会断）；{@code candidates} 是<b>全量结构化</b>候选，
 * 供对比卡按距离排序、中选高亮、落选置灰。两者粒度不同，不是一回事。
 */
public record RendezvousFrame(

        String orderId,

        double meetLon,

        double meetLat,

        boolean locked,

        Integer candidateCount,

        List<String> rejectionReasons,

        Long etaTs,

        String chosenNestId,

        String chosenDroneId,

        List<CandidateView> candidates) {

    /**
     * 兼容构造器：保留 T1-1 之前的四参签名，新字段留空。
     *
     * <p>会面帧只由编排器一处发（编排器侧经帧投影器产出全参帧），主代码已全部迁到全参构造器；
     * 队列侧构造点不参与发帧，本构造器仅供旧调用点与测试过渡。
     */
    public RendezvousFrame(String orderId, double meetLon, double meetLat,
                           boolean locked) {
        this(orderId, meetLon, meetLat, locked, null, null, null, null, null, null);
    }

    /**
     * 兼容构造器：保留 T1-1 的七参签名（候选数／淘汰原因／预计到达时刻），候选对比三项留空。
     */
    public RendezvousFrame(String orderId, double meetLon, double meetLat,
                           boolean locked, Integer candidateCount,
                           List<String> rejectionReasons, Long etaTs) {
        this(orderId, meetLon, meetLat, locked, candidateCount, rejectionReasons,
                etaTs, null, null, null);
    }
}
