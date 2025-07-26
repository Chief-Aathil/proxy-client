package com.proxy.client.connection;

import com.proxy.client.communicator.ProxyClientCommunicator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.Socket;

@RequiredArgsConstructor
@Slf4j
@Component
public class ProxyClientConnectionManager {

    @Value("${proxy.server.host}")
    private String proxyServerHost;

    @Value("${proxy.server.port}")
    private int proxyServerPort;

    private Socket clientSocket;
    private final ProxyClientCommunicator proxyClientCommunicator;


    @PostConstruct
    public void init() {
        log.info("Attempting to connect to proxy server at {}:{}", proxyServerHost, proxyServerPort);
        connect();
    }

    /**
     * Establishes a single TCP connection to the offshore proxy server.
     */
    private void connect() {
        try {
            clientSocket = new Socket(proxyServerHost, proxyServerPort);
            clientSocket.setTcpNoDelay(true); // Optimize for lower latency
            log.info("Successfully connected to proxy server at {}:{}", proxyServerHost, proxyServerPort);

            // Initialize the communicator with the socket's streams
            proxyClientCommunicator.initialize(clientSocket.getInputStream(), clientSocket.getOutputStream());

            // Start communicator's send and receive threads
            proxyClientCommunicator.start();

        } catch (IOException e) {
            log.error("Failed to connect to proxy server at {}:{}: {}", proxyServerHost, proxyServerPort, e.getMessage());
            // In future phases, this is where reconnect logic will go.
            // For now, if it fails here, the application might not function correctly.
        }
    }

    /**
     * Closes the connection gracefully when the application shuts down.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ProxyClientConnectionManager. Closing client socket.");
        try {
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException e) {
            log.error("Error closing client socket: {}", e.getMessage());
        } finally {
            proxyClientCommunicator.shutdown(); // Ensure communicator resources are also released
        }
    }

    /**
     * Check if the connection is currently active.
     * @return true if connected, false otherwise.
     */
    public boolean isConnected() {
        return clientSocket != null && clientSocket.isConnected() && !clientSocket.isClosed();
    }
}