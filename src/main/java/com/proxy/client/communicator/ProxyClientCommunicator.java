package com.proxy.client.communicator;

import com.proxy.client.queue.RequestQueue;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProxyClientCommunicator {

    private final RequestQueue requestQueue;
    // Queue for outgoing FramedMessages to be sent by the send thread
    private final BlockingQueue<FramedMessage> outgoingQueue = new LinkedBlockingQueue<>();
    // Map to correlate request IDs with CompletableFutures for responses
    private final ConcurrentHashMap<UUID, CompletableFuture<FramedMessage>> correlationMap = new ConcurrentHashMap<>();
    // Map to store browser output streams for active HTTPS tunnels, mapped by requestID
    private final ConcurrentHashMap<UUID, OutputStream> httpsTunnelOutputStreamsMap = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private ExecutorService executorService;
    private Runnable connectionLossCallback;

    /**
     * Initializes the communicator with the socket to the offshore proxy server.
     * This method must be called after a successful socket connection.
     *
     * @param proxyServerClientSocket The connected socket to the offshore proxy server.
     * @param connectionLossCallback A callback to be invoked if the connection is lost.
     */
    public void initialize(Socket proxyServerClientSocket, Runnable connectionLossCallback) throws IOException {
        this.dataInputStream = new DataInputStream(proxyServerClientSocket.getInputStream());
        this.dataOutputStream = new DataOutputStream(proxyServerClientSocket.getOutputStream());
        this.executorService = Executors.newFixedThreadPool(2); // One for sending, one for receiving
        this.connectionLossCallback = connectionLossCallback;
        log.info("ProxyClientCommunicator initialized with proxy server socket streams.");
    }

    /**
     * Starts the dedicated send and receive threads.
     */
    public void start() {
        if (dataInputStream == null || dataOutputStream == null) {
            log.error("Communicator not initialized. Cannot start threads.");
            return;
        }
        running = true;
        executorService.submit(this::sendLoop);
        executorService.submit(this::receiveLoop);
        log.info("ProxyClientCommunicator send and receive loops started.");
    }

    /**
     * Adds a FramedMessage to the outgoing queue for sending to the offshore proxy server.
     * This method is non-blocking.
     *
     * @param message The FramedMessage to send.
     * @throws InterruptedException If the thread is interrupted while adding to the queue.
     */
    public void send(FramedMessage message) throws InterruptedException, IOException {
        if (!running) {
            log.warn("Communicator is not running. Message not sent: {}", message);
            throw new IOException("Communicator not running, connection likely lost.");
        }
        outgoingQueue.put(message); // Blocking if queue is full
        log.debug("Added message to outgoing queue: {}", message);
    }

    /**
     * Sends a FramedMessage and returns a CompletableFuture that will be completed
     * when a response with the matching requestID is received.
     * This is used for HTTP responses and CONTROL_200_OK for HTTPS CONNECT.
     *
     * @throws InterruptedException If the thread is interrupted while sending the message.
     * @throws IOException If the communicator is not running.
     */
    public CompletableFuture<FramedMessage> sendAndAwaitResponse(FramedMessage requestMessage) throws InterruptedException, IOException {
        CompletableFuture<FramedMessage> future = new CompletableFuture<>();
        correlationMap.put(requestMessage.getRequestID(), future);
        try {
            send(requestMessage);
        } catch (IOException e) {
            correlationMap.remove(requestMessage.getRequestID());
            future.completeExceptionally(e);
            throw e;
        }
        return future;
    }

    /**
     * Registers an OutputStream for an HTTPS tunnel, allowing HttpsRequestHandler
     * to directly write data meant for the offshore proxy server.
     *
     * @param requestID The unique ID of the HTTPS tunnel.
     * @param outputStream The OutputStream associated with the browser connection.
     */
    public void registerHttpsTunnelOutputStream(UUID requestID, OutputStream outputStream) {
        httpsTunnelOutputStreamsMap.put(requestID, outputStream);
        log.debug("Registered HTTPS tunnel output stream for ID: {}", requestID);
    }

    /**
     * Removes the OutputStream for an HTTPS tunnel.
     *
     * @param requestID The unique ID of the HTTPS tunnel.
     */
    public void removeHttpsTunnelOutputStream(UUID requestID) {
        httpsTunnelOutputStreamsMap.remove(requestID);
        log.debug("Removed HTTPS tunnel output stream for ID: {}", requestID);
    }

    /**
     * The main loop for sending messages from the outgoing queue.
     */
    private void sendLoop() {
        Thread.currentThread().setName("Client-Send-Thread");
        log.info("Client send loop started.");
        while (running) {
            try {
                FramedMessage message = outgoingQueue.take(); // Blocks until a message is available
                byte[] bytes = message.toBytes();
                dataOutputStream.writeInt(bytes.length); // Write length of the framed message
                dataOutputStream.write(bytes);           // Write the framed message bytes
                dataOutputStream.flush();
                log.debug("Sent message: {}", message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Client send loop interrupted. Shutting down.");
                running = false;
            } catch (IOException e) {
                log.error("Error sending message: {}. Connection to proxy server lost. Shutting down send loop.", e.getMessage());
                handleConnectionLoss();
                running = false;
            } catch (Exception e) {
                log.error("Unexpected error in client send loop: {}", e.getMessage(), e);
                handleConnectionLoss();
            }
        }
        log.info("Client send loop stopped.");
    }

    /**
     * The main loop for receiving messages from the input stream.
     */
    private void receiveLoop() {
        Thread.currentThread().setName("Client-Receive-Thread");
        log.info("Client receive loop started.");
        while (running) {
            try {
                // Read the length of the incoming framed message first
                int frameLength = dataInputStream.readInt();
                if (frameLength < 0) {
                    log.error("Received invalid frame length: {}. Connection to proxy server corrupted. Shutting down receive loop.", frameLength);
                    handleConnectionLoss();
                    running = false;
                    break;
                }
                // Read the actual framed message bytes
                byte[] frameBytes = new byte[frameLength];
                dataInputStream.readFully(frameBytes);

                // Deserialize the framed message
                try (ByteArrayInputStream bis = new ByteArrayInputStream(frameBytes);
                     DataInputStream dis = new DataInputStream(bis)) {
                    FramedMessage receivedMessage = FramedMessage.fromStream(dis);
                    log.debug("Received message: {}", receivedMessage);

                    dispatchReceivedMessage(receivedMessage);
                }
            } catch (IOException e) {
                log.error("Error receiving message: {}. Connection to proxy server lost. Shutting down receive loop.", e.getMessage());
                handleConnectionLoss();
                running = false;
            } catch (Exception e) {
                log.error("Unexpected error in client receive loop: {}", e.getMessage(), e);
                handleConnectionLoss();
            }
        }
        log.info("Client receive loop stopped.");
    }

    /**
     * Dispatches received FramedMessages to the appropriate CompletableFuture or handler.
     * This is the central routing logic for all incoming messages from the server.
     *
     * @param message The received FramedMessage.
     */
    private void dispatchReceivedMessage(FramedMessage message) {
        CompletableFuture<FramedMessage> future = correlationMap.remove(message.getRequestID());
        if (future != null) {
            future.complete(message);
            log.debug("Completed pending future for ID: {} (Type: {})", message.getRequestID(), message.getMessageType());
        } else {
            // No waiting future, check for specific message types that are not direct responses
            switch (message.getMessageType()) {
                case HTTPS_DATA -> dispatchHttpsData(message);
                case CONTROL_TUNNEL_CLOSE -> handleTunnelClose(message);

                // HEARTBEAT_PONG is handled by correlationMap in ProxyClientConnectionManager.sendAndAwaitResponse
                // No other message types are expected without a pending future.
                default ->
                        log.warn("Received unhandled message type: {} for ID: {}. No pending future found. Discarding.", message.getMessageType(), message.getRequestID());
            }
        }
    }

    private void handleTunnelClose(FramedMessage message) {
        log.info("Received CONTROL_TUNNEL_CLOSE for ID: {}. Cleaning up browser connection.", message.getRequestID());
        // When server signals tunnel close, remove the browser OutputStream.
        // The corresponding ClientRequestHandler/HttpsRequestHandler should already be closing
        // the browser socket due to its own lifecycle or this signal.
        OutputStream closedBrowserOut = httpsTunnelOutputStreamsMap.remove(message.getRequestID());
        if (closedBrowserOut != null) {
            try {
                // Attempt to close the stream if it's still open and we manage it directly here
                // In this design, ClientRequestHandler manages closing the actual browser socket.
                // This line might be redundant or cause errors if socket is already closed by handler.
                // Better to just remove the mapping and let handler handle actual socket closure.
                // ((Socket) ((FilterOutputStream) closedBrowserOut).getOut()).close(); // If we could get the underlying socket.
            } catch (Exception e) {
                 log.debug("Error attempting to close browser output stream after CONTROL_TUNNEL_CLOSE: {}", e.getMessage());
            }
        }
        // Inform the RequestQueue that this request is completed/cancelled, if it's waiting
        requestQueue.cancelRequest(message.getRequestID());
    }

    private void dispatchHttpsData(FramedMessage message) {
        // If it's HTTPS_DATA, write directly to the browser's OutputStream
        OutputStream browserOut = httpsTunnelOutputStreamsMap.get(message.getRequestID());
        if (browserOut != null) {
            try {
                browserOut.write(message.getPayload());
                browserOut.flush();
                log.trace("Wrote {} bytes of HTTPS_DATA to browser for ID: {}", message.getPayload().length, message.getRequestID());
            } catch (IOException e) {
                log.warn("Failed to write HTTPS_DATA to browser for ID {}: {}. Browser connection likely closed.", message.getRequestID(), e.getMessage());
                // If browser connection is lost, signal the server to close its side of the tunnel
                try {
                    send(new FramedMessage(FramedMessage.MessageType.CONTROL_TUNNEL_CLOSE, message.getRequestID(), new byte[0]));
                } catch (InterruptedException | IOException sendEx) {
                    Thread.currentThread().interrupt();
                    log.error("Error sending CONTROL_TUNNEL_CLOSE after browser write failure for ID {}: {}", message.getRequestID(), sendEx.getMessage());
                } finally {
                    httpsTunnelOutputStreamsMap.remove(message.getRequestID()); // Clean up local map
                }
            }
        } else {
            log.warn("Received HTTPS_DATA for unknown or already closed tunnel ID: {}. Discarding.", message.getRequestID());
            // If we receive data for a tunnel we don't know about, signal server to clean up
            try {
                send(new FramedMessage(FramedMessage.MessageType.CONTROL_TUNNEL_CLOSE, message.getRequestID(), new byte[0]));
            } catch (InterruptedException | IOException sendEx) {
                Thread.currentThread().interrupt();
                log.error("Error sending CONTROL_TUNNEL_CLOSE for unknown HTTPS_DATA: {}", sendEx.getMessage());
            }
        }
    }

    /**
     * Handles connection loss by calling the registered callback and failing all pending futures.
     */
    private void handleConnectionLoss() {
        if (running) { // Prevent multiple loss notifications if already shutting down
            running = false; // Mark as not running immediately
            log.error("ProxyClientCommunicator: Connection to server lost. Notifying manager and failing pending operations.");
            if (connectionLossCallback != null) {
                connectionLossCallback.run(); // Changed to .run()
            }

            // Fail all pending CompletableFutures for HTTP requests
            correlationMap.forEach((requestID, future) -> {
                future.completeExceptionally(new IOException("Connection to proxy server lost."));
                log.warn("Failing HTTP request future {} due to connection loss.", requestID);
            });
            correlationMap.clear();

            // Clear all HTTPS tunnel output streams. The HttpsRequestHandler should detect write failures.
            httpsTunnelOutputStreamsMap.clear(); // This helps in preventing writes to dead streams
            log.warn("Cleared all active HTTPS tunnel output streams due to connection loss.");
        }
    }

    /**
     * Checks if the communicator's loops are actively running.
     * @return true if running, false otherwise.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Shuts down the communicator gracefully.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ProxyClientCommunicator.");
        running = false; // Signal threads to stop

        // Interrupt threads and wait for termination
        if (executorService != null) {
            executorService.shutdownNow(); // Interrupts running tasks
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("ProxyClientCommunicator executor did not terminate in time.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Shutdown interrupted.");
            }
        }

        // Clear any pending futures and tunnel streams
        correlationMap.forEach((uuid, future) -> future.cancel(true));
        correlationMap.clear();
        httpsTunnelOutputStreamsMap.clear(); // Clear all registered streams
        outgoingQueue.clear();

        // Close streams
        try {
            if (dataInputStream != null) dataInputStream.close();
            if (dataOutputStream != null) dataOutputStream.close();
        } catch (IOException e) {
            log.error("Error closing communicator streams: {}", e.getMessage());
        }
        log.info("ProxyClientCommunicator shutdown complete.");
    }
}