package com.proxy.client.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "proxy.client")
public class ClientConfig {

    private Server server;
    private Reconnect reconnect;
    private Heartbeat heartbeat;
    private int listenPort;

    @Data
    public static class Server {
        private String host;
        private int port;
    }

    @Data
    public static class Reconnect {
        private long initialDelayMs;
        private long maxDelayMs;
        private int maxAttempts;
    }

    @Data
    public static class Heartbeat {
        private long intervalMs;
        private long timeoutMs;
    }
}
