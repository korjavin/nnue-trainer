package com.engine.nnue_trainer.board;

import java.util.Objects;

public class PlaceNeutralsAction extends Action {
  public final Pos pos1;
  public final Pos pos2;

  public PlaceNeutralsAction(Pos pos1, Pos pos2) {
    super(ActionType.PLACE_NEUTRALS);
    this.pos1 = pos1;
    this.pos2 = pos2;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PlaceNeutralsAction that = (PlaceNeutralsAction) o;
    // The order of pos1 and pos2 doesn't technically matter for equality if it's just two
    // placements,
    // but let's assume they are sorted or we just check both combinations.
    return (Objects.equals(pos1, that.pos1) && Objects.equals(pos2, that.pos2))
        || (Objects.equals(pos1, that.pos2) && Objects.equals(pos2, that.pos1));
  }

  @Override
  public int hashCode() {
    return Objects.hash(pos1, pos2) + Objects.hash(pos2, pos1); // commutative hash
  }

  @Override
  public String toString() {
    return "PlaceNeutralsAction{"
        + "pos1="
        + pos1.row
        + ","
        + pos1.col
        + ", pos2="
        + pos2.row
        + ","
        + pos2.col
        + "}";
  }
}
