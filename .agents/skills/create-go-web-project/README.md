# Create Go Web Project

A Claude skill for scaffolding Go web projects with various configurations.

## Features

- **Project Types**: Pure API, Vanilla HTML/JS, React (Vite), Telegram Bot, Telegram + Web
- **Authentication**: Google OAuth2, Telegram WebApp Auth
- **Database**: SQLite + sqlc + goose migrations (pure Go, no CGO)
- **Storage**: Cloudflare R2 / AWS S3
- **Deployment**: Docker multi-stage builds, GitHub Actions, Portainer webhook
- **Extras**: CORS, rate limiting, WebSocket, graceful shutdown, structured logging (slog)

## Usage

Ask Claude to create a new Go web project:

```
Create a new Go web project called "my-app" with React frontend, Google OAuth, and SQLite database
```

Claude will ask follow-up questions to customize the project to your needs.

## What Gets Generated

- Complete project structure with `cmd/`, `internal/`, `sql/`, `web/` directories
- Dockerfile with multi-stage build
- docker-compose.yml with Traefik labels
- GitHub Actions workflows for CI/CD
- Environment templates and documentation
- Optional: Litestream backup, R2 storage, Telegram integration

## Requirements

- Go 1.25+
- Docker (for deployment)
- `gh` CLI (optional, for GitHub repo creation)

## License

MIT
