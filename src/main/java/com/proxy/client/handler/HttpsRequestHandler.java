package com.proxy.client.handler;

import com.proxy.client.communicator.FramedMessage;
import com.proxy.client.communicator.ProxyClientCommunicator;
import com.proxy.client.queue.RequestQueue;
import com.proxy.client.task.ProxyRequestTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RequiredArgsConstructor
@Scope("prototype")
@Component
public class HttpsRequestHandler {

    private final RequestQueue requestQueue;
    private final ProxyClientCommunicator clientCommunicator;

    /**
     * Handles HTTPS CONNECT requests and manages the bidirectional tunnel once established.
     * This method focuses on the initial handshake to establish the tunnel.
     */
    public void handleHttpsConnect(UUID requestID,
                                   byte[] connectRequestBytes,
                                   InputStream browserIn,
                                   OutputStream browserOut,
                                   Socket clientSocket)
            throws InterruptedException, ExecutionException, TimeoutException, IOException {

        // Establish the tunnel by sending CONNECT request to offshore proxy and waiting for 200 OK
        establishTunnel(requestID, connectRequestBytes, browserOut, clientSocket);

        // Register this handler's browser output stream with the communicator.
        // This allows ProxyClientCommunicator to directly write incoming HTTPS_DATA
        // (from the offshore server) to this browser's socket.
        clientCommunicator.registerHttpsTunnelOutputStream(requestID, browserOut);

        // Now, enter the continuous tunneling mode
        runHttpsTunnel(requestID, browserIn, clientSocket);
    }

    private void establishTunnel(UUID requestID, byte[] connectRequestBytes, OutputStream browserOut, Socket clientSocket)
            throws InterruptedException, ExecutionException, TimeoutException, IOException {
        CompletableFuture<byte[]> httpsTunnelReadyFuture = new CompletableFuture<>();
        ProxyRequestTask task = new ProxyRequestTask(
                requestID,
                FramedMessage.MessageType.HTTPS_CONNECT,
                connectRequestBytes,
                clientSocket,
                httpsTunnelReadyFuture
        );

        requestQueue.put(task);
        log.debug("Enqueued HTTPS CONNECT request with ID: {}", requestID);

        // Wait for the tunnel to be established (indicated by CONTROL_200_OK from server)
        httpsTunnelReadyFuture.get(60, TimeUnit.SECONDS);
        log.info("HTTPS tunnel established for ID: {}", requestID);

        // Send 200 OK to browser to acknowledge tunnel establishment
        String httpOkResponse = "HTTP/1.1 200 Connection Established\r\n\r\n";
        browserOut.write(httpOkResponse.getBytes(StandardCharsets.ISO_8859_1));
        browserOut.flush();
        log.debug("Sent 200 Connection Established to browser for ID: {}", requestID);
    }

    /**
     * Manages the continuous, bidirectional flow of encrypted HTTPS data
     * through an already established tunnel.
     */
    private void runHttpsTunnel(UUID requestID, InputStream browserIn, Socket clientSocket) {
        try {
            // Continuously read raw encrypted bytes from the browser's socket
            // and send them as HTTPS_DATA FramedMessages to the offshore proxy.
            // Data from server -> browser is handled by ProxyClientCommunicator's receive thread
            // using the registered output stream.
            transferBrowserToProxy(requestID, browserIn, clientSocket);
            log.info("Browser socket closed gracefully for HTTPS tunnel ID: {}", requestID);
        } catch (IOException e) {
            if (clientSocket.isClosed()) {
                log.debug("HTTPS tunnel ID {} already closed due to browser socket closure or prior error.", requestID);
            } else {
                log.warn("I/O error during HTTPS tunnel data transfer from browser for ID {}: {}", requestID, e.getMessage());
            }
        } finally {
            // When the browser socket closes (or an error occurs in the loop),
            // send a CONTROL_TUNNEL_CLOSE message to the server to tear down its side of the tunnel.
            sendTunnelCloseSignal(requestID);
        }
    }

    /**
     * Reads raw encrypted bytes from the browser's input stream and sends them
     * as HTTPS_DATA FramedMessages to the offshore proxy.
     */
    private void transferBrowserToProxy(UUID requestID, InputStream browserIn, Socket clientSocket) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        while (!clientSocket.isClosed() && (bytesRead = browserIn.read(buffer)) != -1) {
            if (bytesRead > 0) {
                byte[] payload = new byte[bytesRead];
                System.arraycopy(buffer, 0, payload, 0, bytesRead);
                FramedMessage dataMessage = new FramedMessage(
                        FramedMessage.MessageType.HTTPS_DATA,
                        requestID,
                        payload
                );
                try {
                    clientCommunicator.send(dataMessage); // Send raw encrypted data from browser to server
                    log.trace("Sent {} bytes of HTTPS_DATA from browser for ID: {}", bytesRead, requestID);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Re-set the interrupt flag
                    log.warn("HTTPS tunnel data send for ID {} interrupted. Breaking tunnel loop.", requestID);
                    break; // Exit the while loop
                }
            }
        }
    }

    /**
     * Sends a CONTROL_TUNNEL_CLOSE message to the proxy-server to signal
     * the tear-down of a specific HTTPS tunnel.
     */
    private void sendTunnelCloseSignal(UUID requestID) {
        log.info("Sending CONTROL_TUNNEL_CLOSE for HTTPS tunnel ID: {}", requestID);
        FramedMessage closeMessage = new FramedMessage(
                FramedMessage.MessageType.CONTROL_TUNNEL_CLOSE,
                requestID,
                new byte[0]
        );
        try {
            // Ensure send is not interrupted by the same thread interruption
            clientCommunicator.send(closeMessage);
        } catch (InterruptedException | IOException e) {
            log.error("Failed to send CONTROL_TUNNEL_CLOSE for ID {}: {}", requestID, e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
