package com.proxy.client;

import com.proxy.client.communicator.FramedMessage;
import com.proxy.client.communicator.ProxyClientCommunicator;
import com.proxy.client.connection.ProxyClientConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class PingTestService {

    private final ProxyClientConnectionManager connectionManager;
    private final ProxyClientCommunicator communicator;

    public PingTestService(ProxyClientConnectionManager connectionManager, ProxyClientCommunicator communicator) {
        this.connectionManager = connectionManager;
        this.communicator = communicator;
    }

    /**
     * This method will be called once the Spring application is fully ready.
     * We'll use it to perform our initial Ping/Pong test.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void runPingTest() {
        if (!connectionManager.isConnected()) {
            log.warn("Client not connected to proxy server. Skipping Ping/Pong test.");
            return;
        }

        UUID pingId = UUID.randomUUID();
        FramedMessage pingMessage = new FramedMessage(FramedMessage.MessageType.HEARTBEAT_PING, pingId, new byte[0]);

        log.info("Sending HEARTBEAT_PING with ID: {}", pingId);

        try {
            // Send PING and await PONG response
            CompletableFuture<FramedMessage> pongFuture = communicator.sendAndAwaitResponse(pingMessage);

            // Wait for the PONG for a short duration
            FramedMessage pongResponse = pongFuture.get(10, TimeUnit.SECONDS); // 5-second timeout

            if (pongResponse.getMessageType() == FramedMessage.MessageType.HEARTBEAT_PONG &&
                    pongResponse.getRequestID().equals(pingId)) {
                log.info("SUCCESS: Received expected HEARTBEAT_PONG for ID: {}", pingId);
            } else {
                log.error("FAILURE: Received unexpected message type {} or mismatched ID {} for PING ID {}",
                        pongResponse.getMessageType(), pongResponse.getRequestID(), pingId);
            }

        } catch (TimeoutException e) {
            log.error("FAILURE: Timed out waiting for HEARTBEAT_PONG for ID: {}. Error: {}", pingId, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("FAILURE: Ping test interrupted for ID: {}. Error: {}", pingId, e.getMessage());
        } catch (Exception e) {
            log.error("FAILURE: An unexpected error occurred during Ping/Pong test for ID {}: {}", pingId, e.getMessage(), e);
        }
    }
}