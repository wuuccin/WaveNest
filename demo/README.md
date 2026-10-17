# 彭紫情：10 月 16 日大屏与 M4 交付

## 10 月 17 日完整电脑端演示

- `wavenest-complete-demo.mp4`：约 1 分 50 秒，1440×900，带中文字幕提示，可在 Windows 视频播放器或浏览器中打开。
- 视频依次展示实时服务连接、船员下单、订单调度、异常处置、逐步演示的两种变向选择、订单进展和任务回放。
- “放弃会面并带货返航”使用后端仿真回放；“原机继续配送”明确标为前端情境推演，不代表后端已执行这个选择。
- `wavenest-complete-demo.webm` 是同一段视频的原始录屏文件。

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
- 本机演示数据库使用未入库的 `application-local.yml`，连接 `127.0.0.1:3307`；已验证 `wavenest` 数据库的 14 张表及后端订单接口。其他电脑需按各自环境配置数据库连接。

## 本地验证

在仓库根目录运行 `./gradlew.bat :wl-app:compileJava :wl-m4-monitor:test`；在 `frontend` 运行 `pnpm build` 和 `node --test tests/demo-runner.test.mjs`。
