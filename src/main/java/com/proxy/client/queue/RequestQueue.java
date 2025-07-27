package com.proxy.client.queue;

import com.proxy.client.task.ProxyRequestTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A central, thread-safe queue for ProxyRequestTask instances.
 * ClientRequestHandlers put tasks into this queue, and a single QueueConsumer
 * takes tasks from it sequentially for processing.
 */
@Component
@Slf4j
public class RequestQueue {

    private final BlockingQueue<ProxyRequestTask> queue;

    public RequestQueue() {
        // Using LinkedBlockingQueue which is an optionally-bounded FIFO queue.
        // For now, unbounded, which means 'put' will not block due to capacity.
        this.queue = new LinkedBlockingQueue<>();
        log.info("RequestQueue initialized.");
    }

    /**
     * Adds a ProxyRequestTask to the end of the queue.
     * This method will block if the queue is capacity-constrained and full
     * (not applicable for unbounded LinkedBlockingQueue unless memory is exhausted).
     *
     * @param task The ProxyRequestTask to add.
     * @throws InterruptedException If the thread is interrupted while waiting.
     */
    public void put(ProxyRequestTask task) throws InterruptedException {
        queue.put(task);
        log.debug("Added task to RequestQueue: {}", task.getRequestID());
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     * This method will block if the queue is empty.
     *
     * @return The ProxyRequestTask at the head of the queue.
     * @throws InterruptedException If the thread is interrupted while waiting.
     */
    public ProxyRequestTask take() throws InterruptedException {
        ProxyRequestTask task = queue.take();
        log.debug("Took task from RequestQueue: {}", task.getRequestID());
        return task;
    }

    /**
     * Returns the number of elements currently in the queue.
     * @return The size of the queue.
     */
    public int size() {
        return queue.size();
    }

    /**
     * Clears all elements from the queue.
     */
    public void clear() {
        queue.clear();
        log.info("RequestQueue cleared.");
    }
}