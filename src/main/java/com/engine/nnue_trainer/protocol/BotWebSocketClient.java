package com.engine.nnue_trainer.protocol;

import java.net.URI;
import java.net.URISyntaxException;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

public class BotWebSocketClient extends WebSocketClient {

  public BotWebSocketClient(URI serverUri) {
    super(serverUri);
  }

  public BotWebSocketClient() throws URISyntaxException {
    super(new URI("ws://localhost:8080/ws"));
  }

  @Override
  public void onOpen(ServerHandshake handshakedata) {
    System.out.println("Connected to WebSocket server");
  }

  @Override
  public void onMessage(String message) {
    System.out.println("Received message: " + message);
  }

  @Override
  public void onClose(int code, String reason, boolean remote) {
    System.out.println("Disconnected from WebSocket server: " + reason);
  }

  @Override
  public void onError(Exception ex) {
    System.err.println("WebSocket error occurred:");
    ex.printStackTrace();
  }
}
