package com.proxy.client.controller;

import com.proxy.client.model.QueuedHttpRequest;
import com.proxy.client.queue.RequestQueueManager;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProxyController {

    private final RequestQueueManager queueManager;

    public ProxyController(RequestQueueManager queueManager) {
        this.queueManager = queueManager;
    }

    @RequestMapping("/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws Exception {
        // Enable async processing
        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0); // disable timeout; we’ll manage it manually

        queueManager.enqueue(new QueuedHttpRequest(asyncContext));
    }
}
