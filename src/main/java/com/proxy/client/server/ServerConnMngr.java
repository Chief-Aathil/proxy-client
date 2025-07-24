package com.proxy.client.server;

import com.proxy.client.enums.RequestType;
import com.proxy.client.task.ProxyRequestTask;
import com.proxy.client.utils.ByteStreamUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;

@Service
public class ServerConnMngr {

    private final BlockingQueue<ProxyRequestTask> requestQueue;
    private Socket proxyServerConnection;
    private InputStream proxyServerIn;
    private OutputStream proxyServerOut;

    private final String PROXY_SERVER_HOST = "localhost"; // Or actual offshore IP
    private final int PROXY_SERVER_PORT = 9000; // Proxy-server's custom listener port

    private volatile boolean running = true;

    public ServerConnMngr(BlockingQueue<ProxyRequestTask> requestQueue) {
        this.requestQueue = requestQueue;
    }

    @PostConstruct
    public void init() {
        // Start a dedicated thread to consume tasks from the queue and manage the single connection
        new Thread(this::processRequests, "ServerConnMgrThread").start();
        System.out.println("ServerConnMgrThread started.");
    }

    private void processRequests() {
        while (running) {
            ProxyRequestTask task = null;
            try {
                // 1. Take a task from the queue (blocks if queue is empty)
                task = requestQueue.take();
                System.out.println("Taken task from queue: " + task);

                // 2. Ensure connection to proxy-server is active
                ensureOffshoreConnection();

                // 3. Process the task based on its type
                if (task.type == RequestType.HTTP) {
                    processHttpRequestTask(task);
                } else if (task.type == RequestType.CONNECT) {
                    // Phase 3: HTTPS CONNECT handling (will be implemented later)
                    System.out.println("Skipping CONNECT task for now, as it's not yet implemented in ServerConnMgr.");
                    task.httpResponseFuture.completeExceptionally(new UnsupportedOperationException("HTTPS CONNECT not yet implemented."));
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("ServerConnMgrThread interrupted. Shutting down.");
                running = false;
            } catch (IOException e) {
                System.err.println("Connection to Proxy-Server lost or I/O error: " + e.getMessage());
                closeProxyServerConnection(); // Force close to trigger reconnection on next loop
                if (task != null) {
                    task.httpResponseFuture.completeExceptionally(new IOException("Failed to communicate with proxy server: " + e.getMessage()));
                }
            } catch (Exception e) { // Catch any other unexpected errors during processing
                System.err.println("Unhandled error in ServerConnMgr: " + e.getMessage());
                e.printStackTrace();
                if (task != null) {
                    task.httpResponseFuture.completeExceptionally(e);
                }
            }
        }
        System.out.println("ServerConnMgrThread stopped.");
    }

    private void ensureOffshoreConnection() throws IOException, InterruptedException {
        while (proxyServerConnection == null || proxyServerConnection.isClosed()) {
            System.out.println("Attempting to connect to Proxy-Server at " + PROXY_SERVER_HOST + ":" + PROXY_SERVER_PORT);
            try {
                proxyServerConnection = new Socket(PROXY_SERVER_HOST, PROXY_SERVER_PORT);
                proxyServerIn = proxyServerConnection.getInputStream();
                proxyServerOut = proxyServerConnection.getOutputStream();
                System.out.println("Successfully connected to Proxy-Server.");
            } catch (IOException e) {
                System.err.println("Failed to connect to Proxy-Server: " + e.getMessage() + ". Retrying in 5 seconds...");
                Thread.sleep(5000);
            }
        }
    }

    private void processHttpRequestTask(ProxyRequestTask task) throws IOException {
        // --- Send HTTP Request to Proxy-Server using custom framing ---
        // Message Type: 0x01 for HTTP Request
        proxyServerOut.write(0x01);
        // Content Length: long (8 bytes)
        new DataOutputStream(proxyServerOut).writeLong(task.rawHttpRequestBytes.length);
        // Raw HTTP Request Bytes
        proxyServerOut.write(task.rawHttpRequestBytes);
        proxyServerOut.flush();
        System.out.println("Sent HTTP request frame to Proxy-Server. Size: " + task.rawHttpRequestBytes.length);

        // --- Read HTTP Response from Proxy-Server using custom framing ---
        int responseType = proxyServerIn.read(); // Read Message Type
        if (responseType == -1) {
            throw new IOException("Proxy-Server closed connection unexpectedly while waiting for response.");
        }
        if (responseType != 0x01) { // Expecting HTTP Response type (can reuse 0x01 or define new one)
            throw new IOException("Unexpected message type from Proxy-Server: " + responseType + ". Expected 0x01 (HTTP Response).");
        }

        long responseLength = ByteStreamUtils.readLong(proxyServerIn); // Read Content Length
        byte[] rawHttpResponseBytes = new byte[(int) responseLength]; // Cast to int, assuming < 2GB response
        ByteStreamUtils.readFully(proxyServerIn, rawHttpResponseBytes, 0, rawHttpResponseBytes.length); // Read raw response

        System.out.println("Received HTTP response frame from Proxy-Server. Size: " + rawHttpResponseBytes.length);

        // --- Complete the Future to unblock the ClientHandler ---
        task.httpResponseFuture.complete(rawHttpResponseBytes);
        System.out.println("HTTP Response future completed for task: " + task);
    }

    private void closeProxyServerConnection() {
        try {
            if (proxyServerConnection != null && !proxyServerConnection.isClosed()) {
                proxyServerConnection.close();
                System.out.println("Closed connection to Proxy-Server.");
            }
        } catch (IOException e) {
            System.err.println("Error closing connection to Proxy-Server: " + e.getMessage());
        } finally {
            proxyServerConnection = null;
            proxyServerIn = null;
            proxyServerOut = null;
        }
    }

    @PreDestroy
    public void destroy() {
        running = false;
        closeProxyServerConnection();
        System.out.println("ServerConnMgr shutting down.");
    }
}