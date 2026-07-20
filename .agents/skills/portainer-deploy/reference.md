# Portainer Deploy - Reference

## Traefik Labels Reference

### Basic HTTP/HTTPS Service

```yaml
labels:
  # Enable Traefik
  - "traefik.enable=true"

  # Router configuration
  - "traefik.http.routers.myapp.rule=Host(`example.com`)"
  - "traefik.http.routers.myapp.entrypoints=websecure"
  - "traefik.http.routers.myapp.tls.certresolver=myresolver"

  # Service port (which port inside container to route to)
  - "traefik.http.services.myapp.loadbalancer.server.port=8080"
```

### WebSocket Support

Add these middleware labels:

```yaml
labels:
  # ... basic labels above ...

  # WebSocket middleware
  - "traefik.http.middlewares.myapp-ws.headers.customrequestheaders.Connection=upgrade"
  - "traefik.http.middlewares.myapp-ws.headers.customrequestheaders.Upgrade=websocket"

  # Apply middleware to router
  - "traefik.http.routers.myapp.middlewares=myapp-ws"
```

### Multiple Domains

```yaml
labels:
  - "traefik.enable=true"
  - "traefik.http.routers.myapp.rule=Host(`example.com`) || Host(`www.example.com`)"
  # ... rest of labels ...
```

### Path-based Routing

```yaml
labels:
  - "traefik.enable=true"
  - "traefik.http.routers.myapp.rule=Host(`example.com`) && PathPrefix(`/api`)"
  # ... rest of labels ...
```

### Custom Entrypoints

If you have custom Traefik entrypoints:

```yaml
labels:
  - "traefik.http.routers.myapp.entrypoints=web,websecure"  # Both HTTP and HTTPS
```

---

## Environment Variables

### Common Variables for docker-compose.yml

**With Traefik:**
- `HOSTNAME` - Your domain name (e.g., `example.com`)
- `NETWORK_NAME` - Traefik network name (e.g., `traefik_network`)

**Application-specific:**
- `APP_ENV` - Environment (production, staging, development)
- `PORT` - Application port (usually 8080)
- Database credentials, API keys, etc.

### Setting Environment Variables in Portainer

**Method 1: Stack Environment Variables**
1. Go to your stack in Portainer
2. Scroll to "Environment variables"
3. Add key-value pairs:
   ```
   HOSTNAME=example.com
   NETWORK_NAME=traefik_network
   DB_PASSWORD=secret123
   ```

**Method 2: .env file**
Create `.env` file in your stack directory:
```env
HOSTNAME=example.com
NETWORK_NAME=traefik_network
DB_PASSWORD=secret123
```

---

## GitHub Actions Variables Reference

### Automatic Variables

These are always available in GitHub Actions:

- `${{ github.actor }}` - User who triggered the workflow
- `${{ github.repository }}` - Full repo name (owner/repo)
- `${{ github.repository_owner }}` - Repository owner
- `${{ github.event.repository.name }}` - Repository name
- `${{ github.sha }}` - Full commit SHA
- `${{ github.ref }}` - Branch ref (refs/heads/main)
- `${{ github.ref_name }}` - Branch name (main)

### Secrets

Add these to your repository secrets:

**Required:**
- `PORTAINER_REDEPLOY_HOOK` - Portainer webhook URL

**Optional:**
- `PORTAINER_REDEPLOY_HOOK_2` - Second stack webhook
- `PORTAINER_REDEPLOY_HOOK_BOT` - Bot stack webhook
- Custom application secrets

**How to get Portainer webhook URL:**
1. Open stack in Portainer
2. Click "Webhooks" button
3. Copy the webhook URL (format: `https://portainer.example.com/api/stacks/webhooks/xxx`)

---

## Docker Build Arguments

### COMMIT_SHA

Pass commit SHA to the build:

**In GitHub Actions:**
```yaml
- name: Build and push Docker image
  uses: docker/build-push-action@v5
  with:
    build-args: |
      COMMIT_SHA=${{ github.sha }}
```

**In Dockerfile:**
```dockerfile
ARG COMMIT_SHA=unknown

# Use in Go build
RUN go build -ldflags "-X main.commitSHA=${COMMIT_SHA}" -o app

# Or replace in HTML
RUN sed -i "s/__COMMIT_SHA__/${COMMIT_SHA}/g" /app/index.html
```

### Other Common Build Args

```dockerfile
ARG GO_VERSION=1.24
ARG APP_VERSION=1.0.0
ARG BUILD_DATE
```

---

## Network Configuration

### External Network (Traefik)

```yaml
networks:
  traefik_network:
    external: true
```

This network must already exist (created by Traefik).

**Create manually if needed:**
```bash
docker network create traefik_network
```

### Internal Networks

For service-to-service communication:

```yaml
services:
  app:
    networks:
      - internal
      - traefik_network

  database:
    networks:
      - internal  # Not exposed to Traefik

networks:
  internal:
    driver: bridge
  traefik_network:
    external: true
```

---

## Volume Configuration

### Named Volumes

```yaml
services:
  app:
    volumes:
      - app-data:/app/data

volumes:
  app-data:
```

Managed by Docker, survives container removal.

### Bind Mounts

```yaml
services:
  app:
    volumes:
      - ./data:/app/data
      - ./config.yml:/app/config.yml:ro  # Read-only
```

Maps host directory/file to container.

### Volume Permissions

If permission issues occur, you might need to set ownership:

```dockerfile
RUN mkdir -p /app/data && chown -R appuser:appuser /app/data
USER appuser
```

---

## Troubleshooting

### GitHub Actions

**Image not found:**
- Verify image was pushed successfully
- Check ghcr.io permissions (public or token access)
- Ensure GITHUB_TOKEN has `packages: write` permission

**Webhook not triggering:**
- Check secret name matches (`PORTAINER_REDEPLOY_HOOK`)
- Verify webhook URL is correct
- Test webhook manually: `curl -X POST <webhook-url>`

**sed not updating:**
- Check image format in docker-compose.yml
- Verify sed pattern matches exactly
- Use `-i` flag for in-place editing (macOS might need `-i ''`)

### Portainer

**Stack won't update:**
- Check webhook URL is correct
- Verify image tag in deploy branch
- Check Portainer can pull from ghcr.io
- Review Portainer logs

**Container won't start:**
- Check environment variables are set
- Verify volumes exist and have correct permissions
- Review container logs in Portainer

### Traefik

**404 Not Found:**
- Verify Host rule matches your domain
- Check DNS points to Traefik server
- Ensure container is on correct network

**Certificate issues:**
- Verify certresolver name matches Traefik config
- Check Let's Encrypt rate limits
- Review Traefik logs for ACME errors

**WebSocket connection fails:**
- Ensure WebSocket middleware is configured
- Check browser console for errors
- Verify backend supports WebSocket upgrades

---

## Best Practices

### Security

1. **Never commit secrets** - Use GitHub Secrets and Portainer env vars
2. **Use specific image tags** in production (commit SHA, not `latest`)
3. **Run as non-root user** in containers when possible
4. **Enable HTTPS** - Always use Traefik with TLS
5. **Restrict network access** - Use internal networks for databases

### Deployment

1. **Use deploy branch** - Keeps main branch clean
2. **Tag images with commit SHA** - Enables easy rollback
3. **Test locally first** - `docker build` and `docker-compose up`
4. **Monitor logs** - Check GitHub Actions and Portainer logs
5. **Have rollback plan** - Keep previous image tags

### Docker Compose

1. **Use environment variables** - Don't hardcode values
2. **Separate secrets from compose file** - Use .env or Portainer
3. **Name containers** - Easier to identify and debug
4. **Set restart policies** - `unless-stopped` for production
5. **Configure healthchecks** - Ensure services are actually ready

### GitHub Actions

1. **Use workflow_dispatch** - Enable manual triggers
2. **Fail fast** - `set -e` in bash scripts
3. **Add comments** - Document complex sed patterns
4. **Version lock actions** - Use @v4 not @latest
5. **Minimal permissions** - Only grant what's needed

---

## Quick Reference Commands

### Local Testing

```bash
# Build locally
docker build -t myapp:test .

# Run locally
docker run -p 8080:8080 myapp:test

# Test with compose
docker-compose up

# Check logs
docker logs <container-name>

# Shell into container
docker exec -it <container-name> sh
```

### Git Operations

```bash
# Check deploy branch
git fetch origin deploy
git log origin/deploy

# Manual update (for testing)
git checkout -B deploy
sed -i "s|:latest|:$(git rev-parse --short HEAD)|g" docker-compose.yml
git add docker-compose.yml
git commit -m "test: Update image tag"
git push origin deploy --force
```

### Portainer Webhook

```bash
# Test webhook manually
curl -X POST "https://portainer.example.com/api/stacks/webhooks/xxx"

# With authentication (if needed)
curl -X POST \
  -H "Authorization: Bearer <token>" \
  "https://portainer.example.com/api/stacks/webhooks/xxx"
```

### Docker Registry

```bash
# List images
gh api /user/packages/container/REPO_NAME/versions

# Delete old images
gh api -X DELETE /user/packages/container/REPO_NAME/versions/VERSION_ID

# Pull image
docker pull ghcr.io/OWNER/REPO:TAG
```
