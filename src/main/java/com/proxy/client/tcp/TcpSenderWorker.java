package com.proxy.client.tcp;

import com.proxy.client.model.QueuedHttpRequest;
import com.proxy.client.queue.RequestQueueManager;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

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

                // Send the request
                String payload = req.getMethod() + " " + req.getRequestURI() + " HTTP/1.1\r\nHost: " + req.getHeader("host") + "\r\n\r\n";
                connectionManager.send(payload);

                // Get response
                byte[] responseBytes = connectionManager.receiveRaw();
                System.out.println("[proxy-client] Received response");

                // Send response back to original client
                writeParsedResponseToClient(responseBytes,res);
                asyncContext.complete();
                System.out.println("[proxy-client] Response sent to actual client");

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void writeParsedResponseToClient(byte[] rawResponseBytes, HttpServletResponse res) throws IOException {
        int headerBodySeparatorIndex = -1;
        // Search for the first occurrence of \r\n\r\n (CRLF CRLF)
        for (int i = 0; i < rawResponseBytes.length - 3; i++) {
            if (rawResponseBytes[i] == 0x0D && rawResponseBytes[i + 1] == 0x0A && // CR LF
                    rawResponseBytes[i + 2] == 0x0D && rawResponseBytes[i + 3] == 0x0A) { // CR LF
                headerBodySeparatorIndex = i;
                break;
            }
        }

        if (headerBodySeparatorIndex == -1) {
            // If no proper separator is found, handle as an error or unexpected format.
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            res.getWriter().write("Error: Raw response bytes do not contain a valid HTTP header-body separator.");
            return;
        }

        // 1. Extract the raw header string
        String rawHeaders = new String(rawResponseBytes, 0, headerBodySeparatorIndex, StandardCharsets.ISO_8859_1);
        String[] headerLines = rawHeaders.split("\r\n");

        int statusCode = HttpServletResponse.SC_OK; // Default status code
        String contentType = "application/octet-stream"; // Default content type

        // Parse Status Line and Headers from the extracted header string
        if (headerLines.length > 0) {
            // Parse Status Line (e.g., "HTTP/1.1 200 OK")
            String statusLine = headerLines[0];
            if (statusLine.startsWith("HTTP/")) {
                String[] statusParts = statusLine.split(" ");
                if (statusParts.length > 1) {
                    try {
                        statusCode = Integer.parseInt(statusParts[1]);
                    } catch (NumberFormatException e) {
                        // status code is not a valid number
                        System.err.println("Warning: Could not parse status code from: " + statusLine);
                    }
                }
            }

            // Parse other relevant headers
            for (int i = 1; i < headerLines.length; i++) {
                String line = headerLines[i];
                if (line.toLowerCase().startsWith("content-type:")) {
                    contentType = line.substring("content-type:".length()).trim();
                }
            }
        }

        // Extract the actual HTML body bytes
        byte[] htmlBodyBytes = new byte[rawResponseBytes.length - (headerBodySeparatorIndex + 4)];
        System.arraycopy(rawResponseBytes, headerBodySeparatorIndex + 4, htmlBodyBytes, 0, htmlBodyBytes.length);

        // Build response
        res.setStatus(statusCode);
        res.setHeader("Content-Type", contentType);
        res.setContentLength(htmlBodyBytes.length);

        // Write only the HTML body to the client's output stream
        OutputStream out = res.getOutputStream();
        out.write(htmlBodyBytes);
        out.flush();
    }
}
