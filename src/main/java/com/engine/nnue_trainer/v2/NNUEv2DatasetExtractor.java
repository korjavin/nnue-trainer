package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class NNUEv2DatasetExtractor {

  public static class Record {
    public List<int[]> sparse_stm = new ArrayList<>();
    public List<int[]> sparse_nstm = new ArrayList<>();
    public float[] dense14;
    public float wdl_target;
    public int[] board_size = new int[2];
  }

  private final Map<String, Integer> dictionary;
  private final Random rng;

  public NNUEv2DatasetExtractor(Map<String, Integer> dictionary, long seed) {
    this.dictionary = dictionary;
    this.rng = new Random(seed);
  }

  public NNUEv2DatasetExtractor(Map<String, Integer> dictionary) {
    this(dictionary, 42L);
  }

  private String getPatternString(PatternContract.Window w) {
    StringBuilder sb = new StringBuilder();
    sb.append("d:").append(w.distanceBucket).append(",s:");
    for (int i = 0; i < w.symbols.length; i++) {
      sb.append(w.symbols[i]);
      if (i < w.symbols.length - 1) {
        sb.append(",");
      }
    }
    return sb.toString();
  }

  private List<int[]> extractSparse(Board board, int stm) {
    List<PatternContract.Window> windows = PatternContract.extractWindows(board, stm);
    Map<Integer, Integer> counts = new HashMap<>();

    for (PatternContract.Window w : windows) {
      String patStr = getPatternString(w);
      if (dictionary != null && dictionary.containsKey(patStr)) {
        int patId = dictionary.get(patStr);
        counts.put(patId, counts.getOrDefault(patId, 0) + 1);
      }
    }

    List<int[]> result = new ArrayList<>();
    for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
      result.add(new int[] {entry.getKey(), entry.getValue()});
    }
    return result;
  }

  public Record processPosition(Board board, int stm, int winner, int turnNumber) {
    Record record = new Record();
    record.board_size[0] = board.rows;
    record.board_size[1] = board.cols;

    int nstm = 3 - stm;

    record.sparse_stm = extractSparse(board, stm);
    record.sparse_nstm = extractSparse(board, nstm);

    record.dense14 = DenseFeatures.extract(board, stm, turnNumber);

    if (winner == stm) {
      record.wdl_target = 1.0f;
    } else if (winner == 0) {
      record.wdl_target = 0.5f;
    } else {
      record.wdl_target = 0.0f;
    }

    return record;
  }

  public List<Record> processDataset(List<BoardState> states, double subsampleRate) {
    List<Record> dataset = new ArrayList<>();
    for (BoardState state : states) {
      if (rng.nextDouble() > subsampleRate) {
        continue;
      }
      dataset.add(processPosition(state.board, state.stm, state.winner, state.turnNumber));
    }
    return dataset;
  }

  public static class BoardState {
    public Board board;
    public int stm;
    public int winner;
    public int turnNumber;

    public BoardState(Board board, int stm, int winner, int turnNumber) {
      this.board = board;
      this.stm = stm;
      this.winner = winner;
      this.turnNumber = turnNumber;
    }
  }
}
