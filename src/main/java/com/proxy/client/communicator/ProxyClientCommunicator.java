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

                    // Attempt to complete a waiting future
                    CompletableFuture<FramedMessage> future = correlationMap.remove(receivedMessage.getRequestID());
                    if (future != null) {
                        future.complete(receivedMessage);
                    } else {
                        // This message was not explicitly awaited via sendAndAwaitResponse.
                        // This will be important for handling server-initiated messages (like PONGs
                        // that aren't part of a direct request-response pair) or HTTPS_DATA frames.
                        // For Phase 1, we might just log it. For later phases, it needs dispatching.
                        log.warn("Received message with no waiting future: {}. Type: {}", receivedMessage.getRequestID(), receivedMessage.getMessageType());
                        // TODO: In later phases, dispatch to appropriate handler (e.g., ClientRequestHandler for HTTPS_DATA)
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

        // Clear any pending futures
        correlationMap.forEach((uuid, future) -> future.cancel(true));
        correlationMap.clear();
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