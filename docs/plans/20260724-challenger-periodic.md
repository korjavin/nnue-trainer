# Challenger mode: periodic random-player challenges (fix stuck-after-decline)

## Overview
The challenger-mode bot currently challenges only reactively: `HandshakeHandler.handleUsersUpdate()`
sends a challenge when the server pushes a `UsersUpdateMessage`, throttled to once/10s, targeting the
FIRST user whose username contains "go" or startsWith "bot". Problems: (a) if user-list updates stop
arriving it never re-challenges → stuck; (b) if a challenge is declined it never retries; (c) it only
targets go/bot users, not random online players; (d) it never checks whether the bot ITSELF is already
in a game.

This change makes challenger mode timer-driven: on a ~5-min cadence, when the bot is NOT in a game, it
picks a RANDOM eligible online player (not self, not already in-game) from a cached user list and sends
a `ChallengeMessage` (kept at 12x12, the production board). The reactive path is kept (as an immediate
first attempt, 10s-throttled) so the existing reactive tests stay green, but the TIMER is the real
driver so the bot never gets stuck when updates stop or a challenge is declined.

Deliberate behavior change — owner-reviewed before merge. Merge target: master (production bot).

## Context (from discovery)
- Files/components involved:
  - `src/main/java/com/engine/nnue_trainer/protocol/HandshakeHandler.java` — reactive challenge logic (rewrite)
  - `src/main/java/com/engine/nnue_trainer/protocol/GameLoopHandler.java` — tracks `currentGameId` (add `isInGame()`)
  - `src/main/java/com/engine/nnue_trainer/protocol/BotWebSocketClient.java` — constructs both handlers, lifecycle (wire + shutdown)
  - `src/test/java/com/engine/nnue_trainer/protocol/HandshakeHandlerTest.java` — existing tests + new challenger tests
  - `src/main/java/com/engine/nnue_trainer/protocol/messages/{WelcomeMessage,UsersUpdateMessage,ChallengeMessage}.java` — data (no change expected)
- Related patterns found:
  - `train/PeriodicRetrainer.java` — the injectable-scheduler pattern to COPY: constructor takes a
    `ScheduledExecutorService`; `start(initialDelay, period)` calls `scheduler.scheduleAtFixedRate(...)`;
    a `createDefault(...)` factory builds the production instance; `close()` calls `scheduler.shutdownNow()`.
    Its test injects `Executors.newSingleThreadScheduledExecutor()`.
  - `GameLoopHandler.gobotSearchFromEnv()` / `useGobotSearch` field — the per-instance "read env ONCE at
    construction" testability convention (do NOT bury `System.getenv` deep in logic).
- Dependencies identified: Mockito + JUnit5 already used by `HandshakeHandlerTest`.

## Development Approach
- **Testing approach**: Regular (code first, then tests), matching existing repo style.
- Read env ONCE at construction into final fields (per-instance/injectable config), NOT via static getenv
  deep in logic. Follow the `useGobotSearch` convention.
- Prove the periodic behavior with an INJECTED scheduler / manual tick invocation — no wall-clock waiting.
- Keep `ChallengeMessage` at 12x12 (production board).
- All tests must pass (`./mvnw test`) before finishing.

## Testing Strategy
- **Unit tests** (in `HandshakeHandlerTest`): periodic fire (via injected scheduler → captured Runnable),
  random eligible pick (seeded `Random`), skip-while-in-game (controllable `BooleanSupplier`),
  self-exclusion (capture own id from welcome), no-eligible-users no-op. Keep all existing tests green.
- No E2E tests in this project for this path.

## Progress Tracking
- Mark completed items with `[x]` immediately when done.
- Add newly discovered tasks with ➕ prefix; blockers with ⚠️ prefix.

## What Goes Where
- Implementation Steps: code + tests in this repo.
- Post-Completion: manual production observation (bot actually challenges every ~5 min live).

## Implementation Steps

### Task 1: Add `isInGame()` to GameLoopHandler
- [x] make `currentGameId` field `volatile` (read from the scheduler thread, written on the worker thread)
- [x] add `public boolean isInGame()` returning `!currentGameId.isEmpty()`
- [x] add a test in a suitable protocol test (or `HandshakeHandlerTest` companion) OR verify via the
      existing GameLoopHandler tests that `isInGame()` is false initially, true after a `game_start`
      message, and false again after `game_end`
- [x] run `./mvnw test` — must pass before next task

### Task 2: Rewrite HandshakeHandler as timer-driven challenger with injectable config
- [x] add fields: `BooleanSupplier isInGame`, `ScheduledExecutorService scheduler`, `Random random`,
      `final boolean challengerMode`, `final int intervalSec`; keep `MessageSender`, `ObjectMapper`
- [x] add `volatile List<UsersUpdateMessage.User> onlineUsers` cache (init empty) and `volatile String selfId`
- [x] read env ONCE at construction: `CHALLENGER_MODE` (env or system property, "true" gate) and
      `CHALLENGE_INTERVAL_SEC` (default 300) into final fields via small private static helpers
- [x] keep the simple `HandshakeHandler(MessageSender)` constructor (isInGame → `() -> false`, own
      single-thread scheduler, `new Random()`, env-derived config) so existing wiring/tests compile
- [x] add `HandshakeHandler(MessageSender, BooleanSupplier isInGame)` production constructor (env config,
      own scheduler + Random)
- [x] add a fully-injectable constructor `(MessageSender, BooleanSupplier, ScheduledExecutorService, Random,
      boolean challengerMode, int intervalSec)` for tests
- [x] `handleWelcome`: capture `selfId` from `WelcomeMessage.getUserId()` (still no send)
- [x] `handleUsersUpdate`: cache `usersUpdateMessage.getUsers()` (null-safe → empty list); then, if
      challengerMode, do a 10s-throttled reactive `attemptChallenge()` (keeps existing reactive tests green)
- [x] add `void attemptChallenge()`: gate on `challengerMode`; return if `isInGame.getAsBoolean()`; build
      eligible list from cache (id != null, id != selfId, `!user.isInGame()`, NO go/bot filter); if empty
      no-op; else pick `eligible.get(random.nextInt(eligible.size()))`, send `ChallengeMessage(id, 12, 12)`
- [x] add `void challengeTick()` (package-visible) = `attemptChallenge()` — the scheduled task body
- [x] add `void start()`: if challengerMode, `scheduler.scheduleAtFixedRate(this::challengeTick, initialDelay, intervalSec, SECONDS)` with a little jitter on the initial delay (seeded via `random`)
- [x] add `void shutdown()` → `scheduler.shutdownNow()`
- [x] update `testHandleUsersUpdateMessageDoesNotChallengeIfDisabled` to set `CHALLENGER_MODE=false`
      BEFORE constructing the handler (env read once at construction now) — construct a fresh handler in
      the test after setting the property
- [x] run `./mvnw test` — existing HandshakeHandlerTest cases must pass before next task

### Task 3: Wire HandshakeHandler into BotWebSocketClient lifecycle
- [ ] in `BotWebSocketClient` constructor, build `gameLoopHandler` first, then
      `new HandshakeHandler(sender, gameLoopHandler::isInGame)`
- [ ] call `handshakeHandler.start()` to begin the periodic timer
- [ ] in `shutdown()`, call `handshakeHandler.shutdown()` alongside `worker.shutdownNow()`
- [ ] run `./mvnw test` — must pass before next task

### Task 4: Add challenger unit tests
- [ ] periodic fire: inject a mock `ScheduledExecutorService`, call `start()`, capture the `Runnable`
      passed to `scheduleAtFixedRate`, invoke it after caching one eligible user → verify a challenge sent
- [ ] random eligible pick: cache several eligible users, seeded `Random`, call `challengeTick()` →
      verify the challenge targets the expected user id for that seed (no go/bot filter)
- [ ] skip-while-in-game: `isInGame` supplier returns true → `challengeTick()` sends nothing
- [ ] self-exclusion: welcome sets selfId; cache list containing only self → no challenge; cache with self
      + one other → challenges the other
- [ ] no-eligible no-op: cache empty / all in-game → `challengeTick()` sends nothing
- [ ] run `./mvnw test` — all green

### Task 5: Verify acceptance criteria
- [ ] verify: idle challenger bot picks a RANDOM eligible online player about every ~5 min via the timer
- [ ] verify: never challenges while `isInGame()` is true
- [ ] verify: self excluded; declines survived (timer keeps firing; random pick avoids hammering)
- [ ] run full `./mvnw test` — all green
- [ ] run spotless/checkstyle if wired into the build (`./mvnw test` covers verification)

## Technical Details
- Eligibility predicate per cached `UsersUpdateMessage.User u`: `u.getId() != null && !u.getId().equals(selfId) && !u.isInGame()`.
- `ChallengeMessage` serializes target as `targetUserId` (see `@JsonProperty`); keep rows=cols=12.
- Interval: `CHALLENGE_INTERVAL_SEC` seconds (default 300). Jitter: add `random.nextInt(...)` seconds to the
  initial delay only (fixed-rate period stays `intervalSec`) so multiple bots don't sync — keep it minimal.
- Thread-safety: `onlineUsers` and `selfId` written on the message worker thread, read on the scheduler
  thread → mark `volatile`. `currentGameId` in GameLoopHandler likewise → `volatile`.
- The scheduler in the production single-arg/two-arg constructors is created but only spawns a thread once
  `start()` schedules a task, so constructing a handler in a test that never calls `start()` leaks nothing.

## Post-Completion
**Manual verification** (production, owner):
- With `CHALLENGER_MODE=true`, confirm the live bot sends a challenge to a random online player about every
  5 minutes, keeps doing so after declines and quiet periods, and never challenges while itself in a game.
