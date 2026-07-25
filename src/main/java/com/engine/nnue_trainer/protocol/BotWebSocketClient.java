package com.engine.nnue_trainer.protocol;

import com.engine.nnue_trainer.search.SearchEngine;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

public class BotWebSocketClient extends WebSocketClient {

  private final HandshakeHandler handshakeHandler;
  private final GameLoopHandler gameLoopHandler;

  // Messages are handled on this single worker thread instead of the WebSocket read
  // thread. A move search can block for many seconds; if that ran on the read thread the
  // library could not process the server's pings, the server would hit pongWait (60s) and
  // close the socket mid-match. Single-threaded => order preserved, no overlapping moves.
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "bot-msg-worker");
            t.setDaemon(false);
            return t;
          });
  private volatile boolean shuttingDown = false;

  public BotWebSocketClient(URI serverUri) {
    this(serverUri, new SearchEngine());
  }

  public BotWebSocketClient(URI serverUri, SearchEngine searchEngine) {
    super(serverUri);
    MessageSender sender = this::send;
    this.gameLoopHandler = new GameLoopHandler(sender, searchEngine);
    this.handshakeHandler = new HandshakeHandler(sender, gameLoopHandler::isInGame);
    handshakeHandler.start();
  }

  public BotWebSocketClient() throws URISyntaxException {
    this(new URI("ws://localhost:8080/ws"));
  }

  /** Stop reconnecting and release the worker thread (e.g. on intentional shutdown). */
  public void shutdown() {
    shuttingDown = true;
    handshakeHandler.shutdown();
    worker.shutdownNow();
  }

  @Override
  public void onOpen(ServerHandshake handshakedata) {
    System.out.println("Connected to WebSocket server");
  }

  @Override
  public void onMessage(String message) {
    // Hand off to the worker so the read thread stays free to answer keepalive pings.
    if (shuttingDown) {
      return;
    }
    worker.submit(
        () -> {
          try {
            handshakeHandler.handleMessage(message);
            gameLoopHandler.handleMessage(message);
          } catch (Exception e) {
            System.err.println("Error handling message: " + e.getMessage());
          }
        });
  }

  @Override
  public void onClose(int code, String reason, boolean remote) {
    System.out.println(
        "Disconnected from WebSocket server: "
            + reason
            + " (code="
            + code
            + ", remote="
            + remote
            + ")");
    if (shuttingDown) {
      return;
    }
    // A dropped keepalive or between-games idle close must not kill the bot. Reconnect on a
    // separate thread (onClose runs on the read thread, which reconnectBlocking would block).
    new Thread(this::reconnectLoop, "bot-reconnect").start();
  }

  private void reconnectLoop() {
    for (int attempt = 1; attempt <= 5 && !shuttingDown; attempt++) {
      try {
        Thread.sleep(1000L * attempt);
        System.out.println("Reconnecting to WebSocket server (attempt " + attempt + ")...");
        if (reconnectBlocking()) {
          return; // reconnected; server re-sends welcome/bot_wanted -> handshake re-runs
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        System.err.println("Reconnect attempt " + attempt + " failed: " + e.getMessage());
      }
    }
  }

  @Override
  public void onError(Exception ex) {
    System.err.println("WebSocket error occurred:");
    ex.printStackTrace();
  }
}
