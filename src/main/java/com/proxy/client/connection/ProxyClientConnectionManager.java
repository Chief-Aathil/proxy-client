package com.proxy.client.connection;

import com.proxy.client.communicator.FramedMessage;
import com.proxy.client.communicator.ProxyClientCommunicator;
import com.proxy.client.config.ClientConfig;
import com.proxy.client.listener.ClientConnectionListener;
import com.proxy.client.queue.RequestQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProxyClientConnectionManager {

    private final ClientConfig clientConfig;
    private final ProxyClientCommunicator clientCommunicator;
    private final RequestQueue requestQueue;
    private final ClientConnectionListener clientConnectionListener;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1); // For reconnect delays and heartbeats
    private final ReentrantLock connectionLock = new ReentrantLock(); // To prevent concurrent connection attempts
    private final AtomicBoolean connectionLostSignal = new AtomicBoolean(false);

    private volatile boolean active = false;
    private volatile Socket proxyServerClientSocket; // The socket connecting to the offshore proxy server


    @PostConstruct
    public void init() {
        active = true;
        scheduler.submit(this::initiateConnectionFlow);
    }

    /**
     * Initiates the connection flow, attempting to connect/reconnect to the proxy server.
     */
    private void initiateConnectionFlow() {
        Thread.currentThread().setName("Client-Connection-Flow");
        String host = clientConfig.getServer().getHost();
        int port = clientConfig.getServer().getPort();
        log.info("Initiating connection flow to proxy server at {}:{}", host, port);
        long currentDelay = clientConfig.getReconnect().getInitialDelayMs();
        AtomicInteger attempts = new AtomicInteger(0);
        int maxAttempts = clientConfig.getReconnect().getMaxAttempts();

        while (active && !isConnected()) {
            connectionLock.lock();
            try {
                if (isConnected()) { // Recheck inside lock
                    break;
                }
                attempts.incrementAndGet();
                log.info("Attempting to connect to proxy server (attempt {}/{})...",
                        attempts.get(), (maxAttempts == 0 ? "unlimited" : maxAttempts));
                try {
                    establishProxyConnection(host, port);
                    break; // Exit retry loop
                } catch (IOException e) {
                    log.error("Failed to connect to proxy server: {}. Retrying in {} ms.", e.getMessage(), currentDelay);
                    cleanupDisconnectedState(); // Clean up if previous attempt failed
                    if (maxAttempts != 0 && attempts.get() >= maxAttempts) {
                        log.error("Maximum connection attempts reached ({}). Giving up.", maxAttempts);
                        active = false;
                        break;
                    }
                    try {
                        // Exponential backoff
                        Thread.sleep(currentDelay);
                        currentDelay = Math.min(currentDelay * 2, clientConfig.getReconnect().getMaxDelayMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Connection attempt interrupted.");
                        active = false;
                        break;
                    }
                }
            } finally {
                connectionLock.unlock();
            }
        }
        if (!active) {
            log.error("Connection manager has stopped trying to connect to the proxy server.");
        }
    }

    private void establishProxyConnection(String host, int port) throws IOException {
        // Try to establish the connection
        proxyServerClientSocket = new Socket(host, port);
        proxyServerClientSocket.setTcpNoDelay(true);
        log.info("Successfully connected to proxy server at {}:{}", host, port);

        // Initialize and start the communicator with the new socket
        clientCommunicator.initialize(proxyServerClientSocket, this::onConnectionLoss);
        clientCommunicator.start();

        // Connection successful, start heartbeat and browser listener
        startHeartbeat();
        if (!clientConnectionListener.isRunning()) { // Prevent multiple starts if already running
            clientConnectionListener.startListening();
        }
        connectionLostSignal.set(false); // Reset signal
    }


    /**
     * Callback method invoked by ProxyClientCommunicator when connection is lost.
     */
    private void onConnectionLoss() {
        log.warn("Connection to proxy server lost. Signalling reconnect.");
        connectionLostSignal.set(true);
        // Immediately trigger a reconnection attempt outside the current communication loops
        scheduler.submit(this::handleReconnectTrigger);
    }

    /**
     * Handles the reconnection trigger. Ensures only one reconnect attempt is active.
     */
    private void handleReconnectTrigger() {
        if (!active) {
            log.info("Connection manager is not active, skipping reconnect attempt.");
            return;
        }
        if (connectionLock.tryLock()) { // Try to acquire lock non-blockingly
            try {
                if (!connectionLostSignal.get()) { // Already reconnected or signal reset
                    log.debug("No active connection loss signal, skipping reconnect initiation.");
                    return;
                }
                log.info("Connection loss detected. Shutting down current communicator and initiating reconnection flow.");
                // Explicitly shut down the old communicator and close socket if any
                cleanupDisconnectedState();
                connectionLostSignal.set(false); // Reset signal before re-attempt
                scheduler.submit(this::initiateConnectionFlow); // Start new connection flow
            } finally {
                connectionLock.unlock();
            }
        } else {
            log.debug("Another reconnection attempt is already in progress. Skipping.");
        }
    }


    /**
     * Starts the heartbeat mechanism to periodically send PINGs and check for PONGs.
     */
    private void startHeartbeat() {
        long intervalMs = clientConfig.getHeartbeat().getIntervalMs();
        long timeoutMs = clientConfig.getHeartbeat().getTimeoutMs();
        log.info("Starting heartbeat with interval {}ms and timeout {}ms.", intervalMs, timeoutMs);
        scheduler.scheduleAtFixedRate(() -> {
            if (!active || !isConnected()) {
                log.debug("Skipping heartbeat: not active or not connected.");
                return;
            }
            try {
                // Send a PING and await PONG
                FramedMessage pingMessage = new FramedMessage(FramedMessage.MessageType.HEARTBEAT_PING, UUID.randomUUID(), new byte[0]);
                log.trace("Sending HEARTBEAT_PING for ID: {}", pingMessage.getRequestID());

                clientCommunicator.sendAndAwaitResponse(pingMessage)
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .whenComplete((pongMessage, ex) -> {
                            if (ex == null && pongMessage != null && pongMessage.getMessageType() == FramedMessage.MessageType.HEARTBEAT_PONG) {
                                log.trace("Received HEARTBEAT_PONG for ID: {}", pingMessage.getRequestID());
                            } else {
                                if (ex instanceof TimeoutException) {
                                    log.warn("Heartbeat PONG timeout for ID: {}. Triggering connection loss.", pingMessage.getRequestID());
                                } else {
                                    log.warn("Heartbeat PONG failed for ID: {}. Exception: {}. Triggering connection loss.", pingMessage.getRequestID(), ex != null ? ex.getMessage() : "Unknown");
                                }
                                // Connection assumed lost due to heartbeat failure
                                if (connectionLostSignal.compareAndSet(false, true)) { // Only signal once
                                    scheduler.submit(this::handleReconnectTrigger);
                                }
                            }
                        });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Heartbeat task interrupted.");
            } catch (Exception e) {
                log.error("Error during heartbeat: {}", e.getMessage(), e);
                // Assume connection issue if heartbeat task itself throws unexpected error
                if (connectionLostSignal.compareAndSet(false, true)) { // Only signal once
                    scheduler.submit(this::handleReconnectTrigger);
                }
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Checks if the client is currently connected to the proxy server.
     *
     * @return true if connected and communicator is running, false otherwise.
     */
    public boolean isConnected() {
        return proxyServerClientSocket != null && proxyServerClientSocket.isConnected() && !proxyServerClientSocket.isClosed() && clientCommunicator.isRunning();
    }

    /**
     * Cleans up the disconnected state: stops communicator, closes socket.
     * This is called before attempting a reconnect.
     */
    private void cleanupDisconnectedState() {
        log.info("Cleaning up disconnected state.");
        if (clientCommunicator.isRunning()) {
            clientCommunicator.shutdown();
        }
        if (proxyServerClientSocket != null && !proxyServerClientSocket.isClosed()) {
            try {
                proxyServerClientSocket.close();
                log.info("Closed old proxy server client socket.");
            } catch (IOException e) {
                log.error("Error closing proxy server client socket: {}", e.getMessage());
            } finally {
                proxyServerClientSocket = null;
            }
        }
    }

    /**
     * Closes the connection gracefully when the application shuts down.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ProxyClientConnectionManager.");
        active = false;
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate in time.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Connection manager shutdown interrupted.");
        }
        cleanupDisconnectedState(); // Ensure communicator and socket are closed
        log.info("ProxyClientConnectionManager shutdown complete.");
    }
}