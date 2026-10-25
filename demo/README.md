# WaveNest 前端演示材料

本目录提供两段用途不同的录屏。播放前请先确认要讲的是界面功能，还是本机前后端联调结果。

## 1. `wavenest-compact-complete-demo.webm`

- 紧凑版前端的完整功能讲解，约 91 秒，1440×900。
- 使用离线样例，覆盖运行总览、下单、调度、异常处置、两种船舶变向选择和任务回放。
- 两种变向选择用于讲清业务规则和界面交互，不能作为正式后端闭环已经完成的证据。

## 2. `wavenest-live-service-demo.webm`

- 本机前端与正式 Spring Boot 后端的实时联调录屏，1440×900。
- 录制时实际运行 MySQL 3306、Redis 6379、RabbitMQ 5672、Mosquitto 1883、后端 8080 和前端 5173。
- 实际演示链：创建订单 → 后端自动派发 → 调度台核对 → WebSocket 实时遥测 → 后端注入船舶变向 → 历史轨迹回放。
- 录制订单：`ORD-1792901862235-4`；自动派发无人机：`UAV-01`。
- 当前正式后端会自动推进至“已派发”。从去程、交接、返航到完成的自动状态推进尚未接入，因此视频在结尾明确说明该边界。

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
- `POST /sim/ship/turn` 返回变向次数和新航向；
- `GET /api/replay/{droneId}` 返回历史帧。
