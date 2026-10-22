package com.waterlogistics.app.push;

import com.waterlogistics.contract.ws.WsEnvelope;
import com.waterlogistics.contract.ws.WsFrameType;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Single realtime outlet; accepts already projected frames and makes no business decisions. */
@Component
public final class WsPushEndpoint extends TextWebSocketHandler {
    private static final Set<String> TYPES = EnumSet.allOf(WsFrameType.class).stream()
            .map(WsFrameType::getType)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper json;
    public WsPushEndpoint(ObjectMapper json) { this.json = json; }
    @Override public void afterConnectionEstablished(WebSocketSession session) { sessions.add(session); }
    @Override public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) { sessions.remove(session); }
    public void publish(WsFrameType type, Object data) {
        if (type == null) throw new IllegalArgumentException("WS type is required");
        publish(type.getType(), data);
    }
    public void publish(String type, Object data) {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Unknown WS type: " + type);
        try {
            String frame = json.writeValueAsString(new WsEnvelope<>(type, data));
            for (var session : sessions) {
                if (!session.isOpen()) { sessions.remove(session); continue; }
                try { synchronized (session) { session.sendMessage(new TextMessage(frame)); } }
                catch (IOException e) { sessions.remove(session); try { session.close(); } catch (IOException ignored) {} }
            }
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid WS payload", e); }
    }
}
