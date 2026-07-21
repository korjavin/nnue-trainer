package com.engine.nnue_trainer.search;

import com.engine.nnue_trainer.board.Action;

public class TranspositionTable {
  private final TTEntry[] table;
  private final int mask;

  public TranspositionTable(int sizeInMB) {
    // Approx 32 bytes per entry in Java. We'll size it based on MB.
    // 1 MB = 1024 * 1024 bytes.
    // Number of entries = (sizeInMB * 1024 * 1024) / 32
    // We want a power of 2 size to use bitwise AND for masking instead of modulo.
    int numEntries = (sizeInMB * 1024 * 1024) / 32;
    int powerOf2Size = 1;
    while (powerOf2Size <= numEntries) {
      powerOf2Size <<= 1;
    }
    powerOf2Size >>= 1; // get highest power of 2 <= numEntries

    this.mask = powerOf2Size - 1;
    this.table = new TTEntry[powerOf2Size];
    for (int i = 0; i < powerOf2Size; i++) {
      this.table[i] = new TTEntry(); // pre-allocate
    }
  }

  public TTEntry probe(long key) {
    int index = (int) (key & mask);
    TTEntry entry = table[index];
    if (entry.zobristKey == key) {
      return entry;
    }
    return null;
  }

  public void store(long key, Action bestAction, float score, int depth, byte flag) {
    int index = (int) (key & mask);
    TTEntry entry = table[index];

    // Always replace strategy or replace if depth is >= existing (we'll use always replace for
    // simplicity and active search relevance)
    entry.zobristKey = key;
    entry.bestAction = bestAction;
    entry.score = score;
    entry.depth = depth;
    entry.flag = flag;
  }

  public void clear() {
    for (int i = 0; i < table.length; i++) {
      table[i].zobristKey = 0;
    }
  }
}
