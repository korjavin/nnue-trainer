# NNUE Board Feature Mapping

Implement feature mapping for the NNUE input vector in `com.engine.nnue_trainer.nnue`.

## Tasks

- [ ] Task 1: Implement feature extraction based on Go features.go

### Task 1: Implement feature extraction based on Go features.go
1. Extracted features must match the Go `features.go` mapping:
   - 26 features per player: Normal, Fortified, Connected, Disconnected, Mobility, Captures, BaseExits, BaseOpenings, BaseAnchors, BaseThreat, Threatened, ThreatenedLoss, ThreatTempo, Articulation, MaxCutLoss, SpaceRace, SealedBase, NeutralUnused, MovesLeftTempo, ThreatenedCuts, MinCutThreatDist, MinEnemyBaseDist, FrontOpenness, FrontWidth, ChainReach, SeverableFrac.
2. Flatten into a float array of size `4 * 26 = 104`.\n