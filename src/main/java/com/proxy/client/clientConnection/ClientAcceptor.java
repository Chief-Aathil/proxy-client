package com.proxy.client.clientConnection;

import com.proxy.client.task.ProxyRequestTask;
import com.proxy.client.utils.ByteStreamUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

//@RequiredArgsConstructor
@Service
public class ClientAcceptor {

    private ServerSocket proxyServerSocket;
    private final int proxyPort = 8080;
    private final int clientSocketTimeout = 5000;
    private ExecutorService clientConnectionPool; // Manages threads for incoming client connections

    private final BlockingQueue<ProxyRequestTask> requestQueue ;

    @Autowired
    public ClientAcceptor(BlockingQueue<ProxyRequestTask> requestQueue) {
        this.requestQueue = requestQueue;
    }

    @PostConstruct
    public void init() {
        clientConnectionPool = Executors.newFixedThreadPool(5); // Adjust as needed, or use fixed pool

        try {
            proxyServerSocket = new ServerSocket(proxyPort);
            // Set a timeout for accept() to allow graceful shutdown
            proxyServerSocket.setSoTimeout(1000); // 1-second timeout
            System.out.println("Proxy-Client listening for incoming connections on port " + proxyPort);

            // Start a dedicated thread to continuously accept client connections
            new Thread(this::acceptClientConnection, "ClientAcceptorThread").start();

        } catch (IOException e) {
            System.err.println("FATAL: Could not start Proxy-Client ServerSocket on port " + proxyPort + ": " + e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        System.out.println("ClientAcceptor shutting down...");
        try {
            if (proxyServerSocket != null && !proxyServerSocket.isClosed()) {
                proxyServerSocket.close();
                System.out.println("Proxy-Client ServerSocket closed.");
            }
        } catch (IOException e) {
            System.err.println("Error closing Proxy-Client ServerSocket: " + e.getMessage());
        }

        // Shut down the client connection thread pool gracefully
        if (clientConnectionPool != null) {
            clientConnectionPool.shutdown();// Initiates graceful shutdown
            System.out.println("Attempting to shut down client connection pool...");
            try {
                if (!clientConnectionPool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    // If not all tasks completed in time, force shutdown
                    clientConnectionPool.shutdownNow();
                    System.err.println("Client connection pool did not terminate gracefully. Forced shutdown.");
                } else {
                    System.out.println("Client connection pool shut down gracefully.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Client connection pool shutdown interrupted.");
                clientConnectionPool.shutdownNow();
            }
        }
    }


    private void acceptClientConnection() {
        while (!proxyServerSocket.isClosed()) {
            try {
                Socket clientSocket = proxyServerSocket.accept();
                clientSocket.setSoTimeout(clientSocketTimeout);
                System.out.println("Accepted new client connection from " + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort());
                clientConnectionPool.submit(new ClientConnectionHandler(clientSocket, requestQueue));

            } catch (SocketTimeoutException e) {
                // This is expected during graceful shutdown when accept() times out
                //System.out.println("ServerSocket accept timed out ...");
            } catch (IOException e) {
                if (!proxyServerSocket.isClosed()) {
                    System.err.println("Error accepting client connection: " + e.getMessage());
                }
                // If an error occurs, wait a bit before trying to accept again
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // --- Inner class for handling individual client connections ---
    private static class ClientConnectionHandler implements Runnable {
        private final Socket clientSocket;
        private final BlockingQueue<ProxyRequestTask> requestQueue;

        public ClientConnectionHandler(Socket clientSocket, BlockingQueue<ProxyRequestTask> requestQueue) {
            this.clientSocket = clientSocket;
            this.requestQueue = requestQueue;
        }

        @Override
        public void run() {
            String clientInfo = clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();
            System.out.println("ClientHandler started for: " + clientInfo);

            try (InputStream clientIn = clientSocket.getInputStream();
                 OutputStream clientOut = clientSocket.getOutputStream()) {

                // Loop for HTTP Keep-Alive connections
                while (!clientSocket.isClosed()) {
                    String requestLine = null;
                    try {
                        requestLine = readRequestLine(clientIn);
                    } catch (SocketTimeoutException ste) {
                        System.out.println("Client idle timeout for " + clientInfo + ". Closing connection.");
                        break; // Exit loop, close client connection due to inactivity
                    } catch (IOException ioe) {
                        System.out.println("Client " + clientInfo + " disconnected or I/O error during request line read: " + ioe.getMessage());
                        break; // Client likely closed connection
                    }

                    if (requestLine == null || requestLine.isEmpty()) {
                        System.out.println("Client " + clientInfo + " sent empty request line or closed connection.");
                        break; // Client closed connection or sent empty line
                    }
                    System.out.println("Received from " + clientInfo + ": " + requestLine);

                    if (requestLine.startsWith("CONNECT ")) {
                        // Phase 3: HTTPS CONNECT handling (detailed implementation later)
                        System.out.println("HTTPS CONNECT request received from " + clientInfo + ". (To be implemented in Phase 3)");
                        // For now, send a 501 Not Implemented response and close
                        sendErrorResponse(clientOut, 501, "Not Implemented: HTTPS CONNECT Tunneling.");
                        break; // Close connection after sending error
                    } else {
                        // Phase 2: HTTP GET/POST handling
                        byte[] rawHttpRequestBytes = null;
                        try {
                            rawHttpRequestBytes = readFullHttpRequest(clientIn, requestLine);
                        } catch (SocketTimeoutException ste) {
                            System.out.println("Client idle timeout for " + clientInfo + " during request body read. Closing connection.");
                            break;
                        } catch (IOException ioe) {
                            System.out.println("Client " + clientInfo + " disconnected or I/O error during full request read: " + ioe.getMessage());
                            break;
                        }

                        if (rawHttpRequestBytes == null) {
                            System.out.println("Failed to read full HTTP request from " + clientInfo + " or client closed.");
                            break; // Error during reading, close connection
                        }

                        // Create a CompletableFuture that this specific ClientHandler will wait on for its response
                        CompletableFuture<byte[]> httpResponseFuture = new CompletableFuture<>();
                        ProxyRequestTask task = new ProxyRequestTask(clientIn, clientOut, rawHttpRequestBytes, httpResponseFuture);

                        try {
                            // Put the task into the queue (this will block if the queue is full - applies backpressure)
                            requestQueue.put(task);
                            System.out.println("Task added to queue for " + clientInfo + ": " + task.type);

                            // Wait for the response to be completed by the ServerConnMgr.
                            // This blocks the current ClientHandler thread until the response is ready.
                            byte[] rawHttpResponse = httpResponseFuture.get(); // Blocks here

                            System.out.println("Received response from ServerConnMgr for " + clientInfo + ". Sending to client.");

                            // Send the response back to the client browser
                            clientOut.write(rawHttpResponse);
                            clientOut.flush();
                            System.out.println("Response sent to " + clientInfo + ".");

                            // Check if the response or original request indicated 'Connection: close'
                            // For simplicity here, we continue the loop assuming keep-alive unless an error occurred.
                            // A robust proxy would parse response headers for 'Connection: close'.

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); // Restore interrupted status
                            System.err.println("Client handler for " + clientInfo + " interrupted while putting/getting from queue: " + e.getMessage());
                            sendErrorResponse(clientOut, 503, "Proxy busy or interrupted. Please try again later.");
                            break; // Exit loop, close client connection
                        } catch (java.util.concurrent.ExecutionException e) {
                            // This catches exceptions from httpResponseFuture.get() (e.g., if completeExceptionally was called)
                            System.err.println("Error processing request for " + clientInfo + " from ServerConnMgr: " + e.getCause().getMessage());
                            sendErrorResponse(clientOut, 500, "Proxy processing error: " + e.getCause().getMessage());
                            break; // Exit loop, close client connection
                        } catch (Exception e) { // Catch any other unexpected errors during processing or writing
                            System.err.println("Unhandled error for " + clientInfo + ": " + e.getMessage());
                            e.printStackTrace();
                            sendErrorResponse(clientOut, 500, "Proxy Internal Error.");
                            break; // Exit loop, close client connection
                        }
                    }
                } // End of while loop for keep-alive
            } catch (IOException e) {
                // This typically means the client abruptly disconnected.
                System.err.println("Client connection I/O error for " + clientInfo + ": " + e.getMessage());
            } finally {
                // Ensure the client socket is closed when the handler finishes
                try {
                    if (clientSocket != null && !clientSocket.isClosed()) {
                        clientSocket.close();
                        System.out.println("Client socket closed for " + clientInfo + ".");
                    }
                } catch (IOException e) {
                    System.err.println("Error closing client socket for " + clientInfo + ": " + e.getMessage());
                }
            }
        }

        // --- Helper methods for HTTP request parsing ---

        /**
         * Reads a single line (up to \n or EOF) from the InputStream.
         * Returns null if EOF is reached before any data.
         */
        private String readRequestLine(InputStream in) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                bos.write(c);
            }
            if (c == -1 && bos.size() == 0) {
                return null;
            }
            // Trim to remove leading/trailing whitespace, including '\r' if present.
            return bos.toString(StandardCharsets.UTF_8).trim();
        }

        /**
         * Reads the full HTTP request (headers and body) given the first line.
         * Handles Content-Length and basic chunked encoding.
         */
        private byte[] readFullHttpRequest(InputStream in, String firstLine) throws IOException {
            ByteArrayOutputStream requestBuffer = new ByteArrayOutputStream();
            requestBuffer.write(firstLine.getBytes(StandardCharsets.UTF_8));
            requestBuffer.write('\r'); // Add back CRLF for the first line
            requestBuffer.write('\n');

            String line;
            int contentLength = -1; // -1 indicates not found or not specified
            boolean isChunked = false;

            // Read headers until an empty line
            while ((line = readRequestLine(in)) != null && !line.isEmpty()) {
                requestBuffer.write(line.getBytes(StandardCharsets.UTF_8));
                requestBuffer.write('\r');
                requestBuffer.write('\n');

                String lowerCaseLine = line.toLowerCase();
                if (lowerCaseLine.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(lowerCaseLine.substring("content-length:".length()).trim());
                } else if (lowerCaseLine.startsWith("transfer-encoding:") && lowerCaseLine.contains("chunked")) {
                    isChunked = true;
                }
            }
            requestBuffer.write('\r'); // End of headers: CRLF
            requestBuffer.write('\n');

            // Read body based on Content-Length or Transfer-Encoding
            if (contentLength != -1) {
                byte[] bodyBuffer = new byte[contentLength];
                ByteStreamUtils.readFully(in, bodyBuffer, 0, contentLength);
                requestBuffer.write(bodyBuffer);
            } else if (isChunked) {
                // Simplified chunked handling: read chunk size, then chunk data, until 0-sized chunk
                System.out.println("ClientConnectionHandler: Handling chunked transfer encoding (simplified).");
                int chunkSize;
                while (true) {
                    String chunkSizeLine = readRequestLine(in);
                    if (chunkSizeLine == null || chunkSizeLine.isEmpty()) {
                        throw new IOException("Malformed chunked encoding: Missing chunk size line.");
                    }
                    try {
                        chunkSize = Integer.parseInt(chunkSizeLine.trim(), 16);
                    } catch (NumberFormatException e) {
                        throw new IOException("Malformed chunked encoding: Invalid chunk size format: " + chunkSizeLine);
                    }

                    if (chunkSize == 0) {
                        // End of chunks, read final CRLF
                        readRequestLine(in); // Read trailing headers (if any) and final CRLF
                        break;
                    }

                    byte[] chunk = new byte[chunkSize];
                    ByteStreamUtils.readFully(in, chunk, 0, chunkSize);
                    requestBuffer.write(chunk);

                    readRequestLine(in); // Read CRLF after chunk data
                }
            }
            // For requests without a body (e.g., GET), no body bytes will be read.
            return requestBuffer.toByteArray();
        }

        /**
         * Sends a basic HTTP error response to the client.
         */
        private void sendErrorResponse(OutputStream out, int statusCode, String message) {
            try {
                String statusText = switch (statusCode) {
                    case 503 -> "Service Unavailable";
                    case 500 -> "Internal Server Error";
                    case 501 -> "Not Implemented";
                    case 400 -> "Bad Request";
                    default -> "Error";
                };

                String httpResponse = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: " + message.length() + "\r\n" +
                        "Connection: close\r\n\r\n" + // Signal to close connection
                        message;
                out.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                out.flush();
                System.err.println("Sent error response " + statusCode + " to client.");
            } catch (IOException e) {
                System.err.println("Error sending error response: " + e.getMessage());
            }
        }
    }
}