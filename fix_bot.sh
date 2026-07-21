sed -i '/import com.engine.nnue_trainer.search.SearchEngine;/d' src/main/java/com/engine/nnue_trainer/protocol/BotWebSocketClient.java
sed -i '3iimport com.engine.nnue_trainer.search.SearchEngine;' src/main/java/com/engine/nnue_trainer/protocol/BotWebSocketClient.java
