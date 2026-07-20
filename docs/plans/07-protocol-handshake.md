# Handshake and Lobby Management

Implement parsing for welcome and lobby messages.

## Tasks

- [ ] Task 1: Implement welcome and lobby message handlers

### Task 1: Implement welcome and lobby message handlers
1. Parse incoming JSON welcome message:
   `{"type": "welcome", "userId": "uuid", "username": "username"}`
2. Handle incoming JSON `bot_wanted` message:
   `{"type": "bot_wanted", "lobbyId": "uuid", "requestId": "uuid", ...}`
3. When `bot_wanted` is received, send `join_lobby` message to the server:
   `{"type": "join_lobby", "lobbyId": "uuid"}`\n