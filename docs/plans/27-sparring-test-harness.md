# Plan: Local Sparring Test Harness against Go Bot

This plan specifies the implementation of auto-challenging logic in the Java bot and the creation of an automated Python sparring harness to run match play against the Go bot player located in `../virusgame`.

## 1. Goal
Enable automated model training and testing by letting the Java bot spar against the Go bot player in a local backend instance, saving the resulting game trajectories to SQLite for future training runs.

## 2. Requirements

### 2.1 Java Bot Auto-Challenging Logic (`HandshakeHandler.java`)
- Support parsing `"users_update"` messages from the server:
  - Register `UsersUpdateMessage.java` as a Jackson subtype of `BaseMessage`.
  - When `UsersUpdateMessage` is received:
    - Search the user list for any player whose username contains `"go"` or `"Go"` (case-insensitive) or starts with `"bot"`, and who is not currently in a game (`inGame == false`).
    - Rate limit challenges: Send a `ChallengeMessage` (type `"challenge"`, target `"opponentId"`, `"rows": 12`, `"cols": 12`) at most once every 10 seconds to avoid spamming the server.

### 2.2 Orchestrated Python Sparring Script (`run_sparring.py`)
Create a Python orchestration script `run_sparring.py` in the root folder:
- **Startup Backend**: Start the Go backend server by running `go run main.go` inside `/Users/iv/Projects/virusgame/backend`.
- **Startup Go Bot**: Start the Go bot by running `go run bot-templates/go/*.go` inside `/Users/iv/Projects/virusgame` (configured to connect to `ws://localhost:8080/ws` with username `GoBot`).
- **Startup Java Bot**: Start our Java bot by running `./mvnw spring-boot:run` (configured to connect to `ws://localhost:8080/ws` with username `JavaBot`).
- Run the simulation for a configurable number of games.
- Once games complete, they will automatically be written to the local SQLite database at `../virusgame/backend/data/games.db`, which we can then import using `import_games.py`!

### 2.3 Verification
- Run spotless formatting check.
- Verify compile and tests pass.
