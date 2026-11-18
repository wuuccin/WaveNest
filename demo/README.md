# WaveNest 前端演示材料

本目录提供离线界面讲解和真实服务联跑录屏。播放前请先确认视频的数据来源。

`wavenest-compact-complete-demo.webm` 是旧版船舶变向脚本的历史材料，不作为风速突变主线的验收证据。

## 新版离线讲解视频

`wavenest-weather-offline-demo.webm` 展示从填写订单到风速突变、原机返航、风况回落、重新派单和任务回放的七段界面流程。该视频使用离线样例帧，用于讲解操作步骤，不作为前后端实时联通的验收证据。

重新录制时，在仓库根目录运行 `node frontend/tests/record-offline-weather-demo.mjs`。本机需要 Playwright 的 FFmpeg 组件；首次使用可在 `frontend` 目录运行 `pnpm exec playwright install ffmpeg`。

## 1. `wavenest-compact-complete-demo.webm`

- 紧凑版前端的完整功能讲解，约 91 秒，1440×900。
- 使用离线样例，覆盖运行总览、下单、调度、气象超限处置和任务回放。
- 旧录屏仍含船舶变向选择，内容与当前风速突变主线不一致，仅供查看历史界面。

## 2. `wavenest-live-service-demo.webm`

- 风速突变主线的前后端实时联跑录屏，1440×900；使用正式 Spring Boot 服务与独立 `wavenest_demo` 库。
- 录制时运行 MySQL 3306、Redis 6379、RabbitMQ 5672、Mosquitto 1883、后端 8081、前端 5174。
- 演示链：真实下单 → 自动派发 → WebSocket 遥测 → 风速超限 → 在飞复核终止 → 原机返航 → 货物回收 → 风况回落和闸门解除 → 重新派单 → 投放回执 → 返航完成 → 历史轨迹回放。
- 仿真回收确认与投放回执通过 `/sim` 端点注入真实 MQ 消息，状态与审计由后端生产消费者落库；录制脚本不直接改订单状态。

## 重新录制实时联调视频

确认前端、后端和中间件已启动后，在 `frontend` 目录执行：

```powershell
$env:BROWSER_PATH='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
$env:FRONTEND_URL='http://127.0.0.1:5174'
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
- 真实回收确认后货物回巢并重新就绪，订单自动重派；随后以真实投放回执形成交付凭证，订单推进到完成；
- `GET /api/replay/{droneId}` 返回历史帧。
