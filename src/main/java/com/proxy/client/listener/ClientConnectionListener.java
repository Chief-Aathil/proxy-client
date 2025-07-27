package com.proxy.client.listener;

import com.proxy.client.handler.ClientRequestHandler;
import com.proxy.client.queue.RequestQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
@Component
public class ClientConnectionListener {

    @Value("${proxy.client.listen.port}")
    private int listenPort;

    private ServerSocket serverSocket;
    private ExecutorService clientHandlerExecutor; // To manage threads for each ClientRequestHandler
    private volatile boolean running = false;
    private Future<?> listenerTask;
    private final RequestQueue requestQueue;

    /**
     * Called by Spring after component initialization. Starts listening for incoming browser connections.
     */
    @PostConstruct
    public void init() {
        log.info("Initializing ClientConnectionListener on port {}", listenPort);
        clientHandlerExecutor = Executors.newCachedThreadPool(); // Creates new threads as needed, reuses idle ones
        running = true;
        listenerTask = Executors.newSingleThreadExecutor().submit(this::startListening); // Run listener in its own thread
    }

    /**
     * Main loop to accept incoming browser connections and hand them off.
     */
    private void startListening() {
        try {
            serverSocket = new ServerSocket(listenPort);
            log.info("ClientConnectionListener started, listening on port {}", listenPort);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept(); // Blocks until a new connection comes in
                    clientSocket.setTcpNoDelay(true); // Standard optimization
                    log.info("Accepted new browser connection from: {}", clientSocket.getInetAddress().getHostAddress());
                    clientHandlerExecutor.submit(new ClientRequestHandler(clientSocket, requestQueue));
                } catch (IOException e) {
                    if (running) { // Only log error if not intentionally shutting down
                        log.error("Error accepting client connection: {}", e.getMessage());
                    } else {
                        log.info("ClientConnectionListener stopped accepting connections.");
                    }
                } catch (Exception e) {
                    log.error("Unexpected error in client listener loop: {}", e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            log.error("Could not start ClientConnectionListener on port {}: {}", listenPort, e.getMessage());
            running = false; // Mark as not running if startup fails
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    log.error("Error closing server socket: {}", e.getMessage());
                }
            }
            log.info("ClientConnectionListener stopped.");
        }
    }

    /**
     * Shuts down the listener and associated resources gracefully.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ClientConnectionListener.");
        running = false; // Signal the listening loop to stop

        // Interrupt the listener thread if it's currently blocking on accept()
        if (listenerTask != null && !listenerTask.isDone()) {
            listenerTask.cancel(true);
        }

        // Close the server socket to unblock accept() immediately
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                log.info("ClientConnectionListener ServerSocket closed.");
            } catch (IOException e) {
                log.error("Error closing ClientConnectionListener ServerSocket: {}", e.getMessage());
            }
        }

        // Shut down the executor for client handlers
        if (clientHandlerExecutor != null) {
            clientHandlerExecutor.shutdown(); // Initiates an orderly shutdown
            try {
                if (!clientHandlerExecutor.awaitTermination(10, TimeUnit.SECONDS)) { // Wait for tasks to complete
                    log.warn("Client handlers did not terminate in time. Forcing shutdown.");
                    clientHandlerExecutor.shutdownNow(); // Forcefully shut down
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("ClientConnectionListener shutdown interrupted.");
            }
        }
        log.info("ClientConnectionListener shutdown complete.");
    }
}