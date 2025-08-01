package com.proxy.client.queue;

import com.proxy.client.task.ProxyRequestTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A central, thread-safe queue for ProxyRequestTask instances.
 * ClientRequestHandlers put tasks into this queue, and a single QueueConsumer
 * takes tasks from it sequentially for processing.
 */
@Component
@Slf4j
public class RequestQueue {

    // The queue for sequential processing of requests
    private final BlockingQueue<ProxyRequestTask> queue = new LinkedBlockingQueue<>();

    // A map to allow quick lookup and cancellation of tasks by their requestID
    private final ConcurrentHashMap<UUID, ProxyRequestTask> activeTasks = new ConcurrentHashMap<>();

    /**
     * Adds a ProxyRequestTask to the queue and registers it in the activeTasks map.
     * This method is blocking if the queue is capacity-limited (though LinkedBlockingQueue is unbounded by default).
     *
     * @param task The ProxyRequestTask to add.
     * @throws InterruptedException If the thread is interrupted while adding the task.
     */
    public void put(ProxyRequestTask task) throws InterruptedException {
        activeTasks.put(task.getRequestID(), task);
        queue.put(task);
        log.debug("Added task with ID {} to queue. Queue size: {}", task.getRequestID(), queue.size());
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     * Also removes the task from the activeTasks map.
     * This method will block if the queue is empty.
     *
     * @return The ProxyRequestTask at the head of the queue.
     * @throws InterruptedException If the thread is interrupted while waiting.
     */
    public ProxyRequestTask take() throws InterruptedException {
        ProxyRequestTask task = queue.take();
        activeTasks.remove(task.getRequestID()); // Remove from map once taken for processing
        log.debug("Took task with ID {} from queue. Remaining active tasks: {}", task.getRequestID(), activeTasks.size());
        return task;
    }

    /**
     * Attempts to cancel a specific request by its ID.
     * This method finds the task in the activeTasks map and completes its
     * CompletableFuture exceptionally, unblocking the waiting ClientRequestHandler.
     * It also attempts to remove the task from the queue if it hasn't been taken yet.
     *
     * @param requestID The UUID of the request to cancel.
     */
    public void cancelRequest(UUID requestID) {
        ProxyRequestTask task = activeTasks.remove(requestID); // Remove from map
        if (task != null) {
            log.warn("Attempting to cancel request with ID: {}. Completing future exceptionally.", requestID);
            task.getResponseFuture().completeExceptionally(new IOException("Request cancelled by proxy server (tunnel closed)."));

            // Attempt to remove from the queue if it's still there and hasn't been taken by QueueConsumer yet.
            // This is a best-effort removal as `remove` can be slow for large queues and may not find it
            // if it's already being processed or about to be taken.
            boolean removedFromQueue = queue.remove(task);
            if (removedFromQueue) {
                log.debug("Successfully removed cancelled task with ID {} from queue.", requestID);
            } else {
                log.debug("Cancelled task with ID {} was not found in queue (likely already taken for processing).", requestID);
            }
        } else {
            log.debug("Attempted to cancel non-existent or already processed request with ID: {}", requestID);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down RequestQueue. Clearing all pending tasks.");
        // Complete any pending futures exceptionally to unblock waiting ClientRequestHandlers
        activeTasks.forEach((id, task) -> {
            task.getResponseFuture().completeExceptionally(new IOException("Proxy shutting down."));
            log.warn("Failing pending request future {} due to shutdown.", id);
        });
        activeTasks.clear();
        queue.clear();
        log.info("RequestQueue shutdown complete.");
    }
}