package com.waterlogistics.contract.ws;

import java.util.List;

/**
 * 会面点帧 {@code rendezvous}——依据《22-接口契约》v1.2 §4。
 *
 * <p>前端表现：十字准星，锁定后加边框高亮。6a 纠偏重算会面点后随新帧下发。
 *
 * <p><b>T1-1 扩字段</b>（10-27 契约与地基）：新增候选数／逐条淘汰原因／预计到达时刻三项，均为可空——
 * 候选数与淘汰原因取候选链已产出的结果（E3 {@code selectFeasibleWithReasons} 透出，
 * 见 T1-2 编排器侧与 T1-6 队列侧填值），预计到达时刻为 {@code meet_time} 的 Unix 毫秒。
 *
 * @param orderId         订单号
 * @param meetLon         会面点经度
 * @param meetLat         会面点纬度
 * @param locked          是否已锁定（定案后不可被常规纠偏改写）
 * @param candidateCount  参与本次会面点决策的候选机数；候选链未产出时为 {@code null}
 * @param rejectionReasons 逐条淘汰原因，与 {@code FeasibilityJudge} 判定结果的
 *                         {@code List<String> reasons} 同构；无记录时为 {@code null}
 * @param etaTs           预计到达（会面）时刻，Unix 毫秒；未定会面时为 {@code null}
 */
public record RendezvousFrame(

        String orderId,

        double meetLon,

        double meetLat,

        boolean locked,

        Integer candidateCount,

        List<String> rejectionReasons,

        Long etaTs) {

    /**
     * 兼容构造器：保留 T1-1 之前的四参签名，新字段留空。
     *
     * <p>构造点分散在 wl-m2 编排器与队列侧，序号 2 填值前逐处迁移，互不阻塞。
     */
    public RendezvousFrame(String orderId, double meetLon, double meetLat,
                           boolean locked) {
        this(orderId, meetLon, meetLat, locked, null, null, null);
    }
}
