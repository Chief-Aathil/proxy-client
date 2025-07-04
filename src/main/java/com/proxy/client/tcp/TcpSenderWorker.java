package com.proxy.client.tcp;

import com.proxy.client.model.QueuedHttpRequest;
import com.proxy.client.queue.RequestQueueManager;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;

@Component
public class TcpSenderWorker implements Runnable {

    private final TcpConnectionManager connectionManager;
    private final RequestQueueManager queueManager;

    public TcpSenderWorker(TcpConnectionManager connectionManager, RequestQueueManager queueManager) {
        this.connectionManager = connectionManager;
        this.queueManager = queueManager;
    }

    @PostConstruct
    public void startWorkerThread() {
        new Thread(this).start();
    }

    @Override
    public void run() {
        while (true) {
            try {
                QueuedHttpRequest q = queueManager.take();
                AsyncContext asyncContext = q.asyncContext();
                HttpServletRequest req = (HttpServletRequest) asyncContext.getRequest();
                HttpServletResponse res = (HttpServletResponse) asyncContext.getResponse();

                // Serialize the request
                String payload = req.getMethod() + " " + req.getRequestURI() + " HTTP/1.1\r\nHost: " + req.getHeader("host") + "\r\n\r\n";
                connectionManager.send(payload);

                // Read response
                String serverResponse = connectionManager.receive();
                System.out.println("[proxy-client] Received response:\n" + serverResponse);


                // Send response back to original client
                writeResponseToClient(serverResponse,res);
                asyncContext.complete();
            } catch (Exception e) {
                e.printStackTrace(); // log error for debugging
            }
        }
    }

    private void writeResponseToClient(String rawResponse, HttpServletResponse res) throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(rawResponse));
        String statusLine = reader.readLine(); // e.g., HTTP/1.1 200 OK

        int statusCode = 200; // fallback
        if (statusLine != null && statusLine.startsWith("HTTP/1.1")) {
            String[] parts = statusLine.split(" ");
            if (parts.length >= 2) {
                try {
                    statusCode = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
        }

        // Read headers
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colonIndex = line.indexOf(":");
            if (colonIndex > 0) {
                String headerName = line.substring(0, colonIndex).trim();
                String headerValue = line.substring(colonIndex + 1).trim();
                res.setHeader(headerName, headerValue);
            }
        }

        // Read body
        StringBuilder bodyBuilder = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            bodyBuilder.append(line).append("\n");
        }

        res.setStatus(statusCode);
        res.getWriter().write(bodyBuilder.toString());
        res.getWriter().flush();


    }

}
