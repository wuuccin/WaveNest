package com.waterlogistics.dispatch.rendezvous;

/**
 * 机巢坐标查询端口：会面求解按货物所在巢取起点时使用。
 *
 * <p>只读、无副作用。查无、或台账缺少经纬度时返回 {@code null}，由调用方按
 * 「货物所在巢不可用」拒绝求解，不得退回无人机当前所在巢。
 */
@FunctionalInterface
public interface NestLookup {

    /**
     * 按机巢标识查坐标。
     *
     * @param nestId 机巢标识，调用方保证非空、非空白
     * @return 机巢坐标；查无或坐标缺失时返回 {@code null}
     */
    NestPosition find(String nestId);

    /**
     * 机巢坐标。
     *
     * @param nestId 机巢标识
     * @param lon    经度
     * @param lat    纬度
     */
    record NestPosition(String nestId, double lon, double lat) {

        public NestPosition {
            if (nestId == null || nestId.isBlank()) {
                throw new IllegalArgumentException("nestId 不能为空");
            }
            if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0
                    || !Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
                throw new IllegalArgumentException(
                        "机巢坐标须为有限数且经纬度在合法范围内，实际 " + lon + "," + lat);
            }
        }
    }
}
