package com.proxy.client.model;


import jakarta.servlet.AsyncContext;

public record QueuedHttpRequest(AsyncContext asyncContext) {
}
