#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_replay.py —— 生成一套六类帧 replay（testdata/mock/replay/）。

数据源 run：testdata/mock/scenarios/normal_straight.yaml（seed 1，live，s = 0 全程）。
本脚本按契约 §2 限速积分重算无噪真值（不读 truth/，不碰罗才辉的目录），
预测帧按「仿真内闭环」口径直接取真值前伸（本批次四项指标同为闭环自洽口径），
确定性输出、无随机数，复跑逐字节一致。

产物：replay-normal_straight-s1.json，形如 {"frames":[{"type","data"},...]}，
与前端 api.replay(droneId) 的返回结构同构（App.vue loadGuidedHistory 消费
history.frames，终态判定认 order_state 且 orderStatus ∈ {FINISHED, 已完成}）。

帧契约依据（wl-contract）：
  WsEnvelope {"type","data"}，无 payload、顶层无 ts（契约 §8）
  ship_traj    ShipTrajFrame(mmsi, version, t0, courseDeg, validitySec, premise,
               points[{t,lon,lat,sigmaAlong,sigmaCross}])；无预测应不发帧
  drone_state  DroneStateFrame(uavId, lon, lat, battery, phase, ts, heading, activeOrderId)
  rendezvous   RendezvousFrame(orderId, meetLon, meetLat, locked, candidateCount,
               rejectionReasons, etaTs, chosenNestId, chosenDroneId, candidates)
  alarm        AlarmFrame(alertType, alertLevel, target, message, lon, lat, orderId, eventId)
               alertType/alertLevel 用枚举名（Jackson 默认），前端 displayEnum 转中文
  order_state  {orderId, orderStatus, stateVersion, eventTs}，中文状态词（契约 §8）
  plan_track   PlanTrackFrame(orderId, routeId, comboId, opType, segments[{kind,points}])，
               分段词 去程／末段交接／返程（PlanTrackFrame.SEGMENT_*）

用法：python3 build_replay.py [--out replay-normal_straight-s1.json]
"""
import argparse
import json
import math
import os

# ---------------------------------------------------------------- 常量（全部取自契约与基线文件，出处见行尾）
T0_MS = 1794268800000                    # normal_straight.yaml t0_ms（2026-11-10T00:00:00Z）
MMSI = "123456789"                       # 契约 §3：Live E2E 用 123456789
PRED_VERSION = 11090001                  # normal_straight.yaml pred_version
ORDER_ID = "ORD-1109-0001"
ROUTE_ID = "RT-1109-0001"
COMBO_ID = "C-UAV02-NEST01"
UAV_ID = "UAV-02"                        # 003_seed_drone_nest.sql：NEST-01 M400，巡航 15 m/s
NEST_01 = (115.8480000, 28.6420000)      # 003：南端（朝阳端）
NEST_02 = (115.8670000, 28.6980000)      # 003：北端（八一端）
DRONE_SPEED_MS = 15.0                    # 003：UAV-02 cruise_speed_ms
BATTERY_START = 98.0                     # 003：UAV-02 初始电量
BATTERY_DRAIN_PER_S = 0.15               # 飞行耗电（%/s，演示标定值）
BATTERY_HOVER_PER_S = 0.03               # 悬停耗电（%/s，演示标定值）
MEET_S = 3000.0                          # 会面点沿程（m），南昌大桥上游侧
MEET_T = 666.667                         # 船到会面点时刻（s）＝ 444.444 + 1000/4.5
DEPART_T = 400.0                         # 派发起飞时刻（s）
HANDOVER_END_T = 760.0                   # 交接完成时刻（s）
FINISHED_T = 1000.0                      # 订单完成时刻（s）
LINE_TOTAL_M = 6003.3510                 # 契约 §2：中心线 v3 全长
SIGMA_ALONG_M = 15.0                     # sigmaEM（002 初始建议值，design_assumption）
SIGMA_CROSS_M = 15.0
VALIDITY_SEC = 1800                      # horizonSec（契约 §2）
SEG_LIMITS = [(0.0, 2000.0, 4.5), (2000.0, 4000.0, 4.5), (4000.0, 6003.4, 2.5)]
# 中心线 v3 十三点（016_centerline_v3.sql，measured：影像河心重取）
CHART = [
    (0.0000, 115.844393, 28.645998),
    (511.3912, 115.847602, 28.649634),
    (1015.2520, 115.850273, 28.653512),
    (1539.1750, 115.854263, 28.656665),
    (2019.6943, 115.858212, 28.659247),
    (2524.0104, 115.857937, 28.663776),
    (3052.8931, 115.858769, 28.668476),
    (3515.4177, 115.860851, 28.672213),
    (4059.0016, 115.862310, 28.676931),
    (4574.0294, 115.864577, 28.681114),
    (5044.8372, 115.866785, 28.684879),
    (5571.6111, 115.869281, 28.689080),
    (6003.3510, 115.872180, 28.692014),
]

M_PER_DEG_LAT = math.pi * 6371000.0 / 180.0   # 111194.92664455873，GeoApprox 口径


def s_at(t_sec):
    """限速积分：s(t)，分段 4.5／4.5／2.5，末端封顶。"""
    t_2k = 2000.0 / 4.5                       # 444.444 s 到 s=2000
    t_4k = t_2k + 2000.0 / 4.5                # 888.889 s 到 s=4000
    if t_sec <= t_2k:
        return 4.5 * t_sec
    if t_sec <= t_4k:
        return 2000.0 + 4.5 * (t_sec - t_2k)
    return min(4000.0 + 2.5 * (t_sec - t_4k), LINE_TOTAL_M)


def pos_at(s_m):
    """s → (lon, lat)，十三点折线内按 s 线性插值（与 SimAisConnector 折线模式同口径）。"""
    s_m = max(0.0, min(s_m, LINE_TOTAL_M))
    for i in range(1, len(CHART)):
        s0, lon0, lat0 = CHART[i - 1]
        s1, lon1, lat1 = CHART[i]
        if s_m <= s1 or i == len(CHART) - 1:
            f = 0.0 if s1 == s0 else (s_m - s0) / (s1 - s0)
            return (lon0 + f * (lon1 - lon0), lat0 + f * (lat1 - lat0))
    return (CHART[-1][1], CHART[-1][2])


def dist_m(p, q):
    """两点的平面近似距离（m），每段 latRef 取两端均值（WaterwayGeometry 口径）。"""
    lat_ref = (p[1] + q[1]) / 2.0
    dx = (q[0] - p[0]) * M_PER_DEG_LAT * math.cos(math.radians(lat_ref))
    dy = (q[1] - p[1]) * M_PER_DEG_LAT
    return math.hypot(dx, dy)


def bearing_deg(p, q):
    dx = (q[0] - p[0]) * math.cos(math.radians((p[1] + q[1]) / 2.0))
    dy = q[1] - p[1]
    return (math.degrees(math.atan2(dx, dy)) + 360.0) % 360.0


def interp_path(p, q, f):
    return (p[0] + f * (q[0] - p[0]), p[1] + f * (q[1] - p[1]))


MEET = pos_at(MEET_S)
DIST_N1_MEET = dist_m(NEST_01, MEET)          # 去程距离
ARRIVE_T = DEPART_T + DIST_N1_MEET / DRONE_SPEED_MS   # 到达会面点悬停
DIST_MEET_N1 = DIST_N1_MEET                   # 返原巢（003 position_type＝原巢）
RETURN_ARRIVE_T = HANDOVER_END_T + DIST_MEET_N1 / DRONE_SPEED_MS


def drone_pos_at(t_sec):
    """无人机位置（去程 → 悬停等待 → 交接 → 返原巢）。"""
    if t_sec < DEPART_T:
        return NEST_01
    if t_sec < ARRIVE_T:
        return interp_path(NEST_01, MEET, (t_sec - DEPART_T) / (ARRIVE_T - DEPART_T))
    if t_sec < HANDOVER_END_T:
        return MEET
    if t_sec < RETURN_ARRIVE_T:
        return interp_path(MEET, NEST_01, (t_sec - HANDOVER_END_T) / (RETURN_ARRIVE_T - HANDOVER_END_T))
    return NEST_01


def drone_phase_at(t_sec):
    if t_sec < DEPART_T:
        return "待命"
    if t_sec < ARRIVE_T:
        return "巡航"
    if t_sec < MEET_T:
        return "悬停等待"
    if t_sec < HANDOVER_END_T:
        return "末段交接"
    if t_sec < RETURN_ARRIVE_T:
        return "返航"
    return "归巢"


def drone_battery_at(t_sec):
    """电量（%）：起飞后按飞行/悬停耗电率线性扣减，落地后保持。"""
    if t_sec <= DEPART_T:
        return BATTERY_START
    t_end = min(t_sec, RETURN_ARRIVE_T)
    fly = 0.0
    hover = 0.0
    # 飞行段：[DEPART_T, ARRIVE_T] 与 [HANDOVER_END_T, RETURN_ARRIVE_T]；悬停段：其余
    fly += max(0.0, min(t_end, ARRIVE_T) - DEPART_T)
    if t_end > HANDOVER_END_T:
        fly += min(t_end, RETURN_ARRIVE_T) - HANDOVER_END_T
    hover += max(0.0, min(t_end, HANDOVER_END_T) - ARRIVE_T)
    if t_sec > RETURN_ARRIVE_T:
        hover += 0.0
    return max(0.0, BATTERY_START - fly * BATTERY_DRAIN_PER_S - hover * BATTERY_HOVER_PER_S)


def drone_heading_at(t_sec):
    if t_sec < DEPART_T or t_sec >= RETURN_ARRIVE_T:
        return None
    p0 = drone_pos_at(t_sec)
    p1 = drone_pos_at(t_sec + 1.0)
    if p0 == p1:
        return None
    return round(bearing_deg(p0, p1), 1)


def ship_traj_frame(t_sec):
    """预测帧：从当前时刻前伸 120 s（12 点 × 10 s），闭环口径取真值。"""
    points = []
    for k in range(1, 13):
        tf = t_sec + 10.0 * k
        if s_at(tf) >= LINE_TOTAL_M:
            break
        lon, lat = pos_at(s_at(tf))
        points.append({"t": T0_MS + int(tf * 1000), "lon": round(lon, 6), "lat": round(lat, 6),
                       "sigmaAlong": SIGMA_ALONG_M, "sigmaCross": SIGMA_CROSS_M})
    if not points:
        return None                       # 无预测应不发帧（ShipTrajFrame 契约）
    lon, lat = pos_at(s_at(t_sec))
    lon2, lat2 = pos_at(s_at(t_sec + 1.0))
    return {"type": "ship_traj", "data": {
        "mmsi": MMSI, "version": PRED_VERSION, "t0": T0_MS + int(t_sec * 1000),
        "courseDeg": round(bearing_deg((lon, lat), (lon2, lat2)), 1),
        "validitySec": VALIDITY_SEC, "premise": "VALID", "points": points}}


def drone_state_frame(t_sec):
    lon, lat = drone_pos_at(t_sec)
    return {"type": "drone_state", "data": {
        "uavId": UAV_ID, "lon": round(lon, 6), "lat": round(lat, 6),
        "battery": round(drone_battery_at(t_sec), 1), "phase": drone_phase_at(t_sec),
        "ts": T0_MS + int(t_sec * 1000), "heading": drone_heading_at(t_sec),
        "activeOrderId": ORDER_ID if DEPART_T <= t_sec < RETURN_ARRIVE_T else None}}


def order_state_frame(t_sec, status, version):
    return {"type": "order_state", "data": {
        "orderId": ORDER_ID, "orderStatus": status, "stateVersion": version,
        "eventTs": T0_MS + int(t_sec * 1000)}}


def rendezvous_frame(t_sec, locked):
    return {"type": "rendezvous", "data": {
        "orderId": ORDER_ID, "meetLon": round(MEET[0], 6), "meetLat": round(MEET[1], 6),
        "locked": locked, "candidateCount": 2, "rejectionReasons": [],
        "etaTs": T0_MS + int(MEET_T * 1000),
        "chosenNestId": "NEST-01" if locked else None,
        "chosenDroneId": UAV_ID if locked else None, "candidates": []}}


def alarm_frame(t_sec, alert_type, level, target, message, lon, lat):
    return {"type": "alarm", "data": {
        "alertType": alert_type, "alertLevel": level, "target": target, "message": message,
        "lon": None if lon is None else round(lon, 6),
        "lat": None if lat is None else round(lat, 6),
        "orderId": ORDER_ID if target == MMSI else None,
        "eventId": "EVT-1109-%04d" % int(t_sec)}}


def plan_track_frame():
    """三段航迹：去程（巢→会面点）、末段交接（悬停下降→交接完成）、返程（会面点→原巢）。"""

    def leg(p, q, t_start, t_end, alt_start, alt_end, speed):
        pts = []
        n = 6
        for k in range(n + 1):
            f = k / n
            lon, lat = interp_path(p, q, f)
            pts.append({"lon": round(lon, 6), "lat": round(lat, 6),
                        "altM": round(alt_start + f * (alt_end - alt_start), 1),
                        "speedMs": 0.0 if k == n else speed,
                        "tMs": T0_MS + int((t_start + f * (t_end - t_start)) * 1000)})
        return pts

    segments = [
        {"kind": "去程", "points": leg(NEST_01, MEET, DEPART_T, ARRIVE_T, 120.0, 120.0, DRONE_SPEED_MS)},
        {"kind": "末段交接", "points": leg(MEET, MEET, ARRIVE_T, HANDOVER_END_T, 120.0, 60.0, 0.0)},
        {"kind": "返程", "points": leg(MEET, NEST_01, HANDOVER_END_T, RETURN_ARRIVE_T, 60.0, 120.0, DRONE_SPEED_MS)},
    ]
    return {"type": "plan_track", "data": {
        "orderId": ORDER_ID, "routeId": ROUTE_ID, "comboId": COMBO_ID,
        "opType": "规划航迹", "segments": segments}}


def build_frames():
    events = []       # (t_sec, 序号内顺序, frame)
    # 订单状态：中文状态词（契约 §8；无 WAIT_CARGO，不涉及 orderTimeline 例外）
    for t, status, v in [
        (60, "待受理", 1), (120, "已受理待唤醒", 2), (180, "待调度", 3),
        (300, "调度中", 4), (400, "已派发", 5), (430, "去程中", 6),
        (667, "末段交接中", 7), (760, "返航中", 8), (1000, "已完成", 9),
    ]:
        events.append((t, 0, order_state_frame(t, status, v)))
    events.append((400.0, 1, rendezvous_frame(400.0, False)))
    events.append((MEET_T, 1, rendezvous_frame(MEET_T, True)))
    events.append((400.0, 2, plan_track_frame()))
    # 告警两帧：弯道偏航自愈（WARN）、阵风短时超限提示（INFO）
    lon510, lat510 = pos_at(s_at(510.0))
    events.append((510.0, 1, alarm_frame(510.0, "DEVIATION", "WARN", MMSI,
                                         "船位短时偏离走廊中心线，已自愈恢复", lon510, lat510)))
    lon1020, lat1020 = drone_pos_at(1020.0)
    events.append((1020.0, 1, alarm_frame(1020.0, "WEATHER_EXCEED", "INFO", UAV_ID,
                                          "阵风短时超限提示，已恢复正常巡航", lon1020, lat1020)))
    # 周期帧：ship_traj 每 60 s（末段预测窗口越线后停发）；drone_state 每 60 s 至归巢
    t = 0.0
    while t <= 1560.0:
        frame = ship_traj_frame(t)
        if frame is not None:
            events.append((t, 3, frame))
        t += 60.0
    t = 0.0
    while t <= 1020.0:
        events.append((t, 4, drone_state_frame(t)))
        t += 60.0
    events.sort(key=lambda e: (e[0], e[1]))
    return [e[2] for e in events]


def main():
    parser = argparse.ArgumentParser(description="生成六类帧 replay（normal_straight seed 1）")
    parser.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "replay-normal_straight-s1.json"))
    args = parser.parse_args()
    frames = build_frames()
    payload = {"frames": frames}
    with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    types = {}
    for frame in frames:
        types[frame["type"]] = types.get(frame["type"], 0) + 1
    finished = any(f["type"] == "order_state" and f["data"]["orderStatus"] in ("FINISHED", "已完成")
                   for f in frames)
    print("frames:", len(frames), "| by type:", types, "| 终态已完成帧存在:", finished)
    print("会面点 (s=%.1f m): lon=%.6f lat=%.6f，船到时刻 %.3f s，全程真值用时 %.3f s"
          % (MEET_S, MEET[0], MEET[1], MEET_T, 888.889 + (LINE_TOTAL_M - 4000.0) / 2.5))


if __name__ == "__main__":
    main()
