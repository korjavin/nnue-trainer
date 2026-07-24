package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NNUEv2DatasetExtractorTest {

  @Test
  void testExtractRecordFormat() {
    Map<String, Integer> dict = new HashMap<>();
    dict.put("d:7,s:8,8,8,8,8,8,8,8,8,8,8,8,4,0,0,8,8,0,0,0,8,8,0,0,0", 1);

    NNUEv2DatasetExtractor extractor = new NNUEv2DatasetExtractor(dict, 42L);

    Board board = new Board(3, 3);
    board.setCell(0, 0, new Cell(1, CellKind.NORMAL));

    List<NNUEv2DatasetExtractor.BoardState> states = new ArrayList<>();
    states.add(new NNUEv2DatasetExtractor.BoardState(board, 1, 1, 10));

    List<NNUEv2DatasetExtractor.Record> dataset = extractor.processDataset(states, 1.0);

    assertEquals(1, dataset.size());
    NNUEv2DatasetExtractor.Record record = dataset.get(0);

    assertNotNull(record.sparse_stm);
    assertNotNull(record.sparse_nstm);
    assertNotNull(record.dense14);
    assertEquals(14, record.dense14.length);

    assertEquals(3, record.board_size[0]);
    assertEquals(3, record.board_size[1]);

    assertEquals(1.0f, record.wdl_target);

    boolean foundPattern1 = false;
    for (int[] p : record.sparse_stm) {
      if (p[0] == 1) {
        foundPattern1 = true;
        break;
      }
    }
    assertTrue(foundPattern1, "Should find pattern 1 in sparse_stm");
  }

  @Test
  void testTargetPerspectiveFlipping() {
    NNUEv2DatasetExtractor extractor = new NNUEv2DatasetExtractor(new HashMap<>(), 42L);

    Board board = new Board(3, 3);

    NNUEv2DatasetExtractor.Record r1 = extractor.processPosition(board, 1, 1, 10);
    assertEquals(1.0f, r1.wdl_target, "Win for STM");

    NNUEv2DatasetExtractor.Record r2 = extractor.processPosition(board, 2, 1, 10);
    assertEquals(0.0f, r2.wdl_target, "Loss for STM");

    NNUEv2DatasetExtractor.Record r3 = extractor.processPosition(board, 1, 0, 10);
    assertEquals(0.5f, r3.wdl_target, "Draw for STM");
  }

  @Test
  void testSubsamplingIsDeterministic() {
    NNUEv2DatasetExtractor ext1 = new NNUEv2DatasetExtractor(new HashMap<>(), 123L);
    NNUEv2DatasetExtractor ext2 = new NNUEv2DatasetExtractor(new HashMap<>(), 123L);

    List<NNUEv2DatasetExtractor.BoardState> states = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      states.add(new NNUEv2DatasetExtractor.BoardState(new Board(3, 3), 1, 0, 10));
    }

    List<NNUEv2DatasetExtractor.Record> d1 = ext1.processDataset(states, 0.5);
    List<NNUEv2DatasetExtractor.Record> d2 = ext2.processDataset(states, 0.5);

    assertEquals(d1.size(), d2.size());
  }
}
