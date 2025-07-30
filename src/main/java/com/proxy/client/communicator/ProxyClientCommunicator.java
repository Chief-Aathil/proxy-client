package com.proxy.client.communicator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.io.*;
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
public class ProxyClientCommunicator {

    // Queue for outgoing FramedMessages to be sent by the send thread
    private final BlockingQueue<FramedMessage> outgoingQueue = new LinkedBlockingQueue<>();
    // Map to correlate request IDs with CompletableFutures for responses
    private final ConcurrentHashMap<UUID, CompletableFuture<FramedMessage>> correlationMap = new ConcurrentHashMap<>();
    // Map to store browser output streams for active HTTPS tunnels, mapped by requestID
    private final ConcurrentHashMap<UUID, OutputStream> httpsTunnelOutputStreams = new ConcurrentHashMap<>();

    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private ExecutorService executorService;
    private volatile boolean running = false;

    /**
     * Initializes the communicator with the input and output streams of the client socket.
     * This method must be called after a successful socket connection.
     *
     * @param inputStream  The InputStream from the connected socket.
     * @param outputStream The OutputStream from the connected socket.
     */
    public void initialize(InputStream inputStream, OutputStream outputStream) {
        this.dataInputStream = new DataInputStream(inputStream);
        this.dataOutputStream = new DataOutputStream(outputStream);
        this.executorService = Executors.newFixedThreadPool(2); // One for sending, one for receiving
        log.info("ProxyClientCommunicator initialized with socket streams.");
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
     * Registers an OutputStream associated with an active HTTPS tunnel.
     * This stream will be used to write incoming HTTPS_DATA frames to the browser.
     */
    public void registerHttpsTunnelOutputStream(UUID requestID, OutputStream browserOut) {
        httpsTunnelOutputStreams.put(requestID, browserOut);
        log.debug("Registered HTTPS tunnel output stream for ID: {}", requestID);
    }

    /**
     * Removes the OutputStream mapping for a closed HTTPS tunnel.
     * This prevents writing to a closed socket and cleans up resources.
     */
    public void removeHttpsTunnelOutputStream(UUID requestID) {
        OutputStream removed = httpsTunnelOutputStreams.remove(requestID);
        if (removed != null) {
            log.debug("Removed HTTPS tunnel output stream mapping for ID: {}", requestID);
        }
    }

    /**
     * Dispatches received FramedMessages that are not associated with a waiting CompletableFuture.
     * This includes continuous HTTPS_DATA frames.
     *
     * @param message The received FramedMessage.
     */
    private void dispatchReceivedMessage(FramedMessage message) {
        switch (message.getMessageType()) {
            case HTTPS_DATA:
                OutputStream browserOut = httpsTunnelOutputStreams.get(message.getRequestID());
                if (browserOut != null) {
                    try {
                        browserOut.write(message.getPayload());
                        browserOut.flush();
                        log.trace("Wrote {} bytes of HTTPS_DATA to browser for ID: {}", message.getPayload().length, message.getRequestID());
                    } catch (IOException e) {
                        log.warn("Failed to write HTTPS_DATA to browser for ID {}: {}. Browser socket likely closed.", message.getRequestID(), e.getMessage());
                        // No need to explicitly remove from map here, ClientRequestHandler's cleanup will handle it.
                    }
                } else {
                    log.warn("Received HTTPS_DATA for unknown or closed tunnel ID: {}", message.getRequestID());
                }
                break;
            case CONTROL_TUNNEL_CLOSE:
                // Server initiated a tunnel close (e.g., target server closed connection).
                // Remove the mapping and let the ClientRequestHandler detect its own browser socket closure.
                removeHttpsTunnelOutputStream(message.getRequestID());
                log.info("Received CONTROL_TUNNEL_CLOSE from server for ID: {}", message.getRequestID());
                break;
            // Add other message types if needed (e.g., HEARTBEAT_PONG not linked to a request future)
            default:
                log.warn("Received unhandled message type {} with no waiting future for ID: {}", message.getMessageType(), message.getRequestID());
                break;
        }
    }
    /**
     * Adds a FramedMessage to the outgoing queue for sending.
     * This method is non-blocking.
     *
     * @param message The FramedMessage to send.
     * @throws InterruptedException If the thread is interrupted while adding to the queue.
     */
    public void send(FramedMessage message) throws InterruptedException {
        if (!running) {
            log.warn("Communicator is not running. Message not sent: {}", message);
            return;
        }
        outgoingQueue.put(message); // Blocking put if queue is full (unlikely for LinkedBlockingQueue)
        log.debug("Added message to outgoing queue: {}", message);
    }

    /**
     * Sends a FramedMessage and returns a CompletableFuture that will be completed
     * when a response with the matching requestID is received.
     *
     * @param requestMessage The FramedMessage to send.
     * @return A CompletableFuture that will hold the response FramedMessage.
     * @throws InterruptedException If the thread is interrupted while sending the message.
     */
    public CompletableFuture<FramedMessage> sendAndAwaitResponse(FramedMessage requestMessage) throws InterruptedException {
        CompletableFuture<FramedMessage> future = new CompletableFuture<>();
        correlationMap.put(requestMessage.getRequestID(), future);
        send(requestMessage);
        return future;
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
                log.error("Error sending message: {}. Connection likely lost. Shutting down send loop.", e.getMessage());
                running = false; // Signal to stop loops
                // TODO: In Phase 4, notify ProxyClientConnectionManager about connection loss
            } catch (Exception e) {
                log.error("Unexpected error in client send loop: {}", e.getMessage(), e);
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
                    log.error("Received invalid frame length: {}. Connection likely corrupted. Shutting down receive loop.", frameLength);
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

                    // Attempt to complete a waiting future first (for HTTP responses or CONNECT ACKs)
                    CompletableFuture<FramedMessage> future = correlationMap.remove(receivedMessage.getRequestID());
                    if (future != null) {
                        future.complete(receivedMessage);
                    } else {
                        dispatchReceivedMessage(receivedMessage);
                    }
                }
            } catch (IOException e) {
                log.error("Error receiving message: {}. Connection likely lost. Shutting down receive loop.", e.getMessage());
                running = false; // Signal to stop loops
                // TODO: In Phase 4, notify ProxyClientConnectionManager about connection loss
            } catch (Exception e) {
                log.error("Unexpected error in client receive loop: {}", e.getMessage(), e);
            }
        }
        log.info("Client receive loop stopped.");
    }

    /**
     * Shuts down the communicator, stopping threads and closing streams.
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
        httpsTunnelOutputStreams.clear(); // Clear all registered streams
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