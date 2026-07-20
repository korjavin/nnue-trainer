# Litestream Backup Skill

This skill helps set up SQLite database replication to S3-compatible storage using [Litestream](https://litestream.io/):
- Cloudflare R2
- AWS S3
- MinIO
- Any S3-compatible storage

## When to Use This Skill

Use this skill when the user wants to:
- Add backup/replication for their SQLite database
- Set up Litestream with Cloudflare R2
- Configure continuous SQLite replication
- Add disaster recovery for SQLite-based applications
- Integrate Litestream into Docker Compose

## Prerequisites

1. **SQLite database in WAL mode** - Litestream requires WAL mode to work
2. **Docker Compose setup** - Litestream runs as a sidecar container
3. **S3-compatible storage** - Cloudflare R2, AWS S3, MinIO, etc.

## Implementation Process

### 1. Analyze Project Structure

First, understand the current project setup:

```bash
# Check for existing files
ls -la docker-compose.yml
# Check database location
grep -r "sqlite\|\.db" --include="*.go" --include="*.py" --include="*.js"
```

Determine:
- Where is the SQLite database file located?
- Is WAL mode enabled?
- Is there an existing docker-compose.yml?
- What volume is used for the database?

### 2. Interactive Questions

Ask the user:

1. **Storage provider**: "Which S3-compatible storage will you use? (R2/S3/MinIO)"
2. **Database path**: "What is the path to your SQLite database inside the container?" (e.g., `/app/data/meds.db`)
3. **Sync frequency**: "How often should changes be synced? (default: 1h for infrequent changes, 10s for frequent)"
4. **Snapshot frequency**: "How often to create full snapshots? (default: 24h)"
5. **Auto-restore**: "Do you want to auto-restore the database from backup if it doesn't exist? (recommended for disaster recovery)"

### 3. Enable WAL Mode in Application Code

**CRITICAL**: Litestream requires SQLite to be in WAL mode.

**For Go applications:**
```go
// After opening the database connection
if _, err := db.Exec("PRAGMA journal_mode=WAL"); err != nil {
    return fmt.Errorf("failed to enable WAL mode: %w", err)
}
```

**For Python applications:**
```python
import sqlite3
conn = sqlite3.connect('database.db')
conn.execute('PRAGMA journal_mode=WAL')
```

**For Node.js applications:**
```javascript
db.exec('PRAGMA journal_mode=WAL');
```

### 4. Add Litestream to docker-compose.yml

Add as a sidecar container that shares the database volume. Use dynamic config generation to avoid file mount issues with Portainer:

```yaml
  # Litestream - SQLite replication to S3-compatible storage
  litestream:
    image: litestream/litestream:latest
    container_name: ${PROJECT_NAME}-litestream
    restart: unless-stopped
    entrypoint: /bin/sh
    command:
      - -c
      - |
        cat << EOF > /tmp/litestream.yml
        dbs:
          - path: ${DB_PATH}
            replicas:
              - type: s3
                bucket: $${R2_BUCKET}
                path: ${REPLICA_PATH}
                endpoint: $${R2_ENDPOINT}
                sync-interval: ${SYNC_INTERVAL}
                snapshot-interval: ${SNAPSHOT_INTERVAL}
                retention: ${RETENTION}
        EOF
        exec litestream replicate -config /tmp/litestream.yml ${RESTORE_FLAG}
    volumes:
      - ${VOLUME_NAME}:${VOLUME_MOUNT_PATH}
    environment:
      # AWS SDK expects these standard env vars - map custom vars to them
      - AWS_ACCESS_KEY_ID=${CUSTOM_ACCESS_KEY_ID}
      - AWS_SECRET_ACCESS_KEY=${CUSTOM_SECRET_ACCESS_KEY}
      - R2_ENDPOINT=${R2_ENDPOINT}
      - R2_BUCKET=${R2_BUCKET}
    depends_on:
      - ${MAIN_SERVICE_NAME}
```

**Template variables to replace:**
- `${PROJECT_NAME}` - Project/container name prefix
- `${DB_PATH}` - Full path to database inside container (e.g., `/app/data/meds.db`)
- `${REPLICA_PATH}` - Path prefix in bucket (e.g., `myapp` creates `myapp/` folder)
- `${SYNC_INTERVAL}` - How often to sync WAL (e.g., `1h`, `10s`)
- `${SNAPSHOT_INTERVAL}` - How often to create full snapshots (e.g., `24h`)
- `${RETENTION}` - How long to keep old snapshots (e.g., `168h` = 7 days)
- `${VOLUME_NAME}` - Docker volume name (must match main app)
- `${VOLUME_MOUNT_PATH}` - Where volume is mounted (e.g., `/app/data`)
- `${MAIN_SERVICE_NAME}` - Name of the main application service
- `${RESTORE_FLAG}` - If auto-restore enabled: `-restore-if-db-not-exists`, otherwise empty

### Auto-Restore Option

The `-restore-if-db-not-exists` flag enables automatic disaster recovery:

```yaml
exec litestream replicate -config /tmp/litestream.yml -restore-if-db-not-exists
```

**How it works:**
- On container start, Litestream checks if the database file exists
- If **database exists**: Normal replication continues
- If **database doesn't exist**: Litestream restores from the latest backup in S3/R2 before starting replication
- If **no backup exists and database doesn't exist**: Proceeds normally (app creates fresh database)

**When to use:**
- ✅ Disaster recovery - automatically restore on new server
- ✅ Container recreation - restore when volume is lost
- ✅ Migration - easily move to new infrastructure

**When NOT to use:**
- ❌ Development environments where you want fresh databases
- ❌ When you need manual control over restore process

### 5. Explain Environment Variables

**IMPORTANT**: The AWS SDK (used by Litestream) requires standard environment variable names.
If you use custom names (e.g., `LITESTREAM_R2_ACCESS_KEY_ID`), you must map them to
`AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` in the container's environment section.

Remind the user to add these environment variables in Portainer:

```
Required Environment Variables:

1. AWS_ACCESS_KEY_ID (or CUSTOM_ACCESS_KEY_ID mapped to this)
   - For R2: Get from Cloudflare Dashboard → R2 → Manage R2 API Tokens
   - For AWS S3: Your AWS Access Key ID
   - NOTE: AWS SDK does NOT read LITESTREAM_* or R2_* prefixed vars

2. AWS_SECRET_ACCESS_KEY (or CUSTOM_SECRET_ACCESS_KEY mapped to this)
   - For R2: Get from Cloudflare Dashboard → R2 → Manage R2 API Tokens
   - For AWS S3: Your AWS Secret Access Key

3. R2_ENDPOINT (for Cloudflare R2 only)
   - Format: https://<account-id>.r2.cloudflarestorage.com
   - Get account ID from Cloudflare Dashboard URL

4. R2_BUCKET
   - Your bucket name (must exist before first run)
```

### 6. Getting Cloudflare R2 Credentials

Provide step-by-step instructions:

```
To get Cloudflare R2 credentials:

1. Go to Cloudflare Dashboard → R2 → Manage R2 API Tokens
2. Click "Create API Token"
3. Set permissions to "Object Read & Write"
4. Optionally restrict to specific bucket
5. Copy the Access Key ID and Secret Access Key
6. Your account ID is in the URL: dash.cloudflare.com/<account-id>/r2
```

## Configuration Recommendations

| Use Case | sync-interval | snapshot-interval | retention |
|----------|---------------|-------------------|-----------|
| Personal app, rare changes | 1h | 24h | 168h (7 days) |
| Active app, frequent writes | 10s | 1h | 72h (3 days) |
| Critical data | 1s | 6h | 720h (30 days) |

## Troubleshooting

**"is a directory" error:**
- Config file mount doesn't work with Portainer
- Use the dynamic config generation approach (heredoc)

**"failed to find WAL" error:**
- SQLite is not in WAL mode
- Add `PRAGMA journal_mode=WAL` to application code

**"no EC2 IMDS role found" error (falling back to IMDS):**
- This means credentials are not being read correctly
- The AWS SDK expects `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`
- If you use custom env var names (e.g., `LITESTREAM_R2_ACCESS_KEY_ID`), map them:
  ```yaml
  environment:
    - AWS_ACCESS_KEY_ID=${CUSTOM_ACCESS_KEY_ID}
    - AWS_SECRET_ACCESS_KEY=${CUSTOM_SECRET_ACCESS_KEY}
  ```

**Replication not starting:**
- Check credentials are correct
- Verify bucket exists
- Check endpoint URL format

**Permission denied:**
- Ensure the volume is shared correctly between main app and Litestream
- Check file permissions on the database

## Implementation Checklist

After running this skill, verify:

- [ ] WAL mode enabled in application code (`PRAGMA journal_mode=WAL`)
- [ ] Litestream service added to docker-compose.yml
- [ ] Correct volume shared between app and Litestream
- [ ] AWS SDK credential env vars are correctly mapped (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`)
- [ ] Environment variables documented for user
- [ ] User reminded about R2/S3 credential setup
- [ ] Sync and snapshot intervals configured appropriately
- [ ] Auto-restore flag added if disaster recovery is desired (`-restore-if-db-not-exists`)

## Next Steps for User

After setup:

1. Create R2 bucket in Cloudflare Dashboard (or S3 bucket)
2. Create API token with Object Read & Write permissions
3. Add environment variables to Portainer
4. Deploy the stack
5. Check Litestream container logs to verify replication
6. Verify data in R2/S3 bucket

## Verification

Tell user how to verify it's working:

```bash
# Check Litestream logs
docker logs <project>-litestream

# Should see:
# - "initialized db" message
# - "replicating to" with correct settings
# - "compaction complete" after first sync
```
