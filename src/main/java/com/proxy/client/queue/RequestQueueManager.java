package com.proxy.client.queue;

import com.proxy.client.model.QueuedHttpRequest;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class RequestQueueManager {
    private final BlockingQueue<QueuedHttpRequest> queue = new LinkedBlockingQueue<>(100); // bounded for backpressure

    public void enqueue(QueuedHttpRequest request) throws InterruptedException {
        queue.put(request);
    }

    public QueuedHttpRequest take() throws InterruptedException {
        return queue.take();
    }
}
