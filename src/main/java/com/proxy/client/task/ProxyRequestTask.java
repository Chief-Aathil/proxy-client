package com.proxy.client.task;

import com.proxy.client.enums.RequestType;
import lombok.Getter;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

@Getter
public class ProxyRequestTask {

    public final RequestType type;
    public final InputStream clientInputStream;
    public final OutputStream clientOutputStream;
    public final byte[] rawHttpRequestBytes;
    public final String connectTargetHostPort;
    public final CompletableFuture<byte[]> httpResponseFuture;

    public ProxyRequestTask(InputStream clientIn, OutputStream clientOut, byte[] rawHttpRequestBytes, CompletableFuture<byte[]> httpResponseFuture) {
        this.type = RequestType.HTTP;
        this.clientInputStream = clientIn;
        this.clientOutputStream = clientOut;
        this.rawHttpRequestBytes = rawHttpRequestBytes;
        this.httpResponseFuture = httpResponseFuture;
        this.connectTargetHostPort = null; // Not applicable for HTTP
    }

    // Constructor for CONNECT requests (will be used in Phase 3)
    public ProxyRequestTask(InputStream clientIn, OutputStream clientOut, String connectTargetHostPort, CompletableFuture<byte[]> httpResponseFuture) {
        this.type = RequestType.CONNECT;
        this.clientInputStream = clientIn;
        this.clientOutputStream = clientOut;
        this.connectTargetHostPort = connectTargetHostPort;
        this.httpResponseFuture = httpResponseFuture; // Can be used to signal "connection established" with a null byte array
        this.rawHttpRequestBytes = null; // Not applicable for CONNECT
    }

    // You might add toString() for logging purposes
    @Override
    public String toString() {
        if (type == RequestType.HTTP) {
            String requestPreview = rawHttpRequestBytes != null ? new String(rawHttpRequestBytes, 0, Math.min(rawHttpRequestBytes.length, 100)) : "N/A";
            return "ProxyRequestTask{type=HTTP, client=" + clientInputStream + ", requestPreview='" + requestPreview.replace('\n', ' ').replace('\r', ' ').trim() + "'}";
        } else {
            return "ProxyRequestTask{type=CONNECT, client=" + clientInputStream + ", target='" + connectTargetHostPort + "'}";
        }
    }
}