package com.engine.nnue_trainer.board;

public class Cell {
  public int owner;
  public CellKind kind;

  public Cell(int owner, CellKind kind) {
    this.owner = owner;
    this.kind = kind;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Cell cell = (Cell) o;
    return owner == cell.owner && kind == cell.kind;
  }

  @Override
  public int hashCode() {
    return 31 * owner + (kind != null ? kind.hashCode() : 0);
  }

  @Override
  public String toString() {
    return "Cell{" + "owner=" + owner + ", kind=" + kind + '}';
  }

  public int getOwner() {
    return owner;
  }

  public CellKind getKind() {
    return kind;
  }
}
