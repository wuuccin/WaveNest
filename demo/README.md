# 彭紫情：10 月 16 日大屏与 M4 交付

## 可直接展示

- `six-stage-demo.webm`：离线演示数据录制的六段画面，可在无后端时播放。
- 打开前端后切到“离线演示”，点击“一键演示”；“查看回放”可拖动六段时间轴。
- “开始录屏”会调用浏览器屏幕共享；结束后下载 WebM 文件。
- “导出回放数据”会下载 JSON；“导入录制”可在前端重放该 JSON。

## 真数据接线

- 前端默认选“实时服务”，WebSocket 连接后端 `/ws`，断开时尝试轮询订单。数据源由 `frontend/.env.example` 配置。
- M4 只消费一次 `data.uav.telemetry`，使用契约 `UavTelemetry` 清洗，按 `task.plan` 关联任务，产出 `drone_state`，保存飞行记录供 `/api/monitor/tasks` 和 `/api/monitor/tasks/{taskId}/records` 查询。
- `WsPushEndpoint` 是五类实时帧的唯一广播出口；`/api/replay/{taskId}` 读取它已发送的任务帧。
- 实时“一键演示”只按后端确实发出的帧推进：下单／会面、起飞、船变向、原机返航、重入队、另一架机交接并完成。缺少事件时会停在对应段并报超时，不自动伪造状态。

## 联调前提

- M2/M3 需产出 `task.plan` 和 `order_state`；M1 需产出 `data.uav.telemetry` 及可复现的变向；告警帧需由宋冰鑫的检测链调用唯一推送出口。
- M4 清洗阈值需按组内裁定配置七个 `UAV_*` 环境变量并启用 `TELEMETRY_ENABLED`；配置值未获裁定前，实时遥测消费保持关闭。
- 本机完整后端数据库测试目前因 `root` 登录被拒而无法通过；项目未保存数据库密码。

## 本地验证

在仓库根目录运行 `./gradlew.bat :wl-app:compileJava :wl-m4-monitor:test`；在 `frontend` 运行 `pnpm build` 和 `node --test tests/demo-runner.test.mjs`。
