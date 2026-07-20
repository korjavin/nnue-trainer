package com.engine.nnue_trainer.board;

public class Board {
    public final int rows;
    public final int cols;
    private final Cell[][] cells;

    public Board(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.cells = new Cell[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                this.cells[r][c] = new Cell(0, CellKind.EMPTY);
            }
        }
    }

    public Cell getCell(int row, int col) {
        if (isValidPos(row, col)) {
            return cells[row][col];
        }
        return null;
    }

    public Cell getCell(Pos pos) {
        return getCell(pos.row, pos.col);
    }

    public void setCell(int row, int col, Cell cell) {
        if (isValidPos(row, col)) {
            cells[row][col] = cell;
        }
    }

    public void setCell(Pos pos, Cell cell) {
        setCell(pos.row, pos.col, cell);
    }

    public boolean isValidPos(int row, int col) {
        return row >= 0 && row < rows && col >= 0 && col < cols;
    }

    public boolean isValidPos(Pos pos) {
        return isValidPos(pos.row, pos.col);
    }
}
