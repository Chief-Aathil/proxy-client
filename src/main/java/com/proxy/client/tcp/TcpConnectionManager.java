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

    @Value("${proxy.server.host}")
    private String serverHost;

    @Value("${proxy.server.port}")
    private int serverPort;

    @PostConstruct
    public void connectToServer() {
        try {
            socket = new Socket(serverHost, serverPort);
            System.out.println("[proxy-client] Connected to proxy-server at " + serverHost + ":" + serverPort);
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

            // Send test message
            out.write("ping\n");
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException("Could not connect to offshore proxy-server", e);
        }
    }
}
