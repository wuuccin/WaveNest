package com.waterlogistics.app.push;

import com.waterlogistics.monitor.query.FlightQueryService;
import com.waterlogistics.monitor.store.FlightRecordStore;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Configuration
class MonitorStoreConfiguration {
    @Bean FlightRecordStore flightRecordStore() { return new FlightRecordStore(); }
    @Bean FlightQueryService flightQueryService(FlightRecordStore store) { return new FlightQueryService(store); }
}

/** Queries observed task telemetry and frames actually sent by the realtime endpoint. */
@RestController
public class MonitorQueryController {
    private final FlightQueryService query;
    private final FrameArchive archive;
    public MonitorQueryController(FlightQueryService query, FrameArchive archive) {
        this.query = query;
        this.archive = archive;
    }
    @GetMapping("/api/monitor/tasks")
    public Map<String, Object> tasks() { return Map.of("tasks", query.tasks()); }
    @GetMapping("/api/monitor/tasks/{taskId}/records")
    public Map<String, Object> records(@PathVariable String taskId) { return Map.of("records", query.replay(taskId)); }
    @GetMapping("/api/monitor/statistics")
    public Map<String, Object> statistics() { return query.statistics(); }
    @GetMapping("/api/replay/{taskId}")
    public Map<String, Object> replay(@PathVariable String taskId) {
        var task = query.tasks().stream().filter(row -> row.taskId().equals(taskId)).findFirst();
        if (task.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Task not found");
        var item = task.get();
        return Map.of("frames", archive.replay(taskId));
    }
}
