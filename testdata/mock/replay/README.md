# replay/ —— 六类帧回放产物（演示用）

## 这是什么

一套六类帧 replay，数据源 run 为 [`../scenarios/normal_straight.yaml`](../scenarios/normal_straight.yaml)（seed 1、live、s = 0 全程 30 min）。按执行计划 §2：**只做演示，不进验收台账**，不得把演示数据当验收数据。

## 产物

| 文件 | 说明 |
| --- | --- |
| `replay-normal_straight-s1.json` | `{"frames":[{"type","data"},...]}`，59 帧 |
| `build_replay.py` | 生成器，确定性输出（无随机数），复跑逐字节一致 |

六类帧齐全：`ship_traj` 27、`drone_state` 18、`order_state` 9、`rendezvous` 2、`plan_track` 1、`alarm` 2。

## 契约符合性（§8）

- 统一信封 `{"type","data"}`，**无 `payload`、顶层无 `ts`**；
- 帧类型六类固定，与 `WsFrameType` 一致；
- 订单状态全用中文词（`待受理`→…→`已完成`），终态 `已完成`（对应 `FINISHED`），无 `COMPLETED`；
- 无 `WAIT_CARGO` 路径，不涉及「等待装货不进 orderTimeline」例外；
- 告警帧 `alertType`／`alertLevel` 用枚举名（`DEVIATION`/`WARN` 等，与后端 Jackson 默认序列化一致），前端 `displayEnum` 转中文。

## 前端怎么播

与 `GET /api/replay/{droneId}` 返回结构同构：`App.vue loadGuidedHistory` 消费 `history.frames`，
经 `frontend/src/replay/model.js` 的 `buildReplaySteps(frames)` 转回放步骤；
终态判定认 `order_state` 且 `orderStatus ∈ {FINISHED, 已完成}`——本产物末帧序含 `已完成`（stateVersion 9），
前端能播完。本地演示可用任意静态服务把该 JSON 挂到回放入口，或直接喂 `buildReplaySteps`。

## 数据口径

- 船舶真值：契约 §2 限速积分（4.5／4.5／2.5 m/s 三段），全程 1690.229 s，`s_m ≤ 6003.3510 m`；
  预测帧按**仿真内闭环**口径取真值前伸（120 s 窗口 × 10 s 步长），
  `sigmaAlong`/`sigmaCross` 取 sigmaEM = 15 m（`002_sys_param` 初始建议值，`design_assumption`）。
- 会面点：s = 3000 m（`115.858686/28.668006`，南昌大桥上游侧），船到时刻 666.667 s；
  无人机 `UAV-02`（M400，巡航 15 m/s）自 `NEST-01` 起飞，`plan_track` 三段词
  去程／末段交接／返程（`PlanTrackFrame.SEGMENT_*`）。
- `ship_traj` 在 t = 1560 s 后停发：再前伸 120 s 预测窗口将越过折线末端，
  按「无预测应不发帧」契约处理，不产出末端重合的坏尾巴。
- `evidence_class: simulation_closed_loop`——本产物是闭环自洽演示数据，
  对外写「仿真内闭环指标」，禁写「实测覆盖率」「赣江实测」（契约 §13）。

## 复跑

```bash
cd testdata/mock/replay
python3 build_replay.py            # 生成 replay-normal_straight-s1.json
python3 build_replay.py --out <任意路径>.json
```

依赖仅 Python 3 标准库；输出确定性，逐字节一致。
