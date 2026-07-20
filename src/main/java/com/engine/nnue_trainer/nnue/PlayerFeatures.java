package com.engine.nnue_trainer.nnue;

public class PlayerFeatures {
  public float normal;
  public float fortified;
  public float connected;
  public float disconnected;
  public float mobility;
  public float captures;
  public float baseExits;
  public float baseOpenings;
  public float baseAnchors;
  public float baseThreat;
  public float threatened;
  public float threatenedLoss;
  public float threatTempo;
  public float articulation;
  public float maxCutLoss;
  public float spaceRace;
  public float sealedBase;
  public float neutralUnused;
  public float movesLeftTempo;
  public float threatenedCuts;
  public float minCutThreatDist;
  public float minEnemyBaseDist;
  public float frontOpenness;
  public float frontWidth;
  public float chainReach;
  public float severableFrac;

  public float[] toArray() {
    return new float[] {
      normal,
      fortified,
      connected,
      disconnected,
      mobility,
      captures,
      baseExits,
      baseOpenings,
      baseAnchors,
      baseThreat,
      threatened,
      threatenedLoss,
      threatTempo,
      articulation,
      maxCutLoss,
      spaceRace,
      sealedBase,
      neutralUnused,
      movesLeftTempo,
      threatenedCuts,
      minCutThreatDist,
      minEnemyBaseDist,
      frontOpenness,
      frontWidth,
      chainReach,
      severableFrac
    };
  }
}
