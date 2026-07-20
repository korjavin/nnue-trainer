package com.engine.nnue_trainer;

import com.engine.nnue_trainer.protocol.BotWebSocketClient;
import java.net.URI;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NnueTrainerApplication {

  public static void main(String[] args) {
    SpringApplication.run(NnueTrainerApplication.class, args);

    try {
      String wsUrl = System.getenv("BACKEND_URL");
      if (wsUrl == null || wsUrl.isEmpty()) {
        wsUrl = "ws://localhost:8080/ws";
      }
      System.out.println("Starting BotWebSocketClient connecting to " + wsUrl);
      BotWebSocketClient client = new BotWebSocketClient(new URI(wsUrl));
      client.connect();
    } catch (Exception e) {
      System.err.println("Failed to start bot client: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
