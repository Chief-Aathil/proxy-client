package com.proxy.client.task;

import com.proxy.client.communicator.FramedMessage; // To use MessageType enum
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

/**
 * Represents a single request received from a browser/client, to be processed sequentially.
 * This task holds the raw request data, the client's socket, and a future
 * to deliver the response back to the originating ClientRequestHandler.
 */
@Getter
@RequiredArgsConstructor
@ToString(exclude = {"clientSocket", "responseFuture"})
public class ProxyRequestTask {

    @NonNull private final UUID requestID;
    @NonNull private final FramedMessage.MessageType requestType; // HTTP_REQUEST or HTTPS_CONNECT
    @NonNull private final byte[] rawRequestBytes; // Raw bytes of the HTTP request or CONNECT line
    @NonNull private final Socket clientSocket; // The socket connected to the browser
    @NonNull private final CompletableFuture<byte[]> responseFuture; // Future to deliver the final response bytes
}