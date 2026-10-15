package com.waterlogistics.contract.rest;

/**
 * 经纬度坐标点，坐标系同项目约定（与 {@code emergency_landing_point} 表一致）。
 *
 * <p>用于迫降位置等字段；缺失时应表现为 null，前端显示"迫降位置待更新"，
 * <b>不得用最后已知机位冒充</b>（《22》§3.1）。
 *
 * @param lon 经度
 * @param lat 纬度
 */
public record GeoPoint(double lon, double lat) {
}
