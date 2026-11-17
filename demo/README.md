# WaveNest 前端演示材料

本目录提供新版离线界面讲解，以及两段历史录屏。播放前请先确认视频的数据来源。

两段历史视频录于旧版船舶变向脚本，仅保留作历史材料，不作为风速突变主线的验收证据。新版实时服务视频须在后端和中间件启动后重新录制，并逐段核对气象超限、风况回落、闸门解除及重派交接。

## 新版离线讲解视频

`wavenest-weather-offline-demo.webm` 展示从填写订单到风速突变、原机返航、风况回落、重新派单和任务回放的七段界面流程。该视频使用离线样例帧，用于讲解操作步骤，不作为前后端实时联通的验收证据。

重新录制时，在仓库根目录运行 `node frontend/tests/record-offline-weather-demo.mjs`。本机需要 Playwright 的 FFmpeg 组件；首次使用可在 `frontend` 目录运行 `pnpm exec playwright install ffmpeg`。

## 1. `wavenest-compact-complete-demo.webm`

- 紧凑版前端的完整功能讲解，约 91 秒，1440×900。
- 使用离线样例，覆盖运行总览、下单、调度、气象超限处置和任务回放。
- 旧录屏仍含船舶变向选择，内容与当前风速突变主线不一致，仅供查看历史界面。

## 2. `wavenest-live-service-demo.webm`

- 旧版前后端联调录屏，约 12 分 30 秒，1440×900。该视频不能证明当前风速突变主线。
- 录制时实际运行 MySQL 3306、Redis 6379、RabbitMQ 5672、Mosquitto 1883、后端 8080 和前端 5173。
- 旧版实际演示链：创建订单 → 后端自动派发 → 调度台核对 → WebSocket 实时遥测 → 去程 → 末段交接 → 返航 → 完成 → 船舶变向 → 历史轨迹回放。
- 录制订单：`ORD-1792908153636-2`；自动派发无人机：`UAV-01`。任务卡、任务地图和电量均绑定该订单的实际执行机，不受其他无人机遥测覆盖。
- 四段飞行状态均由后端 `FlightPhaseAdvancer` 写入 `task_log` 后实时广播。数据库审计时间为 14:04:02 去程、14:08:15 末段交接、14:10:15 返航、14:14:28 完成。

## 重新录制实时联调视频

确认前端、后端和中间件已启动后，在 `frontend` 目录执行：

```powershell
$env:BROWSER_PATH='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
node tests/record-live-service-demo.mjs
```

脚本只使用“实时服务”数据源，并会检查以下真实结果：

- WebSocket 显示已连接；
- `POST /api/orders` 返回真实订单号；
- `GET /api/orders` 返回自动派发状态；
- `GET /api/monitor/tasks` 返回无人机和会面点；
- WebSocket 依次收到去程、末段交接、返航和完成；
- 原机去程中调用 `POST /sim/weather/inject` 注入超限风速，随后从 `GET /api/orders` 检查熔断和复核结论；
- `DELETE /sim/weather/bias` 清除偏置，随后由 `GET /api/orders` 确认派单闸门恢复正常；
- 后端订单状态继续推进到末段交接、返航和完成；
- `GET /api/replay/{droneId}` 返回历史帧。
