# Litestream Backup Examples

Real-world examples of Litestream integration patterns.

## Example 1: Medication Tracker Bot (Go + Cloudflare R2)

A Telegram bot with SQLite database backed up to Cloudflare R2.

### Application Code (Go)

Enable WAL mode in the database initialization:

```go
func New(dbPath string) (*Store, error) {
    db, err := sql.Open("sqlite", dbPath)
    if err != nil {
        return nil, fmt.Errorf("failed to open database: %w", err)
    }

    if err := db.Ping(); err != nil {
        return nil, fmt.Errorf("failed to ping database: %w", err)
    }

    // Enable WAL mode for Litestream compatibility
    if _, err := db.Exec("PRAGMA journal_mode=WAL"); err != nil {
        return nil, fmt.Errorf("failed to enable WAL mode: %w", err)
    }

    return &Store{db: db}, nil
}
```

### Docker Compose

```yaml
version: '3'

services:
  medtracker:
    image: ghcr.io/owner/medicationtrackerbot:latest
    container_name: medtracker
    restart: unless-stopped
    volumes:
      - medtracker_data:/app/data
    environment:
      - TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}
      - DB_PATH=/app/data/meds.db

  # Litestream - SQLite replication to Cloudflare R2
  litestream:
    image: litestream/litestream:latest
    container_name: medtracker-litestream
    restart: unless-stopped
    entrypoint: /bin/sh
    command:
      - -c
      - |
        cat << EOF > /tmp/litestream.yml
        dbs:
          - path: /app/data/meds.db
            replicas:
              - type: s3
                bucket: $${R2_BUCKET}
                path: medtracker
                endpoint: $${R2_ENDPOINT}
                sync-interval: 1h
                snapshot-interval: 24h
                retention: 168h
        EOF
        exec litestream replicate -config /tmp/litestream.yml
    volumes:
      - medtracker_data:/app/data
    environment:
      - LITESTREAM_ACCESS_KEY_ID=${LITESTREAM_ACCESS_KEY_ID}
      - LITESTREAM_SECRET_ACCESS_KEY=${LITESTREAM_SECRET_ACCESS_KEY}
      - R2_ENDPOINT=${R2_ENDPOINT}
      - R2_BUCKET=${R2_BUCKET}
    depends_on:
      - medtracker

volumes:
  medtracker_data:
```

### Portainer Environment Variables

```
LITESTREAM_ACCESS_KEY_ID=<your-r2-access-key>
LITESTREAM_SECRET_ACCESS_KEY=<your-r2-secret-key>
R2_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
R2_BUCKET=litestream-backups
```

### Verification

```bash
# Check logs after deployment
docker logs medtracker-litestream

# Expected output:
# time=... level=INFO msg=litestream version=v0.5.6
# time=... level=INFO msg="initialized db" path=/app/data/meds.db
# time=... level=INFO msg="replicating to" type=s3 sync-interval=1h0m0s
# time=... level=INFO msg="compaction complete" level=2
```

---

## Example 2: Python Flask App with AWS S3

A Flask web application with SQLite backed up to AWS S3.

### Application Code (Python)

```python
import sqlite3
from flask import Flask, g

app = Flask(__name__)
DATABASE = '/app/data/app.db'

def get_db():
    db = getattr(g, '_database', None)
    if db is None:
        db = g._database = sqlite3.connect(DATABASE)
        # Enable WAL mode for Litestream
        db.execute('PRAGMA journal_mode=WAL')
    return db

@app.teardown_appcontext
def close_connection(exception):
    db = getattr(g, '_database', None)
    if db is not None:
        db.close()
```

### Docker Compose

```yaml
version: '3'

services:
  webapp:
    build: .
    container_name: flask-app
    volumes:
      - app_data:/app/data
    environment:
      - DATABASE_PATH=/app/data/app.db

  litestream:
    image: litestream/litestream:latest
    container_name: flask-litestream
    restart: unless-stopped
    entrypoint: /bin/sh
    command:
      - -c
      - |
        cat << EOF > /tmp/litestream.yml
        dbs:
          - path: /app/data/app.db
            replicas:
              - type: s3
                bucket: $${AWS_BUCKET}
                path: flask-app
                region: $${AWS_REGION}
                sync-interval: 10s
                snapshot-interval: 1h
                retention: 72h
        EOF
        exec litestream replicate -config /tmp/litestream.yml
    volumes:
      - app_data:/app/data
    environment:
      - LITESTREAM_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}
      - LITESTREAM_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}
      - AWS_BUCKET=${AWS_BUCKET}
      - AWS_REGION=${AWS_REGION}
    depends_on:
      - webapp

volumes:
  app_data:
```

---

## Example 3: Node.js Express with MinIO

A self-hosted backup solution using MinIO.

### Application Code (Node.js)

```javascript
const Database = require('better-sqlite3');

const db = new Database('/app/data/app.db');

// Enable WAL mode for Litestream
db.exec('PRAGMA journal_mode = WAL');

module.exports = db;
```

### Docker Compose

```yaml
version: '3'

services:
  api:
    build: .
    container_name: express-api
    volumes:
      - api_data:/app/data

  minio:
    image: minio/minio
    container_name: minio
    command: server /data --console-address ":9001"
    volumes:
      - minio_data:/data
    environment:
      - MINIO_ROOT_USER=minioadmin
      - MINIO_ROOT_PASSWORD=minioadmin

  litestream:
    image: litestream/litestream:latest
    container_name: api-litestream
    restart: unless-stopped
    entrypoint: /bin/sh
    command:
      - -c
      - |
        cat << EOF > /tmp/litestream.yml
        dbs:
          - path: /app/data/app.db
            replicas:
              - type: s3
                bucket: backups
                path: express-api
                endpoint: http://minio:9000
                force-path-style: true
                sync-interval: 30s
                snapshot-interval: 6h
        EOF
        exec litestream replicate -config /tmp/litestream.yml
    volumes:
      - api_data:/app/data
    environment:
      - LITESTREAM_ACCESS_KEY_ID=minioadmin
      - LITESTREAM_SECRET_ACCESS_KEY=minioadmin
    depends_on:
      - api
      - minio

volumes:
  api_data:
  minio_data:
```

---

## Common Patterns

### Pattern: Infrequent Changes (Personal Projects)

```yaml
sync-interval: 1h
snapshot-interval: 24h
retention: 168h  # 7 days
```

### Pattern: Active Application (Production)

```yaml
sync-interval: 10s
snapshot-interval: 1h
retention: 72h  # 3 days
```

### Pattern: Critical Data (Financial/Medical)

```yaml
sync-interval: 1s
snapshot-interval: 6h
retention: 720h  # 30 days
```

### Pattern: With Auto-Restore (Disaster Recovery)

Enable automatic database restoration if the database file doesn't exist (great for new deployments or after data loss):

```yaml
# In the Litestream command section:
command:
  - -c
  - |
    cat << EOF > /tmp/litestream.yml
    dbs:
      - path: /app/data/app.db
        replicas:
          - type: s3
            bucket: $${R2_BUCKET}
            path: myapp
            endpoint: $${R2_ENDPOINT}
            sync-interval: 1h
            snapshot-interval: 24h
            retention: 168h
    EOF
    exec litestream replicate -config /tmp/litestream.yml -restore-if-db-not-exists
```

**Behavior:**
- ✅ Database exists → Normal replication
- ✅ Database missing, backup exists → Auto-restore from backup, then replicate
- ✅ Database missing, no backup → App creates new DB, replication starts

This is ideal for:
- Production deployments where data must survive container recreation
- Migration to new servers
- Disaster recovery scenarios

