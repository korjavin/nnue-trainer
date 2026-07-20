---
name: compose-service-setup
description: Create a git-ops compose project for a 3rd-party service with GitHub Actions, Portainer webhooks, Traefik support, and optional GHCR image vendoring. Use when the user wants to deploy a 3rd-party service (e.g. Gitea, Vaultwarden, Nextcloud) via Portainer in a git-ops style, or wants to vendor/mirror a Docker image to GHCR.
---

# Compose Service Setup Skill

Creates a complete `{service}-compose` git-ops project for deploying a 3rd-party Docker service via Portainer with GitHub Actions automation.

## When to Use

- User wants to self-host a service using the same pattern as their existing compose projects
- User provides a service name + Docker image and wants a ready-to-deploy repo
- User wants to vendor/mirror a 3rd-party image to GHCR to pin its version
- User wants Traefik routing for a new service

## Step 1: Gather Information

Before generating any files, collect:

**Required:**
- **Service name** (e.g. `vaultwarden`, `nextcloud`) — used for repo name `{service}-compose`, container names, Traefik router names, network names, volume names
- **Upstream Docker image** (e.g. `vaultwarden/server:latest`, `ghcr.io/paperless-ngx/paperless-ngx:latest`)

**Ask if not provided:**
1. **Traefik?** Does the service expose an HTTP web UI? (→ Traefik labels needed)
2. **Port?** What container port does the web UI listen on?
3. **OAuth2 protection?** Should the service be protected by `auth-errors@docker,forward-auth@docker` middlewares?
4. **GHCR vendoring?** Should the upstream image be mirrored to GHCR on a schedule to pin versions?
5. **Additional services?** Does this service need a database (postgres), cache (redis), or other sidecars?
6. **Extra ports?** Does it expose non-HTTP ports (e.g. SSH, SMTP)?
7. **Volumes/data dirs?** What data needs to be persisted?
8. **Output dir?** Where to create the project (default: current directory, naming convention: `{service}-compose`)

**Research the service** (if unsure about env vars, ports, volumes):
- Use WebSearch/WebFetch to look up the service's official Docker documentation
- Check Docker Hub, GitHub repo, or official docs for required environment variables
- Note which env vars are required vs optional, and what sensible defaults are

## Step 2: Project Structure

Create at `{service}-compose/`:

```
{service}-compose/
├── .github/
│   └── workflows/
│       ├── deploy.yml          # Always create
│       └── vendor-images.yml   # Only if GHCR vendoring requested
├── docker-compose.yml
├── .env.example
└── README.md
```

## Step 3: docker-compose.yml

### Key Conventions

**All values configurable via env vars** with sensible defaults:
```yaml
image: ${SERVICE_IMAGE:-upstream/image:tag}
container_name: ${SERVICE_CONTAINER_NAME:-service-name}
```

**Traefik network** — always use `TRAEFIK_NETWORK_NAME` env var:
```yaml
networks:
  traefik_network:
    external: true
    name: ${TRAEFIK_NETWORK_NAME:-traefik_default}
```

The `traefik.docker.network` label must also reference the env var so Traefik knows which network to use for routing:
```yaml
- "traefik.docker.network=${TRAEFIK_NETWORK_NAME:-traefik_default}"
```

**Two-network pattern** (when sidecars exist):
- `internal` network — databases, caches (never exposed to Traefik)
- `traefik_network` — only the web-facing service

**Volume naming** — use env vars:
```yaml
volumes:
  db-data:
    name: ${DB_VOLUME_NAME:-service_db-data}
```

### Traefik Labels Template (for web services)

```yaml
labels:
  - "traefik.enable=true"
  - "traefik.docker.network=${TRAEFIK_NETWORK_NAME:-traefik_default}"
  - "traefik.http.routers.{service}.rule=Host(`${SERVICE_HOST}`)"
  - "traefik.http.routers.{service}.entrypoints=websecure"
  - "traefik.http.routers.{service}.tls.certresolver=${TRAEFIK_CERTRESOLVER:-myresolver}"
  - "traefik.http.services.{service}.loadbalancer.server.port={PORT}"
  # Optional OAuth2 protection:
  # - "traefik.http.routers.{service}.middlewares=auth-errors@docker,forward-auth@docker"
```

### Full Template with Sidecar Services

```yaml
version: '3.8'

services:
  {service}:
    image: ${SERVICE_IMAGE:-upstream/image:tag}
    container_name: ${SERVICE_CONTAINER_NAME:-{service}}
    restart: unless-stopped
    depends_on:
      - {service}-db     # if applicable
    networks:
      - internal         # if sidecars exist
      - traefik_network
    volumes:
      - ${DATA_PATH:-./data}:/data
    environment:
      SOME_VAR: ${SOME_VAR:-default}
      SECRET_VAR: ${SECRET_VAR}
    labels:
      - "traefik.enable=true"
      - "traefik.docker.network=${TRAEFIK_NETWORK_NAME:-traefik_default}"
      - "traefik.http.routers.{service}.rule=Host(`${SERVICE_HOST}`)"
      - "traefik.http.routers.{service}.entrypoints=websecure"
      - "traefik.http.routers.{service}.tls.certresolver=${TRAEFIK_CERTRESOLVER:-myresolver}"
      - "traefik.http.services.{service}.loadbalancer.server.port={PORT}"

  # --- Database (if needed) ---
  {service}-db:
    image: ${POSTGRES_IMAGE:-postgres:16-alpine}
    container_name: ${DB_CONTAINER_NAME:-{service}-db}
    restart: unless-stopped
    networks:
      - internal
    volumes:
      - db-data:/var/lib/postgresql/data
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-{service}}
      POSTGRES_USER: ${POSTGRES_USER:-{service}}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}

networks:
  internal:
    name: ${INTERNAL_NETWORK_NAME:-{service}_internal}
  traefik_network:
    external: true
    name: ${TRAEFIK_NETWORK_NAME:-traefik_default}

volumes:
  db-data:
    name: ${DB_VOLUME_NAME:-{service}_db-data}
```

**If GHCR vendoring is used**, replace the upstream image reference:
```yaml
image: ${SERVICE_IMAGE:-ghcr.io/OWNER/{service}-vendor:latest}
```

## Step 4: .env.example

Document all environment variables, grouped logically. Pattern from existing projects:

```bash
# ===========================================
# {Service} Configuration
# ===========================================

# --- Docker Images ---
# Pin to specific digest for reproducibility:
# Example: upstream/image:tag@sha256:<digest>
SERVICE_IMAGE=upstream/image:tag

# --- Container Names ---
SERVICE_CONTAINER_NAME={service}

# --- Network Names ---
TRAEFIK_NETWORK_NAME=traefik_default
# INTERNAL_NETWORK_NAME={service}_internal  # only if internal network exists

# --- Volume Names ---
# DB_VOLUME_NAME={service}_db-data          # only if DB volume exists

# --- Traefik Configuration ---
SERVICE_HOST={service}.yourdomain.com
TRAEFIK_CERTRESOLVER=myresolver

# --- Required Secrets ---
# Generate with: openssl rand -hex 32
SECRET_VAR=change-me

# --- Database (if applicable) ---
POSTGRES_DB={service}
POSTGRES_USER={service}
POSTGRES_PASSWORD=change-me-to-strong-password

# --- Application Configuration ---
# ... service-specific vars with comments and generate commands ...
```

## Step 5: GitHub Actions Workflows

### deploy.yml (always create — no image build for 3rd-party services)

```yaml
name: Deploy {Service} Stack

on:
  push:
    branches:
      - master
  workflow_dispatch:

jobs:
  deploy:
    runs-on: ubuntu-latest
    permissions:
      contents: write

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Create/Update deploy branch
        run: |
          git config user.name "GitHub Actions Bot"
          git config user.email "actions@github.com"
          git checkout -B deploy
          git push origin deploy --force

      - name: Trigger Portainer Redeploy Webhook
        uses: distributhor/workflow-webhook@2381f0e9c7b6bf061fb1405bd0804b8706116369 # v3.0.8
        with:
          webhook_url: ${{ secrets.PORTAINER_REDEPLOY_HOOK }}
          webhook_secret: "trigger"
```

**Note:** For 3rd-party services, the image tag in docker-compose.yml is managed via the `SERVICE_IMAGE` Portainer env var, not by CI. The deploy branch just synchronizes the compose file and triggers Portainer to reload.

### vendor-images.yml (only when user requests GHCR vendoring)

This mirrors the upstream image to GHCR on a schedule, giving you:
- Version pinning (the digest is captured at mirror time)
- Protection from upstream image deletion or registry outages
- A known GHCR URL to reference in docker-compose.yml

```yaml
name: Vendor Images to GHCR

on:
  schedule:
    - cron: '0 4 * * 1'   # Weekly on Monday at 04:00 UTC
  workflow_dispatch:

jobs:
  vendor:
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

      - name: Pull and re-push {service} image
        env:
          UPSTREAM_IMAGE: upstream/image:tag
          GHCR_IMAGE: ghcr.io/${{ github.repository_owner }}/{service}-vendor
        run: |
          docker pull "${UPSTREAM_IMAGE}"
          DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' "${UPSTREAM_IMAGE}" | cut -d@ -f2)
          docker tag "${UPSTREAM_IMAGE}" "${GHCR_IMAGE}:latest"
          docker push "${GHCR_IMAGE}:latest"
          echo "Vendored ${UPSTREAM_IMAGE} → ${GHCR_IMAGE}:latest (digest: ${DIGEST})"

      # Repeat the pull/push block for each additional image (e.g. postgres, redis sidecars)
      # Only vendor images you don't control — skip official images if comfortable with Docker Hub

      - name: Update docker-compose.yml with vendored image references
        run: |
          git config user.name "GitHub Actions Bot"
          git config user.email "actions@github.com"
          git checkout -B deploy

          # Capture digest after push for traceability
          DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' "ghcr.io/${{ github.repository_owner }}/{service}-vendor:latest" | cut -d@ -f2)

          # Update .env.example comment with latest digest (for reference)
          # The actual image is set in Portainer env vars by the operator
          echo "Latest vendored digest: ghcr.io/${{ github.repository_owner }}/{service}-vendor@${DIGEST}"

          git push origin deploy --force

      - name: Trigger Portainer Redeploy Webhook
        uses: distributhor/workflow-webhook@2381f0e9c7b6bf061fb1405bd0804b8706116369 # v3.0.8
        with:
          webhook_url: ${{ secrets.PORTAINER_REDEPLOY_HOOK }}
          webhook_secret: "trigger"
```

**When to vendor which images:**
- Vendor the **main service image** when the upstream registry is less reliable (quay.io, custom registries, etc.)
- Vendor **sidecar images** (postgres, redis) only if the user specifically requests it — Docker Hub official images are generally stable
- Always note the GHCR image path in `.env.example` as the recommended `SERVICE_IMAGE` value

## Step 6: README.md

Include:
1. What the service is (one-line description)
2. Prerequisites (Traefik stack running, Portainer, Docker)
3. Quick start (clone → configure Portainer env vars → Portainer creates stack from deploy branch)
4. All environment variables table with descriptions, required/optional, and generation commands for secrets
5. Portainer setup steps: create stack → set env vars → set webhook → configure to watch `deploy` branch
6. GitHub secrets needed: `PORTAINER_REDEPLOY_HOOK`
7. How updates work (git push → Actions → Portainer redeploys)
8. Links to official service documentation

## Step 7: Post-Generation Checklist

After creating files, tell the user:

```
Setup complete! Next steps:

1. Create GitHub repository: {service}-compose
   Push: git init && git add . && git commit -m "init" && git remote add origin ... && git push -u origin master

2. Add GitHub secret:
   Settings → Secrets → Actions → New secret
   Name: PORTAINER_REDEPLOY_HOOK
   Value: (get from Portainer stack → Webhooks)

3. Configure Portainer stack:
   - Repository URL: https://github.com/OWNER/{service}-compose
   - Branch: deploy  ← IMPORTANT: not master
   - Compose path: docker-compose.yml
   - Environment variables: (set all from .env.example, especially secrets)

4. Trigger first deploy:
   - Push to master, or run workflow manually in GitHub Actions
   - Check Actions tab for status

5. (If GHCR vendoring) Package visibility:
   - Go to github.com/OWNER → Packages → {service}-vendor → Settings
   - Set visibility to match your needs (private for internal use)
```

## Conventions Summary

| Thing | Convention | Example |
|-------|-----------|---------|
| Repo name | `{service}-compose` | `vaultwarden-compose` |
| Container name | `${SERVICE_CONTAINER_NAME:-{service}}` | `${VAULTWARDEN_CONTAINER_NAME:-vaultwarden}` |
| Traefik router name | service name, lowercase | `vaultwarden` |
| Internal network name | `${INTERNAL_NETWORK_NAME:-{service}_internal}` | `vaultwarden_internal` |
| Traefik network name | `${TRAEFIK_NETWORK_NAME:-traefik_default}` | always this pattern |
| Volume name | `${DB_VOLUME_NAME:-{service}_db-data}` | `vaultwarden_db-data` |
| Data bind mount | `${DATA_PATH:-./data}` | configurable path |
| GHCR vendor image | `ghcr.io/OWNER/{service}-vendor` | `ghcr.io/myorg/vaultwarden-vendor` |
| Deploy branch | `deploy` | Portainer watches this branch |
| Main branch | `master` | where changes are pushed |
