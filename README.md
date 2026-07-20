# NNUE Trainer & Bot for Virus Game (Java)

This is a Java Spring Boot application managed and built using Maven. It serves as an NNUE (Efficiently Updatable Neural Network) trainer and bot for the Virus game.

## Architecture

The project is structured into several key components:
- **Board**: Contains the logic, state representation, and validation for the Virus game board.
- **Protocol**: Implements a WebSocket JSON client to interact with the multiplayer game server.
- **NNUE**: Handles the neural network architecture, inference, and training processes for evaluating board states.

## Protocol

The bot communicates with the game server via a WebSocket connection. It sends and receives multiplayer JSON messages, which include game state updates, turn notifications, move submissions, and other events required to play a live game.

## Build & Run

To build the project, use the included Maven Wrapper:

```bash
./mvnw clean package
```

To run the application, you can execute the generated JAR or use the Spring Boot run command:

```bash
./mvnw spring-boot:run
```

To compile and run tests:

```bash
./mvnw compile
./mvnw test
```

*Note: The project requires Java 21 for compilation and runtime.*

## Rules of Virus Game

The Virus game is a grid-based strategy game where players aim to control the board. The key rules are as follows:

- **Move Types**: Players can make two types of moves:
  - **Grow**: Expanding to an adjacent empty cell.
  - **Attack**: Taking over an opponent's adjacent cell.
- **Moves per Turn**: Each player is allowed to make 3 moves per turn.
- **Base Connection Requirement**: Cells must maintain an active connection to a base; isolated cells may be neutralized or lost.
- **Neutral Placement**: Neutral cells can be placed on the board according to specific game dynamics, often serving as obstacles or strategic points.
