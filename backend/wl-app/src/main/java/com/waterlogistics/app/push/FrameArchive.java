package com.waterlogistics.app.push;

import com.waterlogistics.contract.ws.WsEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Task-scoped live-frame buffer. A published frame has no task until its producer supplies one. */
@Component
public final class FrameArchive {
    private static final int MAX_FRAMES_PER_TASK = 10_000;
    private final Map<String, List<WsEnvelope<?>>> byTask = new ConcurrentHashMap<>();
    public void append(String taskId, WsEnvelope<?> frame) {
        if (taskId == null || taskId.isBlank()) return;
        var frames = byTask.computeIfAbsent(taskId, ignored -> new ArrayList<>());
        synchronized (frames) {
            if (frames.size() == MAX_FRAMES_PER_TASK) frames.remove(0);
            frames.add(frame);
        }
    }
    public List<WsEnvelope<?>> replay(String taskId) {
        var frames = byTask.get(taskId);
        if (frames == null) return List.of();
        synchronized (frames) { return List.copyOf(frames); }
    }
}
