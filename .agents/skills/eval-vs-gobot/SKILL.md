---
name: eval-vs-gobot
description: "Run the strength harness that plays the Java NNUE bot against the GoBot reference implementation over a live game server and reports Java's win rate.
TRIGGER when: measuring bot strength/parity, verifying a search or eval change moved the needle vs GoBot, or reproducing a 'lost N/M to GoBot' result.
DO NOT TRIGGER for: pure search-vs-search A/B with eval held fixed (use SearchAB, see below) or unit tests (./mvnw test)."
---

# Eval: JavaBot vs GoBot reference

`eval_java_vs_go.py` is the end-to-end strength harness. It boots the real virusgame
server, one deterministic GoBot (the hand-tuned reference AI from `bot-hoster`), and the
Java NNUE bot, plays `N` real games over WebSocket, and reports Java's win rate from the
server's `games.db`.

**JavaBot is always the challenger (player1); GoBot accepts (player2).** GoBot runs with
NO exploration epsilon — deterministic, correct for a parity measurement.

## Prerequisites (one-time)

1. **Sibling repo**: virusgame must be checked out at `../virusgame` (path is hardcoded).
2. **JDK 21** — `mvnw` needs it and it is often not on `PATH`:
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
   export PATH="$JAVA_HOME/bin:$PATH"
   ```
   Install if missing: `sudo apt-get install -y openjdk-21-jdk-headless`
3. **Go** — to build the reference binaries.

## Build (required — binaries are gitignored, not committed)

`server` and `bot-hoster` are NOT in git; you must build them from the current virusgame tree:
```bash
cd ../virusgame/backend
go build -o server .                 # game server
go build -o bot-hoster ./cmd/bot-hoster   # GoBot reference AI
```
The Java bot is rebuilt from source by the harness itself (`mvnw spring-boot:run`), so it
auto-picks-up whatever is in `src/main/resources/nnue_weights.json` — no manual jar needed.

## Fresh code first

Before trusting any parity number, put BOTH repos on fresh code — GoBot's strength and the
game rules live in virusgame; other agents change them concurrently:
```bash
git -C ../virusgame fetch origin && git -C ../virusgame pull --rebase origin main
git fetch origin && git pull --ff-only origin master   # nnue-trainer
```
Then rebuild `server` + `bot-hoster` (previous step). Stale binaries = meaningless result.

## Run

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH="$JAVA_HOME/bin:$PATH"
python3 eval_java_vs_go.py 20      # 20 games; default is 20
```
Output ends with e.g. `=== RESULT: Java 0 - 1 Go over 1 games | Java win rate 0% ===`.
Logs: `server.log`, `gobot.log`, `java_bot.log` in the cwd. `games.db` is auto-created by
the server at `../virusgame/backend/data/games.db` on first start.

Smoke test (verify plumbing, ~1 game): `timeout 300 python3 eval_java_vs_go.py 1`.

## Reading results directly

```bash
python3 -c "import sqlite3;c=sqlite3.connect('../virusgame/backend/data/games.db');\
print(c.execute(\"select player1_name,player2_name,result from games \
where player1_name like 'JavaBot%' and player2_name like 'GoBot%' order by rowid desc limit 5\").fetchall())"
```
`result`: 1 = player1 (Java) won, 2 = player2 (GoBot) won.

## Related — SearchAB (in-repo, no server)

For search-vs-search A/B with the eval held fixed (e.g. proving the TT fix), use the faster
in-process harness — it does NOT involve GoBot or the server:
```bash
./mvnw -q compile
java -cp target/classes com.engine.nnue_trainer.train.SearchAB [games=12] [ms=500]
```
It plays the new `SearchEngine` vs `BaselineSearchEngine` (pre-#38), same distilled NNUE
eval, same time budget — any gap is pure search quality. Toggle `USE_TT` / `USE_QUIESCENCE`
in the search to bisect. Use SearchAB to prove the search got stronger; use THIS skill to
prove the whole bot beats GoBot.

## Gotchas (learned the hard way)

- `java: command not found` → set `JAVA_HOME` (step 2). `mvnw` silently fails otherwise.
- `./server: No such file` or `bot-hoster` missing → they're gitignored; `go build` them.
- Win rate looks wrong / all losses → confirm you rebuilt binaries AFTER pulling virusgame.
  A known-broken search (PR #38's TT bug) legitimately loses ~0/12 even at 2x time.
- The harness kills its whole process group on exit; if it hangs, `pkill -f spring-boot:run`.
