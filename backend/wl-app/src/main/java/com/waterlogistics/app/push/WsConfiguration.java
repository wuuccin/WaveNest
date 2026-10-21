package com.waterlogistics.app.push;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WsConfiguration implements WebSocketConfigurer {
    private final WsPushEndpoint endpoint;
    public WsConfiguration(WsPushEndpoint endpoint) { this.endpoint = endpoint; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(endpoint, "/ws").setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173");
    }
}
