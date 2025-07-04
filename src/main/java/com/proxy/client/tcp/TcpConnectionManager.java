package com.proxy.client.tcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.net.Socket;

@Component
public class TcpConnectionManager {
    private Socket socket;
    private BufferedWriter out;
    private BufferedReader in;

    @Value("${proxy.server.host}")
    private String serverHost;

    @Value("${proxy.server.port}")
    private int serverPort;

    @PostConstruct
    public void connectToServer() {
        try {
            socket = new Socket(serverHost, serverPort);
            System.out.println("[proxy-client] Connected to proxy-server at " + serverHost + ":" + serverPort);

            // Just prepare the streams, don't send anything yet
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        } catch (IOException e) {
            throw new RuntimeException("Could not connect to offshore proxy-server", e);
        }
    }

    public synchronized void send(String request) throws IOException {
        out.write(request);
        out.flush();
    }

    public synchronized String receive() throws IOException {
        StringBuilder response = new StringBuilder();
        String line;

        // Read response headers
        int contentLength = 0;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            response.append(line).append("\r\n");

            // Try to extract Content-Length
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.split(":")[1].trim());
            }
        }

        response.append("\r\n");

        // Read response body if Content-Length is known
        if (contentLength > 0) {
            char[] buffer = new char[contentLength];
            int read = in.read(buffer, 0, contentLength);
            response.append(buffer, 0, read);
        }

        return response.toString();
    }

}
