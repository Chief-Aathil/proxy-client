package com.proxy.client.executor;

import com.proxy.client.communicator.FramedMessage;
import com.proxy.client.communicator.ProxyClientCommunicator;
import com.proxy.client.task.ProxyRequestTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
@RequiredArgsConstructor
public class HttpExecutor {

    private final ProxyClientCommunicator communicator;

    /**
     * Processes an HTTP request by framing it and sending it through the tunnel.
     * It then waits for the corresponding HTTP_RESPONSE from the tunnel.
     *
     * @param task The ProxyRequestTask containing the raw HTTP request and response future.
     */
    public void processHttpRequest(ProxyRequestTask task) {
        FramedMessage httpRequestMessage = new FramedMessage(
                FramedMessage.MessageType.HTTP_REQUEST,
                task.getRequestID(),
                task.getRawRequestBytes()
        );
        log.debug("HttpExecutor sending HTTP_REQUEST for ID: {}", task.getRequestID());
        try {
            CompletableFuture<FramedMessage> responseFromTunnelFuture = communicator.sendAndAwaitResponse(httpRequestMessage);

            // Attach a callback to handle the response when it arrives from the tunnel
            responseFromTunnelFuture.whenComplete((tunnelResponse, throwable) -> {
                if (throwable != null) {
                    log.error("Error receiving HTTP_RESPONSE for ID {}: {}", task.getRequestID(), throwable.getMessage());
                    task.getResponseFuture().completeExceptionally(
                            new RuntimeException("Tunnel communication error: " + throwable.getMessage(), throwable));
                } else if (tunnelResponse == null) {
                    // This case should ideally not happen if throwable is null, but as a safeguard
                    log.error("Received null response from tunnel for ID: {}", task.getRequestID());
                    task.getResponseFuture().completeExceptionally(new NullPointerException("Null response from tunnel"));
                } else if (tunnelResponse.getMessageType() == FramedMessage.MessageType.HTTP_RESPONSE) {
                    // Success: received the expected HTTP_RESPONSE
                    log.debug("Received HTTP_RESPONSE for ID: {}", task.getRequestID());
                    task.getResponseFuture().complete(tunnelResponse.getPayload());
                } else {
                    // Received an unexpected message type from the tunnel
                    log.error("Received unexpected message type '{}' for ID {}. Expected HTTP_RESPONSE.",
                            tunnelResponse.getMessageType(), task.getRequestID());
                    task.getResponseFuture().completeExceptionally(
                            new IllegalStateException("Unexpected message type from tunnel: " + tunnelResponse.getMessageType()));
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("HttpExecutor interrupted while sending HTTP_REQUEST for ID {}: {}", task.getRequestID(), e.getMessage());
            task.getResponseFuture().completeExceptionally(new RuntimeException("HttpExecutor interrupted", e));
        } catch (Exception e) {
            log.error("Failed to send HTTP_REQUEST for ID {}: {}", task.getRequestID(), e.getMessage(), e);
            task.getResponseFuture().completeExceptionally(new RuntimeException("Failed to send HTTP_REQUEST", e));
        }
    }
}