# Litestream Backup Skill

Automates SQLite database backup and replication setup using [Litestream](https://litestream.io/) with S3-compatible storage.

## Features

- 🔄 Continuous SQLite replication to S3-compatible storage
- ☁️ Support for Cloudflare R2, AWS S3, MinIO
- 🐳 Docker Compose sidecar integration
- 📦 Works with Portainer deployments
- ⚡ Configurable sync and snapshot intervals

## Use When

- Setting up backup for SQLite databases
- Adding disaster recovery to SQLite-based apps
- Integrating Litestream into Docker Compose
- Deploying with Cloudflare R2 storage

## Quick Example

After running this skill, your docker-compose.yml will include:

```yaml
services:
  # Your existing app service
  app:
    image: your-app:latest
    volumes:
      - app_data:/app/data

  # Litestream sidecar for backup
  litestream:
    image: litestream/litestream:latest
    container_name: app-litestream
    restart: unless-stopped
    entrypoint: /bin/sh
    command:
      - -c
      - |
        cat << EOF > /tmp/litestream.yml
        dbs:
          - path: /app/data/database.db
            replicas:
              - type: s3
                bucket: $${R2_BUCKET}
                path: myapp
                endpoint: $${R2_ENDPOINT}
                sync-interval: 1h
                snapshot-interval: 24h
                retention: 168h
        EOF
        exec litestream replicate -config /tmp/litestream.yml
    volumes:
      - app_data:/app/data
    environment:
      - LITESTREAM_ACCESS_KEY_ID=${LITESTREAM_ACCESS_KEY_ID}
      - LITESTREAM_SECRET_ACCESS_KEY=${LITESTREAM_SECRET_ACCESS_KEY}
      - R2_ENDPOINT=${R2_ENDPOINT}
      - R2_BUCKET=${R2_BUCKET}
    depends_on:
      - app

volumes:
  app_data:
```

## Environment Variables

| Variable | Description |
|----------|-------------|
| `LITESTREAM_ACCESS_KEY_ID` | S3/R2 Access Key ID |
| `LITESTREAM_SECRET_ACCESS_KEY` | S3/R2 Secret Access Key |
| `R2_ENDPOINT` | R2 endpoint URL (for Cloudflare R2) |
| `R2_BUCKET` | Bucket name |

## Prerequisites

1. SQLite database with WAL mode enabled
2. Docker Compose setup
3. S3-compatible storage (R2, S3, MinIO)

## Getting R2 Credentials

1. Cloudflare Dashboard → R2 → Manage R2 API Tokens
2. Create API Token with "Object Read & Write" permissions
3. Copy Access Key ID and Secret Access Key
4. Endpoint format: `https://<account-id>.r2.cloudflarestorage.com`

## Configuration Options

| Use Case | sync-interval | snapshot-interval |
|----------|---------------|-------------------|
| Rare changes | 1h | 24h |
| Active app | 10s | 1h |
| Critical data | 1s | 6h |

## Related

- [Litestream Documentation](https://litestream.io/)
- [Cloudflare R2](https://developers.cloudflare.com/r2/)
