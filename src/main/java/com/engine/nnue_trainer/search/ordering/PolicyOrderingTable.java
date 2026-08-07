package com.engine.nnue_trainer.search.ordering;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.v3.NNUEv3Accumulator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sparse linear move-ordering table (epic nnue-trainer-1jh): {@code score[c] = sum over the
 * position's 145 active features f of W[f][c]} — the NNUE trick applied to move ordering. Features
 * are the v3 set ({@link NNUEv3Accumulator#activeFeatures}, one of 1152 per cell) plus the 4 tempo
 * slots (id {@code 1152 + movesLeft}), trained by {@code python/ordering/train_ordering.py} on the
 * {@code MctsPolicyDatasetEmitter} corpus.
 *
 * <p>One call scores ALL 144 cells at once: 145 active features x 144 cells = ~21k adds, a few µs —
 * cheap enough for every interior node of an alpha-beta search. Zero dependencies on the searcher;
 * integration ranks legal {@code MoveAction} targets by {@code score(...)[row*12+col]} descending.
 *
 * <p>Everything is validated at load — a bad weights file must fail here, once (same discipline as
 * {@code NNUEv3NetEvaluator}).
 */
public final class PolicyOrderingTable {

  public static final Path DEFAULT_WEIGHTS = Path.of("ordering_policy.json");

  /** Tempo one-hot slots appended after the positional features: ids 1152+movesLeft, 0..3. */
  public static final int TEMPO_SLOTS = 4;

  public static final int CELLS = NNUEv3Accumulator.BOARD * NNUEv3Accumulator.BOARD;
  public static final int FEATURES = NNUEv3Accumulator.FEATURES + TEMPO_SLOTS; // 1156

  private final double[] w; // [FEATURES * CELLS], row-major: w[f * CELLS + c]

  private PolicyOrderingTable(double[] w) {
    this.w = w;
  }

  /**
   * Ordering scores for all 144 cells (row-major, {@code r*12+c}) of {@code (board, stm,
   * movesLeft)}. Higher = try earlier. Illegal cells get a score too — the caller masks by its own
   * legal-move set.
   */
  public double[] score(Board board, int stm, int movesLeft) {
    if (movesLeft < 0 || movesLeft >= TEMPO_SLOTS) {
      throw new IllegalArgumentException("movesLeft out of 0..3: " + movesLeft);
    }
    double[] scores = new double[CELLS];
    for (int f : NNUEv3Accumulator.activeFeatures(board, stm)) {
      int base = f * CELLS;
      for (int c = 0; c < CELLS; c++) {
        scores[c] += w[base + c];
      }
    }
    int base = (NNUEv3Accumulator.FEATURES + movesLeft) * CELLS;
    for (int c = 0; c < CELLS; c++) {
      scores[c] += w[base + c];
    }
    return scores;
  }

  /**
   * Loads {@code {"meta": {"features": 1156, "cells": 144, ...}, "w": [[144 doubles] x 1156]}} as
   * written by {@code train_ordering.py}.
   */
  public static PolicyOrderingTable load(Path weightsJson) throws IOException {
    JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(weightsJson));
    JsonNode meta = root.path("meta");
    if (!meta.isObject()) {
      throw new IOException("ordering table: missing/non-object \"meta\" in " + weightsJson);
    }
    // A table fitted over a different feature space would load "fine" and order nonsense.
    if (meta.path("features").asInt(-1) != FEATURES || meta.path("cells").asInt(-1) != CELLS) {
      throw new IOException(
          "ordering table: meta features/cells "
              + meta.path("features").asInt(-1)
              + "/"
              + meta.path("cells").asInt(-1)
              + " != "
              + FEATURES
              + "/"
              + CELLS
              + " in "
              + weightsJson);
    }
    JsonNode wNode = root.path("w");
    if (!wNode.isArray() || wNode.size() != FEATURES) {
      throw new IOException(
          "ordering table: \"w\" must be an array of "
              + FEATURES
              + " rows, got "
              + (wNode.isArray() ? wNode.size() + " rows" : wNode.getNodeType())
              + " in "
              + weightsJson);
    }
    double[] w = new double[FEATURES * CELLS];
    for (int f = 0; f < FEATURES; f++) {
      JsonNode row = wNode.get(f);
      if (!row.isArray() || row.size() != CELLS) {
        throw new IOException(
            "ordering table: w[" + f + "] must have " + CELLS + " entries in " + weightsJson);
      }
      for (int c = 0; c < CELLS; c++) {
        JsonNode v = row.get(c);
        if (!v.isNumber() || !Double.isFinite(v.doubleValue())) {
          throw new IOException("ordering table: w[" + f + "][" + c + "] must be finite, got " + v);
        }
        w[f * CELLS + c] = v.doubleValue();
      }
    }
    return new PolicyOrderingTable(w);
  }
}
