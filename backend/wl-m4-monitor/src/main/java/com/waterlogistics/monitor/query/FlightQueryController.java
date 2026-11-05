package com.waterlogistics.monitor.query;

import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.contract.ws.DroneStateFrame;
import com.waterlogistics.contract.ws.WsFrameType;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** D1 飞行回放与统计的只读 HTTP 入口。 */
@RestController
public final class FlightQueryController {

    private final FlightQueryService queryService;

    public FlightQueryController(FlightQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 返回前端实时处理器可直接重放的历史帧；记录顺序由仓内时间排序保证。
     * 未知任务使用明确的 404 语义，避免把“任务不存在”误显示成“任务没有轨迹”。
     */
    @GetMapping(ApiPath.REPLAY_TEMPLATE)
    public ReplayResponse replay(@PathVariable("taskId") String taskId) {
        List<TelemetryState> states = queryService.replay(taskId);
        if (states.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到飞行任务：" + taskId);
        }
        List<ReplayFrame> frames = states.stream()
                .map(FlightQueryController::toDroneStateFrame)
                .toList();
        return new ReplayResponse(taskId, frames);
    }

    /** 飞行记录仓统计；不占用调度侧的 /api/monitor/statistics。 */
    @GetMapping(ApiPath.REPLAY_STATISTICS)
    public Map<String, Object> statistics() {
        return queryService.statistics();
    }

    private static ReplayFrame toDroneStateFrame(TelemetryState state) {
        var telemetry = state.telemetry();
        return new ReplayFrame(WsFrameType.DRONE_STATE.getType(), new DroneStateFrame(
                telemetry.uavId(), telemetry.lon(), telemetry.lat(), telemetry.battery(),
                null, telemetry.ts(), telemetry.heading(), null));
    }

    public record ReplayResponse(String taskId, List<ReplayFrame> frames) {
    }

    public record ReplayFrame(String type, DroneStateFrame data) {
    }
}
