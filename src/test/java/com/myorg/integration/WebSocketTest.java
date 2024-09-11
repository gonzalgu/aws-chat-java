package com.myorg.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.myorg.ChatStack;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.assertions.Template;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WebSocketTest {

    /*
     * gadol@DESKTOP-8F31RFM:~$ wscat -c
     * wss://swzxzc4mg0.execute-api.sa-east-1.amazonaws.com/dev
     * Connected (press CTRL+C to quit)
     * > {"action": "sendmessage", "message": "hello, everyone!"}
     * < hello, everyone!
     * > {"action": "sendmessage", "message": "hey there!"}
     * >
     * 
     */

    static class WSListener implements WebSocket.Listener {
        int id;

        public WSListener(int index) {
            this.id = index;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            System.out.printf("[%d]: WebSocket opened\n", this.id);
            // TODO Auto-generated method stub
            Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // TODO Auto-generated method stub
            System.out.printf("[%d]: Received message: %s\n", id, data);
            return Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // TODO Auto-generated method stub
            System.out.printf("[%d] WebSocket closed with statusCode: %s\n", id, statusCode);
            return Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            // TODO Auto-generated method stub
            // Listener.super.onError(webSocket, error);
            System.err.printf("[%d] Error ocurrred: %s\n", id, error.getMessage());
        }
    };

    record Message(String action, String message) {
        @Override
        public final String toString() {
            return String.format("{\"action\": \"%s\", \"message\": \"%s\"}", action, message);
        }
    };

    Message makeMessage(String message) {
        return new Message("sendmessage", message);
    }

    @Test
    void testPingPong() throws InterruptedException, ExecutionException {
        String chatApiURL = "wss://swzxzc4mg0.execute-api.sa-east-1.amazonaws.com/dev";
        URI uri = URI.create(chatApiURL);
        HttpClient client = HttpClient.newHttpClient();
        AtomicBoolean pongReceived = new AtomicBoolean(false);
        ByteBuffer response = ByteBuffer.allocate(8);
        var ws = client.newWebSocketBuilder().buildAsync(uri, new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                while (message.remaining() > 0) {
                    byte b = message.get();
                    response.put(b);
                }
                pongReceived.set(true);
                return Listener.super.onPong(webSocket, message);
            }
        }).join();
        var dead_beef = new byte[] { 0xD, 0xE, 0xA, 0xD, 0xB, 0xE, 0xE, 0xF };
        var buf = ByteBuffer.wrap(dead_beef);
        var fut = ws.sendPing(buf);
        fut.get();
        Assertions.assertEquals(0, buf.remaining());
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilTrue(pongReceived);
        var arr = response.array();
        Assertions.assertArrayEquals(dead_beef, arr);
    }

    @Test
    void canSendTextBetweenSockets() throws InterruptedException, ExecutionException {
        String chat = "wss://swzxzc4mg0.execute-api.sa-east-1.amazonaws.com/dev";

        URI chatURI = URI.create(chat);

        HttpClient client = HttpClient.newHttpClient();
        // Create 2 websocket instances
        var ws_f1 = client.newWebSocketBuilder().buildAsync(chatURI, new WSListener(1));
        var ws_f2 = client.newWebSocketBuilder().buildAsync(chatURI, new WSListener(2));

        var fut = CompletableFuture.allOf(ws_f1, ws_f2);
        fut.join();

        var ws1 = ws_f1.get();
        var ws2 = ws_f2.get();

        var msg1 = makeMessage("hey there!");
        ws1.sendText(msg1.toString(), true);
        Thread.sleep(5000);
        var msg2 = makeMessage("Hallo neighbor!");
        ws2.sendText(msg2.toString(), true);
        Thread.sleep(5000);
        ws1.sendClose(WebSocket.NORMAL_CLOSURE, "Bye!")
                .thenRun(() -> System.out.println("websocket closed"));
        Thread.sleep(2000);
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "K thx bye!")
                .thenRun(() -> System.out.println("websocket closed"));

        Thread.sleep(1000);
    }
}
