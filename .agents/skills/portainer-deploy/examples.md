# Portainer Deploy - Examples

This file contains real-world examples from different projects.

## Example 1: Single Service with Traefik (countrycounter)

**Project Structure:**
```
countrycounter/
├── Dockerfile
├── docker-compose.yml
├── backend/
│   └── main.go
└── frontend/
```

**Dockerfile:**
```dockerfile
FROM golang:1.24-alpine AS builder
ARG COMMIT_SHA=unknown
WORKDIR /app
COPY backend/ ./backend/
COPY frontend/ ./frontend/
WORKDIR /app/backend
RUN go mod tidy
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags "-X main.commitSHA=${COMMIT_SHA}" -o /app/main .

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /app
COPY --from=builder /app/main .
COPY --from=builder /app/frontend ./frontend
EXPOSE 8080
CMD ["./main"]
```

**docker-compose.yml:**
```yaml
version: "3.8"

services:
  countrycounter:
    image: ghcr.io/korjavin/countrycounter:latest
    container_name: countrycounter
    volumes:
      - cc-data:/app/backend
    networks:
      - traefik_network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.countrycounter.rule=Host(`${DOMAIN}`)"
      - "traefik.http.routers.countrycounter.entrypoints=websecure"
      - "traefik.http.routers.countrycounter.tls.certresolver=myresolver"
    restart: unless-stopped
    environment:
      - TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}

networks:
  traefik_network:
    external: true

volumes:
  cc-data:
```

**GitHub Actions (.github/workflows/deploy.yml):**
```yaml
name: Deploy Application

on:
  push:
    branches: [ "master" ]
  workflow_dispatch:

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    permissions:
      contents: write
      packages: write

    steps:
    - name: Checkout repository
      uses: actions/checkout@v4

    - name: Log in to GitHub Container Registry
      uses: docker/login-action@v3
      with:
        registry: ghcr.io
        username: ${{ github.actor }}
        password: ${{ secrets.GITHUB_TOKEN }}

    - name: Build and push Docker image
      uses: docker/build-push-action@v5
      with:
        context: .
        push: true
        tags: |
          ghcr.io/korjavin/countrycounter:latest
          ghcr.io/korjavin/countrycounter:${{ github.sha }}
        build-args: |
          COMMIT_SHA=${{ github.sha }}

    - name: Update and commit docker-compose.yml
      run: |
        git config user.name "GitHub Actions Bot"
        git config user.email "actions@github.com"
        git checkout -B deploy
        sed -i "s|image: ghcr.io/korjavin/countrycounter:.*|image: ghcr.io/korjavin/countrycounter:${{ github.sha }}|g" docker-compose.yml
        git add docker-compose.yml
        git commit -m "ci: Update image tag to ${{ github.sha }}"
        git push origin deploy --force

    - name: Trigger Portainer Redeploy Webhook
      uses: distributhor/workflow-webhook@v3
      with:
        webhook_url: ${{ secrets.PORTAINER_REDEPLOY_HOOK }}
        webhook_secret: "trigger"
```

---

## Example 2: Multi-Service (madrookbot)

**Project Structure:**
```
madrookbot/
├── Dockerfile
├── docker-compose.yml
├── main.go (telegram bot)
└── cmd/
    ├── tool-api/
    │   └── main.go
    └── importer/
        └── main.go
```

**Dockerfile (multiple binaries):**
```dockerfile
FROM golang:1.24
WORKDIR /go/src/app
COPY . .
RUN go build -mod=vendor -o /tmp/madrookbot .
RUN go build -mod=vendor -o /tmp/tool-api ./cmd/tool-api
RUN go build -mod=vendor -o /tmp/importer ./cmd/importer

FROM debian:testing
RUN apt-get update && apt-get -y install ca-certificates
RUN mkdir /bot
COPY --from=0 /tmp/madrookbot /bot/madrookbot
COPY --from=0 /tmp/tool-api /bot/tool-api
COPY --from=0 /tmp/importer /bot/importer
WORKDIR /bot
CMD ["/bot/madrookbot"]
```

**docker-compose.yml (3 services from same image):**
```yaml
version: "3.8"

services:
  madrookbot:
    image: ghcr.io/korjavin/madrookbot:latest
    container_name: madrookbot
    environment:
      - BOT_TOKEN=${BOT_TOKEN}
      - GPT_TOKEN=${GPT_TOKEN}
      - QDRANT_HOST=qdrant
      - TOOL_API_URL=http://tool-api:8081
    volumes:
      - ./data:/bot/data
    restart: unless-stopped
    depends_on:
      - qdrant
      - tool-api

  qdrant:
    image: qdrant/qdrant
    container_name: qdrant
    restart: unless-stopped
    ports:
      - "6333:6333"
    volumes:
      - ./qdrant_storage:/qdrant/storage

  tool-api:
    image: ghcr.io/korjavin/madrookbot:latest  # Same image!
    container_name: tool-api
    restart: unless-stopped
    command: ["/bot/tool-api"]  # Override command
    environment:
      - GPT_TOKEN=${GPT_TOKEN}
      - QDRANT_URL=http://qdrant:6333
    ports:
      - "8081:8081"
    depends_on:
      - qdrant
```

**GitHub Actions:**
```yaml
- name: Update and commit docker-compose.yml
  run: |
    git config user.name "GitHub Actions Bot"
    git config user.email "actions@github.com"
    git checkout -B deploy
    # Update image tags from :latest to specific commit SHA
    sed -i "s|ghcr.io/${{ github.repository_owner }}/${{ github.event.repository.name }}:latest|ghcr.io/${{ github.repository_owner }}/${{ github.event.repository.name }}:${{ github.sha }}|g" docker-compose.yml
    git add docker-compose.yml
    git commit -m "ci: Update image tag to ${{ github.sha }}" || echo "No changes to commit"
    git push origin deploy --force
```

---

## Example 3: Multi-Stack Deployment (virusgame)

**Project Structure:**
```
virusgame/
├── Dockerfile
├── docker-compose.yml (main backend)
├── bot-hoster-compose.yml (bot service)
├── backend/
│   ├── main.go
│   └── cmd/
│       └── bot-hoster/
│           └── main.go
└── frontend files
```

**Dockerfile (2 binaries in one image):**
```dockerfile
FROM golang:1.24-alpine AS go-builder
WORKDIR /build
COPY backend/go.mod backend/go.sum ./
RUN go mod download
COPY backend/ .
RUN CGO_ENABLED=0 GOOS=linux go build -o virusgame-server .
RUN CGO_ENABLED=0 GOOS=linux go build -o bot-hoster ./cmd/bot-hoster

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /app
COPY --from=go-builder /build/virusgame-server .
COPY --from=go-builder /build/bot-hoster .
COPY index.html style.css *.js ./
EXPOSE 8080
CMD ["./virusgame-server"]
```

**docker-compose.yml (main stack):**
```yaml
version: "3.8"

services:
  virusgame:
    image: ghcr.io/korjavin/virusgame:latest
    container_name: virusgame-backend
    networks:
      - virusgame-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.virusgame.rule=Host(`${HOSTNAME}`)"
      - "traefik.http.routers.virusgame.entrypoints=websecure"
      - "traefik.http.routers.virusgame.tls.certresolver=myresolver"
      - "traefik.http.services.virusgame.loadbalancer.server.port=8080"
      # WebSocket support
      - "traefik.http.middlewares.virusgame-ws.headers.customrequestheaders.Connection=upgrade"
      - "traefik.http.middlewares.virusgame-ws.headers.customrequestheaders.Upgrade=websocket"
      - "traefik.http.routers.virusgame.middlewares=virusgame-ws"
    volumes:
      - ./backend/data:/app/backend/data
    restart: unless-stopped

networks:
  virusgame-network:
    name: ${NETWORK_NAME}
    external: true
```

**bot-hoster-compose.yml (separate stack):**
```yaml
version: '3.8'

services:
  bot-hoster:
    image: ghcr.io/korjavin/virusgame:latest  # Same image!
    container_name: virusgame-bot-hoster
    command: ["./bot-hoster"]  # Different command
    restart: unless-stopped
    environment:
      - BACKEND_URL=${BACKEND_URL:-ws://virusgame-backend:8080/ws}
      - BOT_POOL_SIZE=${BOT_POOL_SIZE:-10}
```

**GitHub Actions (2 webhooks):**
```yaml
- name: Update and commit docker-compose.yml
  run: |
    git config user.name "GitHub Actions Bot"
    git config user.email "actions@github.com"
    git checkout -B deploy
    # Update BOTH compose files
    sed -i "s|image: ghcr.io/${{ github.repository_owner }}/${{ github.event.repository.name }}:.*|image: ghcr.io/${{ github.repository_owner }}/${{ github.event.repository.name }}:${{ github.sha }}|g" docker-compose.yml
    sed -i "s|image: ghcr.io/${{ github.repository_owner }}/${{ github.event.repository.name }}:.*|image: ghcr.io/${{ github.repository_owner }}/${{ github.event.repository.name }}:${{ github.sha }}|g" bot-hoster-compose.yml

    git add docker-compose.yml bot-hoster-compose.yml
    git commit -m "ci: Update image tag to ${{ github.sha }}"
    git push origin deploy --force

- name: Trigger Portainer Redeploy Webhook
  uses: distributhor/workflow-webhook@v3
  with:
    webhook_url: ${{ secrets.PORTAINER_REDEPLOY_HOOK }}
    webhook_secret: "trigger"

- name: Trigger Portainer Redeploy Webhook Bot
  uses: distributhor/workflow-webhook@v3
  with:
    webhook_url: ${{ secrets.PORTAINER_REDEPLOY_HOOK_BOT }}
    webhook_secret: "trigger"
```

---

## Common Patterns

### Pattern 1: Single Binary
- One Dockerfile builds one binary
- One service in docker-compose.yml
- One Portainer stack

### Pattern 2: Multiple Binaries from Same Codebase
- One Dockerfile builds multiple binaries
- Multiple services using same image with different `command:`
- Can be one or multiple Portainer stacks

### Pattern 3: Service + Dependencies
- Main app + database/cache/queue
- Different images for each service
- All in one docker-compose.yml
- One Portainer stack

### Pattern 4: Microservices
- Multiple related services
- Can be split across multiple compose files
- Multiple Portainer stacks (one per compose file)
- Multiple webhooks in GitHub Actions

---

## sed Patterns Reference

**Single compose file:**
```bash
sed -i "s|image: ghcr.io/korjavin/REPO:.*|image: ghcr.io/korjavin/REPO:${{ github.sha }}|g" docker-compose.yml
```

**Multiple compose files:**
```bash
sed -i "s|image: ghcr.io/korjavin/REPO:.*|image: ghcr.io/korjavin/REPO:${{ github.sha }}|g" docker-compose.yml
sed -i "s|image: ghcr.io/korjavin/REPO:.*|image: ghcr.io/korjavin/REPO:${{ github.sha }}|g" other-compose.yml
```

**Using repository variables:**
```bash
sed -i "s|ghcr.io/${{ github.repository_owner }}/${{ github.event.repository.name }}:latest|ghcr.io/${{ github.repository_owner }}/${{ github.event.repository.name }}:${{ github.sha }}|g" docker-compose.yml
```

This allows the workflow to work for any forked repository without hardcoding owner/repo names.
