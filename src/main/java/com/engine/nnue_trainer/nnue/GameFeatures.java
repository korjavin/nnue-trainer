package com.engine.nnue_trainer.nnue;

public class GameFeatures {
    public PlayerFeatures[] players = new PlayerFeatures[4];

    public GameFeatures() {
        for (int i = 0; i < 4; i++) {
            players[i] = new PlayerFeatures();
        }
    }

    public float[] flatten() {
        float[] flattened = new float[104]; // 4 players * 26 features
        int index = 0;
        for (PlayerFeatures player : players) {
            float[] playerFeatures = player.toArray();
            System.arraycopy(playerFeatures, 0, flattened, index, playerFeatures.length);
            index += playerFeatures.length;
        }
        return flattened;
    }
}
