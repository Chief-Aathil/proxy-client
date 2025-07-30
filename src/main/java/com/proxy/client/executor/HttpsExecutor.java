package com.proxy.client.executor;

import com.proxy.client.communicator.FramedMessage;
import com.proxy.client.communicator.ProxyClientCommunicator;
import com.proxy.client.task.ProxyRequestTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RequiredArgsConstructor
@Component
public class HttpsExecutor {

    private final ProxyClientCommunicator clientCommunicator;

    /**
     * Executes an HTTPS CONNECT request over the persistent tunnel to the offshore proxy.
     * It waits for a CONTROL_200_OK signal from the server, which indicates
     * the server has successfully established its side of the tunnel to the target.
     */
    public void executeConnect(ProxyRequestTask task) throws Exception {
        if (task.getRequestType() != FramedMessage.MessageType.HTTPS_CONNECT) {
            throw new IllegalArgumentException("HttpsExecutor received non-HTTPS_CONNECT task. Type: " + task.getRequestType());
        }

        UUID requestID = task.getRequestID();
        byte[] connectRequestBytes = task.getRawRequestBytes();

        // This is the CompletableFuture that the HttpsRequestHandler (which called QueueConsumer, which called this executor)
        // is waiting on. It expects a byte[] (empty array for success signal).
        CompletableFuture<byte[]> tunnelReadyFuture = (CompletableFuture<byte[]>) task.getResponseFuture();

        log.debug("HttpsExecutor: Sending HTTPS_CONNECT FramedMessage for ID: {}", requestID);
        FramedMessage connectMessage = new FramedMessage(
                FramedMessage.MessageType.HTTPS_CONNECT,
                requestID,
                connectRequestBytes
        );

        try {
            // Send the CONNECT message and get a CompletableFuture from the communicator.
            // This future will be completed by ProxyClientCommunicator when it receives a FramedMessage
            // with the matching requestID (specifically, a CONTROL_200_OK message).
            CompletableFuture<FramedMessage> responseFromCommunicator = clientCommunicator.sendAndAwaitResponse(connectMessage);

            // Wait for the response from the communicator. This blocks the current thread
            // until the CONTROL_200_OK is received or a timeout occurs.
            responseFromCommunicator.get(60, TimeUnit.SECONDS); // Max 60 seconds for tunnel establishment

            // Once the communicator's future completes (meaning CONTROL_200_OK was received),
            // then complete the task's future with an empty byte array. This signals to the
            // HttpsRequestHandler that the tunnel is ready on the server side.
            tunnelReadyFuture.complete(new byte[0]); // Signal tunnel is ready with an empty byte array
            log.info("HttpsExecutor: Received CONTROL_200_OK for ID: {}. Tunnel established on server side.", requestID);

        } catch (TimeoutException e) {
            log.error("HttpsExecutor: Timeout (60s) waiting for CONTROL_200_OK for ID: {}", requestID);
            // If a timeout occurs, complete the HttpsRequestHandler's future exceptionally.
            tunnelReadyFuture.completeExceptionally(new IOException("HTTPS tunnel establishment timed out."));
            throw e; // Re-throw to propagate the exception up to QueueConsumer
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Re-set the interrupt flag
            log.warn("HttpsExecutor: Interrupted while waiting for CONTROL_200_OK for ID: {}", requestID);
            // If interrupted, complete the HttpsRequestHandler's future exceptionally.
            tunnelReadyFuture.completeExceptionally(new IOException("HTTPS tunnel establishment interrupted."));
            throw e; // Re-throw to propagate the exception up the call stack
        } catch (Exception e) {
            log.error("HttpsExecutor: Error establishing tunnel for ID {}: {}", requestID, e.getMessage(), e);
            // For any other exception, complete the HttpsRequestHandler's future exceptionally.
            tunnelReadyFuture.completeExceptionally(e);
            throw e; // Re-throw to propagate the exception up to QueueConsumer
        }
    }
}
