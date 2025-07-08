package com.proxy.client.tcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.net.Socket;

@Component
public class TcpConnectionManager {
    private Socket socket;
    private OutputStream out;
    private InputStream in;

    @Value("${proxy.server.host}")
    private String serverHost;

    @Value("${proxy.server.port}")
    private int serverPort;

    @PostConstruct
    public void connectToServer() {
        try {
            socket = new Socket(serverHost, serverPort);

            // Preparing the streams
            out = socket.getOutputStream();
            in = socket.getInputStream();
            System.out.println("[proxy-client] Connected to proxy-server at " + serverHost + ":" + serverPort);
        } catch (IOException e) {
            throw new RuntimeException("Could not connect to offshore proxy-server", e);
        }
    }

    public synchronized void send(String request) throws IOException {
        out.write(request.getBytes());
        out.flush();
    }

    public synchronized byte[] receiveRaw() throws IOException {
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        byte[] temp = new byte[8192];
        int bytesRead;

        while ((bytesRead = input.read(temp)) != -1) {
            buffer.write(temp, 0, bytesRead);
            if (input.available() == 0) break;
        }

        return buffer.toByteArray();
    }
}
