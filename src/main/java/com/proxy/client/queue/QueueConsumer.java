package com.proxy.client.queue;

import com.proxy.client.executor.HttpExecutor;
import com.proxy.client.executor.HttpsExecutor;
import com.proxy.client.task.ProxyRequestTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class QueueConsumer {

    private final RequestQueue requestQueue;
    private final HttpExecutor httpExecutor;
    private final HttpsExecutor httpsExecutor;

    private ExecutorService consumerExecutor;
    private volatile boolean running = false;
    private Future<?> consumerTask;

    /**
     * Called by Spring after component initialization. Starts the single-threaded queue consumer.
     */
    @PostConstruct
    public void init() {
        log.info("Initializing QueueConsumer.");
        consumerExecutor = Executors.newSingleThreadExecutor();
        running = true;
        consumerTask = consumerExecutor.submit(this::consumeLoop);
    }

    /**
     * The single-threaded loop that takes tasks from the RequestQueue and processes them.
     */
    private void consumeLoop() {
        Thread.currentThread().setName("Client-Queue-Consumer-Thread");
        log.info("Client QueueConsumer loop started.");

        while (running) {
            try {
                ProxyRequestTask task = requestQueue.take(); // Blocks until a task is available

                // Dispatch task based on its type
                switch (task.getRequestType()) {
                    case HTTP_REQUEST:
                        httpExecutor.processHttpRequest(task);
                        break;
                    case HTTPS_CONNECT:
                        httpsExecutor.executeConnect(task);
                        break;
                    default:
                        log.error("Unknown request type received in QueueConsumer: {}. Task ID: {}", task.getRequestType(), task.getRequestID());
                        task.getResponseFuture().completeExceptionally(new IllegalArgumentException("Unknown request type"));
                        break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("QueueConsumer loop interrupted. Shutting down.");
                running = false;
            } catch (Exception e) {
                log.error("Unexpected error in QueueConsumer loop: {}", e.getMessage(), e);
                // Continue running to process other tasks, unless it's a fatal error
            }
        }
        log.info("Client QueueConsumer loop stopped.");
    }

    /**
     * Shuts down the QueueConsumer gracefully.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down QueueConsumer.");
        running = false; // Signal loop to stop

        if (consumerExecutor != null) {
            consumerExecutor.shutdownNow(); // Interrupts the currently blocked take() if any
            try {
                if (!consumerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("QueueConsumer executor did not terminate in time.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("QueueConsumer shutdown interrupted.");
            }
        }
        log.info("QueueConsumer shutdown complete.");
    }
}