package com.proxy.client.handler;

import com.proxy.client.communicator.FramedMessage;
import com.proxy.client.queue.RequestQueue;
import com.proxy.client.task.ProxyRequestTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class ClientRequestHandler implements Runnable {

    private final Socket clientSocket;
    private final RequestQueue requestQueue;

    @Override
    public void run() {
        UUID requestID = UUID.randomUUID();
        log.info("Handling new client connection from {} with ID: {}", clientSocket.getInetAddress().getHostAddress(), requestID);

        InputStream browserIn = null;
        OutputStream browserOut = null;

        try {
            clientSocket.setSoTimeout(30000); // Set a read timeout for the browser socket (30 seconds)
            browserIn = clientSocket.getInputStream();
            browserOut = clientSocket.getOutputStream();

            // 1. Read the full raw HTTP request from the browser socket
            byte[] rawRequestBytes = readHttpRequest(browserIn);
            if (rawRequestBytes.length == 0) {
                log.warn("Empty request received from {}. Closing connection.", clientSocket.getInetAddress());
                return;
            }

            // For simplicity, check if it's an HTTP GET/POST for now.
            // In Phase 3, we'll handle CONNECT for HTTPS.
            String requestLine = new String(rawRequestBytes, 0, Math.min(rawRequestBytes.length, 256), StandardCharsets.ISO_8859_1).split("\r\n")[0];
            if (!(requestLine.startsWith("GET ") || requestLine.startsWith("POST ") || requestLine.startsWith("PUT ") || requestLine.startsWith("DELETE ") || requestLine.startsWith("HEAD "))) {
                log.warn("Unsupported HTTP method or non-HTTP request received: '{}'. Closing connection for ID: {}", requestLine, requestID);
                sendBadRequestResponse(browserOut); // Send 400 Bad Request
                return;
            }

            // 2. Create ProxyRequestTask with CompletableFuture
            CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
            ProxyRequestTask task = new ProxyRequestTask(
                    requestID,
                    FramedMessage.MessageType.HTTP_REQUEST, // Explicitly HTTP_REQUEST for this phase
                    rawRequestBytes,
                    clientSocket,
                    responseFuture
            );

            // 3. Add to RequestQueue
            requestQueue.put(task);
            log.debug("Enqueued HTTP request with ID: {}", requestID);

            // 4. Wait on CompletableFuture for the response (blocking this handler thread)
            byte[] responseBytes = responseFuture.get(60, TimeUnit.SECONDS); // Max 60 seconds to get response from offshore

            // 5. On completion, write response back to browser
            browserOut.write(responseBytes);
            browserOut.flush();
            log.info("Successfully handled and responded to HTTP request ID: {}", requestID);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("ClientRequestHandler for ID {} interrupted. Closing connection.", requestID);
            // Optionally send an error response if possible
        } catch (IOException e) {
            log.error("I/O error handling client connection {}: {}", requestID, e.getMessage());
        } catch (Exception e) { // Catch all other exceptions like TimeoutException, ExecutionException
            log.error("Error processing request for ID {}: {}", requestID, e.getMessage(), e);
            try {
                // Attempt to send a 504 Gateway Timeout if an error occurred on proxy side
                sendGatewayTimeoutResponse(browserOut);
            } catch (IOException ioException) {
                log.error("Failed to send 504 error response to client: {}", ioException.getMessage());
            }
        } finally {
            // 6. Close browser socket
            try {
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                    log.debug("Client socket closed for ID: {}", requestID);
                }
            } catch (IOException e) {
                log.error("Error closing client socket for ID {}: {}", requestID, e.getMessage());
            }
        }
    }

    /**
     * Reads the entire raw HTTP request from the InputStream, including headers and body.
     * Handles Content-Length for POST/PUT requests.
     */
    private byte[] readHttpRequest(InputStream in) throws IOException {
        ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        int headerEndIndex = -1;
        int contentLength = 0;
        boolean isPostOrPut = false;
        long startTime = System.currentTimeMillis();
        long timeout = 30000; // 30 seconds for reading the initial request

        // Read headers (and potentially some body if it arrives with headers)
        while ((bytesRead = in.read(buffer)) != -1) {
            requestBytes.write(buffer, 0, bytesRead);
            byte[] currentBytes = requestBytes.toByteArray(); // This creates a copy each time, optimize if performance critical

            // Look for the end of headers (\r\n\r\n)
            // Using a simple loop for substring search as String.indexOf is efficient for small lookups
            for (int i = 0; i < currentBytes.length - 3; i++) {
                if (currentBytes[i] == '\r' && currentBytes[i+1] == '\n' &&
                        currentBytes[i+2] == '\r' && currentBytes[i+3] == '\n') {
                    headerEndIndex = i;
                    break;
                }
            }

            if (headerEndIndex != -1) {
                String headersPart = new String(currentBytes, 0, headerEndIndex, StandardCharsets.ISO_8859_1);

                // Check for POST/PUT and Content-Length
                if (headersPart.startsWith("POST ") || headersPart.startsWith("PUT ")) {
                    isPostOrPut = true;
                    Pattern contentLengthPattern = Pattern.compile("Content-Length:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
                    Matcher matcher = contentLengthPattern.matcher(headersPart);
                    if (matcher.find()) {
                        contentLength = Integer.parseInt(matcher.group(1));
                    }
                }
                break; // Headers fully read
            }

            if (System.currentTimeMillis() - startTime > timeout) {
                log.warn("Request header read timeout for {}.", clientSocket.getInetAddress());
                throw new IOException("Request header read timeout");
            }
        }

        // If it's a POST/PUT and we need to read more body data
        if (isPostOrPut && contentLength > 0) {
            int bodyAlreadyRead = requestBytes.size() - (headerEndIndex + 4); // +4 for \r\n\r\n
            int remainingBody = contentLength - bodyAlreadyRead;
            if (remainingBody > 0) {
                log.debug("Reading remaining body for POST/PUT, {} bytes to read.", remainingBody);
                byte[] bodyBuffer = new byte[4096]; // Use a smaller buffer for reading body chunks
                int totalBodyRead = bodyAlreadyRead;
                while (totalBodyRead < contentLength && (bytesRead = in.read(bodyBuffer, 0, Math.min(bodyBuffer.length, contentLength - totalBodyRead))) != -1) {
                    requestBytes.write(bodyBuffer, 0, bytesRead);
                    totalBodyRead += bytesRead;
                }
                if (totalBodyRead < contentLength) {
                    log.warn("Did not read full expected body for request. Expected: {}, Read: {}", contentLength, totalBodyRead);
                }
            }
        }
        return requestBytes.toByteArray();
    }

    private void sendBadRequestResponse(OutputStream out) throws IOException {
        String response = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void sendGatewayTimeoutResponse(OutputStream out) throws IOException {
        String response = "HTTP/1.1 504 Gateway Timeout\r\nContent-Type: text/plain\r\nContent-Length: 26\r\n\r\nProxy timeout or error.";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}