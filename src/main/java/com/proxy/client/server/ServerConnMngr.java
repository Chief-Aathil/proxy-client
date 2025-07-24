package com.proxy.client.server;

import com.proxy.client.enums.RequestType;
import com.proxy.client.protocol.ProxyProtocolConstants;
import com.proxy.client.task.ProxyRequestTask;
import com.proxy.client.utils.ByteStreamUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Service
public class ServerConnMngr {

    private final BlockingQueue<ProxyRequestTask> requestQueue;
    private Socket proxyServerConnection;
    private InputStream proxyServerIn;
    private OutputStream proxyServerOut;

    private final String PROXY_SERVER_HOST = "localhost";
    private final int PROXY_SERVER_PORT = 9000; // Proxy-server's custom listener port
    // Regex to extract Host and Port from HTTP request line or Host header
    // Group 1: Hostname, Group 2: Port (optional)
    private static final Pattern HOST_PATTERN = Pattern.compile("Host:\\s*([^:\\s]+)(?::(\\d+))?");
    // Regex to find URL in request line (e.g., GET http://host:port/path HTTP/1.1)
    public static final Pattern URL_PATTERN = Pattern.compile("^(GET|POST|PUT|DELETE|HEAD|OPTIONS)\\s+(https?://[^/\\s]+)(.*)HTTP/1\\.[01]$");


    private volatile boolean running = true;

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
                // Take a task from the queue (blocks if queue is empty)
                task = requestQueue.take();
                System.out.println("Taken task from queue: " + task);
                ensureOffshoreConnection();
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
        String rawRequestString = new String(task.rawHttpRequestBytes, StandardCharsets.UTF_8);
        System.out.println("ServerConnMgr: Processing HTTP request. First line: " + rawRequestString.split("\n")[0].trim());

        // Phase 2: Extract Host and Port for dynamic routing
        Optional<String> hostAndPort = extractHostAndPort(rawRequestString);

        if (hostAndPort.isEmpty()) {
            System.err.println("ServerConnMgr: Could not extract Host and Port from request. Sending 400 Bad Request.");
            task.httpResponseFuture.completeExceptionally(new IllegalArgumentException("Missing or malformed Host header."));
            return;
        }

        String targetHost = hostAndPort.get().split(":")[0];
        int targetPort = (hostAndPort.get().contains(":")) ?
                Integer.parseInt(hostAndPort.get().split(":")[1]) : 80; // Default HTTP port

        System.out.println("ServerConnMgr: Target Host: " + targetHost + ", Target Port: " + targetPort);
        sendConnectRequestToServer(targetHost, targetPort);
        System.out.println("ServerConnMgr: Sent CONNECT request to Proxy-Server for " + targetHost + ":" + targetPort);

        // --- Read CONNECT Response from Proxy-Server ---
        // We expect a CONNECT_SUCCESS (0x10) or CONNECT_FAILED (0x11) response
        int responseType = proxyServerIn.read();
        if (responseType == -1) {
            throw new IOException("Proxy-Server closed connection unexpectedly while waiting for CONNECT response.");
        }
        if (responseType == ProxyProtocolConstants.MSG_TYPE_CONNECT_SUCCESS) {
            System.out.println("ServerConnMgr: Proxy-Server reported CONNECT SUCCESS for " + targetHost + ":" + targetPort);
            // Tunnel established. Now, we send the original HTTP request (0x01) through the tunnel.

            // --- Send Original HTTP Request (0x01) to Proxy-Server (through the tunnel) ---
            sendOriginalHttpRequest(task);
            System.out.println("ServerConnMgr: HTTP Response future completed for task: " + task);
        } else if (responseType == ProxyProtocolConstants.MSG_TYPE_CONNECT_FAILED) {
            handleOriginConnectFailure(task, targetHost, targetPort);
        } else {
            throw new IOException("ServerConnMgr: Unexpected response type from Proxy-Server after CONNECT request: " + responseType);
        }
    }

    private void sendConnectRequestToServer(String targetHost, int targetPort) throws IOException {
        // --- Send CONNECT Request (0x02) to Proxy-Server ---
        // This is the new part for dynamic routing. We ask the proxy-server to establish a tunnel.
        proxyServerOut.write(ProxyProtocolConstants.MSG_TYPE_CONNECT_REQUEST); // Message Type: 0x02 (CONNECT Request)

        // Host Length (short, 2 bytes)
        byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length > Short.MAX_VALUE) {
            throw new IOException("Target host name too long: " + hostBytes.length + " bytes.");
        }
        new DataOutputStream(proxyServerOut).writeShort(hostBytes.length);
        proxyServerOut.write(hostBytes); // Raw Host Bytes

        // Port (int, 4 bytes)
        new DataOutputStream(proxyServerOut).writeInt(targetPort);
        proxyServerOut.flush();
    }

    private void handleOriginConnectFailure(ProxyRequestTask task, String targetHost, int targetPort) throws IOException {
        // Read reason length (short) and reason bytes
        short reasonLength = new DataInputStream(proxyServerIn).readShort();
        byte[] reasonBytes = new byte[reasonLength];
        ByteStreamUtils.readFully(proxyServerIn, reasonBytes, 0, reasonBytes.length);
        String failureReason = new String(reasonBytes, StandardCharsets.UTF_8);

        System.err.println("ServerConnMgr: Proxy-Server reported CONNECT FAILED for " + targetHost + ":" + targetPort + ". Reason: " + failureReason);
        task.httpResponseFuture.completeExceptionally(new IOException("Proxy-Server failed to connect to origin: " + failureReason));
    }

    private void sendOriginalHttpRequest(ProxyRequestTask task) throws IOException {
        proxyServerOut.write(ProxyProtocolConstants.MSG_TYPE_HTTP_REQUEST); // Message Type: 0x01 (HTTP Request)
        new DataOutputStream(proxyServerOut).writeLong(task.rawHttpRequestBytes.length); // Content Length (long)
        proxyServerOut.write(task.rawHttpRequestBytes); // Raw HTTP Request Bytes
        proxyServerOut.flush();
        System.out.println("ServerConnMgr: Sent original HTTP request through tunnel to Proxy-Server. Size: " + task.rawHttpRequestBytes.length);

        // --- Read HTTP Response (0x12) from Proxy-Server (from origin server) ---
        int httpResponseType = proxyServerIn.read();
        if (httpResponseType == -1) {
            throw new IOException("Proxy-Server closed connection unexpectedly while waiting for HTTP response.");
        }
        if (httpResponseType != ProxyProtocolConstants.MSG_TYPE_HTTP_RESPONSE) {
            throw new IOException("Unexpected message type from Proxy-Server after CONNECT success: " + httpResponseType + ". Expected 0x12 (HTTP Response).");
        }

        long responseLength = ByteStreamUtils.readLong(proxyServerIn); // Read Content Length
        byte[] rawHttpResponseBytes = new byte[(int) responseLength];
        ByteStreamUtils.readFully(proxyServerIn, rawHttpResponseBytes, 0, rawHttpResponseBytes.length);

        System.out.println("ServerConnMgr: Received HTTP response from Proxy-Server. Size: " + rawHttpResponseBytes.length);

        task.httpResponseFuture.complete(rawHttpResponseBytes); // Complete the Future
    }

    /**
     * Extracts the Host and Port from an HTTP request.
     * Searches for the Host header. If not found, attempts to parse from the request line (e.g., GET http://host:port/...).
     * Returns "host:port" or "host" if port is default 80.
     */
    private Optional<String> extractHostAndPort(String rawHttpRequest) {
        String[] lines = rawHttpRequest.split("\r?\n"); // Split by CRLF or LF

        // 1. Try to find Host header (most reliable)
        for (String line : lines) {
            Matcher matcher = HOST_PATTERN.matcher(line);
            if (matcher.find()) {
                String host = matcher.group(1);
                String port = matcher.group(2); // Can be null
                if (host != null && !host.isEmpty()) {
                    if (port != null && !port.isEmpty()) {
                        return Optional.of(host + ":" + port);
                    } else {
                        return Optional.of(host + ":80"); // Default to HTTP port 80 if not specified in Host header
                    }
                }
            }
        }

        // 2. If Host header not found, try to parse from the Request Line (e.g., GET http://example.com/ HTTP/1.1)
        if (lines.length > 0) {
            String requestLine = lines[0];
            Optional<String> host = getHostFromRequestLine(requestLine);
            if (Objects.requireNonNull(host).isPresent()) return host;
        }

        return Optional.empty(); // Host header or URL not found
    }

    private static Optional<String> getHostFromRequestLine(String requestLine) {
        Matcher urlMatcher = URL_PATTERN.matcher(requestLine);

        if (urlMatcher.find()) {
            String url = urlMatcher.group(2); // Group 2 is the full URL (e.g., http://example.com:8080)
            try {
                // Extract host and port from URL
                URL parsedUrl = new URL(url);
                String host = parsedUrl.getHost();
                int port = parsedUrl.getPort(); // Returns -1 if no port specified

                if (port == -1) {
                    // Default port based on protocol
                    return Optional.of(host + ":" + (parsedUrl.getProtocol().equalsIgnoreCase("https") ? 443 : 80));
                } else {
                    return Optional.of(host + ":" + port);
                }
            } catch (java.net.MalformedURLException e) {
                System.err.println("ServerConnMgr: Malformed URL in request line: " + url + " - " + e.getMessage());
            }
        }
        return Optional.empty();
    }


//    private void processHttpRequestTask(ProxyRequestTask task) throws IOException {
//        // --- Send HTTP Request to Proxy-Server using custom framing ---
//        // Message Type: 0x01 for HTTP Request
//        proxyServerOut.write(0x01);
//        // Content Length: long (8 bytes)
//        new DataOutputStream(proxyServerOut).writeLong(task.rawHttpRequestBytes.length);
//        // Raw HTTP Request Bytes
//        proxyServerOut.write(task.rawHttpRequestBytes);
//        proxyServerOut.flush();
//        System.out.println("Sent HTTP request frame to Proxy-Server. Size: " + task.rawHttpRequestBytes.length);
//
//        // --- Read HTTP Response from Proxy-Server using custom framing ---
//        int responseType = proxyServerIn.read(); // Read Message Type
//        if (responseType == -1) {
//            throw new IOException("Proxy-Server closed connection unexpectedly while waiting for response.");
//        }
//        if (responseType != 0x01) { // Expecting HTTP Response type (can reuse 0x01 or define new one)
//            throw new IOException("Unexpected message type from Proxy-Server: " + responseType + ". Expected 0x01 (HTTP Response).");
//        }
//
//        long responseLength = ByteStreamUtils.readLong(proxyServerIn); // Read Content Length
//        byte[] rawHttpResponseBytes = new byte[(int) responseLength]; // Cast to int, assuming < 2GB response
//        ByteStreamUtils.readFully(proxyServerIn, rawHttpResponseBytes, 0, rawHttpResponseBytes.length); // Read raw response
//
//        System.out.println("Received HTTP response frame from Proxy-Server. Size: " + rawHttpResponseBytes.length);
//
//        // --- Complete the Future to unblock the ClientHandler ---
//        task.httpResponseFuture.complete(rawHttpResponseBytes);
//        System.out.println("HTTP Response future completed for task: " + task);
//    }

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