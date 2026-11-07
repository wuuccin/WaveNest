package com.waterlogistics.contract.mq;

/**
 * 落巢货物回收确认（主题 {@link TopicName#DATA_UAV_RECYCLE}）。
 *
 * <p>本消息回答的是「原机已落巢、机上货物已被系统回收」这一物理事实。它独立于
 * 订单重入队：回收确认只把货物从「已装机」写成「已回收」，重派由调用方在确认之后
 * 按需发起，二者不得合并。
 *
 * <p><b>去重键</b>为 {@code orderId + goodsId + uavId + nestId}：同一票货在同一落点
 * 的重复确认不得二次写货物行与 {@code task_log}。
 *
 * <p>取值与错误码见 {@code 1106-remediation-contract.md} §2、§4。
 *
 * @param orderId 订单编号
 * @param goodsId 货物标识
 * @param uavId   原执行机编号
 * @param nestId  落巢标识
 * @param ts      确认时刻，Unix 毫秒
 */
public record GoodsRecycleConfirmation(
        String orderId,
        String goodsId,
        String uavId,
        String nestId,
        long ts) {

    public GoodsRecycleConfirmation {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 不能为空：回收确认无法归属到订单");
        }
        if (goodsId == null || goodsId.isBlank()) {
            throw new IllegalArgumentException("goodsId 不能为空：回收确认无法归属到货物");
        }
        if (uavId == null || uavId.isBlank()) {
            throw new IllegalArgumentException("uavId 不能为空：回收确认无法归属到执行机");
        }
        if (nestId == null || nestId.isBlank()) {
            throw new IllegalArgumentException("nestId 不能为空：野外迫降不得确认回收");
        }
    }

    /** 幂等键：同一票货在同一落点只处理一次。 */
    public String idempotencyKey() {
        return orderId + "|" + goodsId + "|" + uavId + "|" + nestId;
    }
}
