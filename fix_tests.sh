sed -i 's/gameLoopHandler = new GameLoopHandler(messageSender);/gameLoopHandler = new GameLoopHandler(messageSender, new com.engine.nnue_trainer.search.SearchEngine());/' src/test/java/com/engine/nnue_trainer/protocol/GameLoopHandlerTest.java
sed -i 's/action instanceof PlaceNeutralsAction/false/g' src/main/java/com/engine/nnue_trainer/search/SearchEngine.java
sed -i 's/nextBoard.setCell(pos1.row, pos1.col, new Cell(0, CellKind.NEUTRAL));\n      nextBoard.setCell(pos2.row, pos2.col, new Cell(0, CellKind.NEUTRAL));/ /g' src/main/java/com/engine/nnue_trainer/search/SearchEngine.java
