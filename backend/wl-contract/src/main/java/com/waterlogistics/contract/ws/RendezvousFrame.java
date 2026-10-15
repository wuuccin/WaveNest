package com.waterlogistics.contract.ws;

/**
 * 会面点帧 {@code rendezvous}——依据《22-接口契约》v1.2 §4。
 *
 * <p>前端表现：十字准星，锁定后加边框高亮。6a 纠偏重算会面点后随新帧下发。
 *
 * @param meetLon 会面点经度
 * @param meetLat 会面点纬度
 * @param locked  是否已锁定（定案后不可被常规纠偏改写）
 */
public record RendezvousFrame(

        String orderId,

        double meetLon,

        double meetLat,

        boolean locked) {
}
