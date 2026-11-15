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
        registry.addHandler(endpoint, "/ws").setAllowedOriginPatterns(
                "http://localhost:5173", "http://127.0.0.1:5173",
                "http://10.*:5173", "http://192.168.*:5173", "http://172.16.*:5173",
                "http://172.17.*:5173", "http://172.18.*:5173", "http://172.19.*:5173",
                "http://172.20.*:5173", "http://172.21.*:5173", "http://172.22.*:5173",
                "http://172.23.*:5173", "http://172.24.*:5173", "http://172.25.*:5173",
                "http://172.26.*:5173", "http://172.27.*:5173", "http://172.28.*:5173",
                "http://172.29.*:5173", "http://172.30.*:5173", "http://172.31.*:5173");
    }
}
