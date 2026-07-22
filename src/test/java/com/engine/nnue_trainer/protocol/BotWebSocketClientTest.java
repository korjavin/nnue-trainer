package com.engine.nnue_trainer.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BotWebSocketClientTest {

  private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;
  private final PrintStream originalErr = System.err;

  @BeforeEach
  public void setUpStreams() {
    System.setOut(new PrintStream(outContent));
    System.setErr(new PrintStream(errContent));
  }

  @AfterEach
  public void restoreStreams() {
    System.setOut(originalOut);
    System.setErr(originalErr);
  }

  @Test
  void testDefaultConstructorURI() throws URISyntaxException {
    BotWebSocketClient client = new BotWebSocketClient();
    assertEquals(new URI("ws://localhost:8080/ws"), client.getURI());
  }

  @Test
  void testCustomConstructorURI() throws URISyntaxException {
    URI customUri = new URI("ws://localhost:9090/custom");
    BotWebSocketClient client = new BotWebSocketClient(customUri);
    assertEquals(customUri, client.getURI());
  }

  @Test
  void testOnOpen() throws URISyntaxException {
    BotWebSocketClient client = new BotWebSocketClient();
    client.onOpen(null);
    assertTrue(outContent.toString().contains("Connected to WebSocket server"));
  }

  @Test
  void testOnMessage() throws URISyntaxException {
    BotWebSocketClient client = new BotWebSocketClient();
    client.onMessage("Test Message");
    // We removed the System.out.println("Received message: " + message);
    // so we just check that no exception was thrown.
    assertFalse(outContent.toString().contains("Received message: Test Message"));
  }

  @Test
  void testOnCloseAfterShutdownDoesNotReconnect() throws URISyntaxException {
    BotWebSocketClient client = new BotWebSocketClient();
    client.shutdown(); // intentional shutdown: close must NOT trigger a reconnect
    client.onClose(1000, "Normal Closure", false);
    assertTrue(
        outContent.toString().contains("Disconnected from WebSocket server: Normal Closure"));
    assertFalse(outContent.toString().contains("Reconnecting"));
  }

  @Test
  void testOnError() throws URISyntaxException {
    BotWebSocketClient client = new BotWebSocketClient();
    Exception ex = new Exception("Test Exception");
    client.onError(ex);
    assertTrue(errContent.toString().contains("WebSocket error occurred:"));
    assertTrue(errContent.toString().contains("Test Exception"));
  }
}
