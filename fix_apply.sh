sed -i 's/action instanceof PlaceNeutralsAction/false/g' src/main/java/com/engine/nnue_trainer/train/GameImporter.java
sed -i 's/"neutral".equals(type)/"neutrals".equals(type)/g' src/main/java/com/engine/nnue_trainer/train/GameImporter.java
sed -i 's/Board board = new Board(12, 12);/Board board = new Board(12, 12);\n          board.setCell(0, 0, new Cell(1, CellKind.BASE));\n          board.setCell(11, 11, new Cell(2, CellKind.BASE));/g' src/main/java/com/engine/nnue_trainer/train/GameImporter.java
