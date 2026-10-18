package com.waterlogistics.monitor.rendezvous;

/**
 * 会面偏差样本（G 组功能 19，86 §1.5 13 字段）。
 *
 * <p>16 号简化版：只发 MQ ＋ 内存/日志留痕，不落库。
 * 数据源：M2 的 {@code B_HandoverConfirmation}（计划/实际会面点与时刻）。
 *
 * @param sampleId        偏差样本唯一标识（UUID）
 * @param droneId         无人机实体标识
 * @param orderId         订单实体标识
 * @param plannedRendezvous 计划会面点（lon, lat），来自 M2 任务方案
 * @param actualRendezvous  实际会面点（lon, lat），来自 M3 末段交接确认
 * @param plannedTime     计划会面时刻（Unix 毫秒）
 * @param actualTime      实际交接时刻（Unix 毫秒）
 * @param dSpaceM         空间偏差（米），Haversine(实际, 计划)
 * @param dTimeS          时间偏差（秒），actualTime − plannedTime
 * @param model           机型
 * @param homeNestId      机巢标识（归属机巢口径，与 task.plan 同字段）
 * @param windCondition   风况段（可选）
 * @param shipSpeedRange  船速段（可选）
 */
public record RendezvousDeviation(
        String sampleId,
        String droneId,
        String orderId,
        GeoPoint plannedRendezvous,
        GeoPoint actualRendezvous,
        long plannedTime,
        long actualTime,
        double dSpaceM,
        double dTimeS,
        String model,
        String homeNestId,
        String windCondition,
        String shipSpeedRange
) {

    /** 经纬度坐标点 */
    public record GeoPoint(double lon, double lat) {}

    /**
     * 从 B_HandoverConfirmation 组装偏差样本。
     *
     * @param confirmation 交接完成确认（含计划/实际会面点与时刻；其 taskId 是任务标识，不等于 orderId）
     * @param orderId      订单实体标识（86 §附 G-01：order_id 来自任务方案 orderId，由调用方传入，
     *                     不得用 confirmation.taskId() 顶替）
     * @param model        机型
     * @param homeNestId   机巢标识（归属机巢口径）
     * @param windCondition 风况段（可选）
     * @param shipSpeedRange 船速段（可选）
     * @return 组装好的偏差样本
     */
    public static RendezvousDeviation from(
            com.waterlogistics.algo.meeting.model.B_HandoverConfirmation confirmation,
            String orderId,
            String model,
            String homeNestId,
            String windCondition,
            String shipSpeedRange) {

        GeoPoint planned = new GeoPoint(confirmation.plannedMeetLon(), confirmation.plannedMeetLat());
        GeoPoint actual = confirmation.actualMeetLon() != null && confirmation.actualMeetLat() != null
                ? new GeoPoint(confirmation.actualMeetLon(), confirmation.actualMeetLat())
                : null;

        double dSpaceM = actual != null
                ? haversineMeters(planned.lat(), planned.lon(), actual.lat(), actual.lon())
                : 0.0;
        double dTimeS = confirmation.handoverDoneT() != null
                ? (confirmation.handoverDoneT() - confirmation.plannedMeetT()) / 1000.0
                : 0.0;

        return new RendezvousDeviation(
                java.util.UUID.randomUUID().toString(),
                confirmation.droneId(),
                orderId,
                planned,
                actual,
                confirmation.plannedMeetT(),
                confirmation.handoverDoneT() != null ? confirmation.handoverDoneT() : 0L,
                dSpaceM,
                dTimeS,
                model,
                homeNestId,
                windCondition,
                shipSpeedRange
        );
    }

    /** Haversine 公式：球面两点距离（米） */
    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0; // 地球半径（米）
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
