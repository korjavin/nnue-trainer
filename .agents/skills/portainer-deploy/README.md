# Portainer Deploy Skill

This skill helps automate deployment setup for projects using:
- **GitHub Actions** for CI/CD
- **GitHub Container Registry** (ghcr.io) for Docker images
- **Portainer** for container orchestration
- **Traefik** (optional) for reverse proxy with automatic HTTPS

## What This Skill Does

When you ask Claude to set up deployment, this skill will:

1. **Analyze** your project structure (Dockerfile, existing compose files, etc.)
2. **Ask questions** if needed (domain, services, Traefik usage)
3. **Generate or update** deployment files:
   - `.github/workflows/deploy.yml` - GitHub Actions workflow
   - `docker-compose.yml` - Docker Compose configuration
4. **Remind you** about required GitHub secrets

## How to Use

Simply ask Claude to set up deployment. For example:

```
"Set up automated deployment with Portainer"
"I need CI/CD for this project with Traefik"
"Configure GitHub Actions to deploy to Portainer"
```

Claude will automatically use this skill and guide you through the process.

## Prerequisites

Before using this skill, ensure you have:

1. **Dockerfile** in your project (required)
2. **GitHub repository** with Actions enabled
3. **Portainer** instance running and accessible
4. **Traefik** (optional, if you want automatic HTTPS)

## What Gets Generated

### GitHub Actions Workflow

A workflow that:
- Triggers on push to main/master branch
- Builds Docker image and pushes to ghcr.io
- Updates docker-compose.yml with new image tag
- Commits to `deploy` branch
- Triggers Portainer webhook(s)

### Docker Compose File

Configuration with:
- Image from ghcr.io
- Traefik labels (if requested)
- Volume mounts for persistence
- Environment variables
- Network configuration

## After Setup

You'll need to:

1. **Add GitHub Secret:**
   - Go to repository Settings → Secrets and variables → Actions
   - Add secret: `PORTAINER_REDEPLOY_HOOK`
   - Value: Your Portainer webhook URL

2. **Create Portainer Stack:**
   - Use the generated docker-compose.yml
   - Configure environment variables
   - Get webhook URL from stack settings

3. **Test Deployment:**
   - Push to main/master branch
   - Watch GitHub Actions run
   - Verify Portainer auto-updates

## Examples

This skill is based on real production setups:

- **virusgame** - Multi-service with WebSocket (backend + bot-hoster)
- **madrookbot** - Multi-service (bot + qdrant + tool-api)
- **countrycounter** - Single service with Traefik

See [examples.md](examples.md) for detailed examples.

## Reference

See [reference.md](reference.md) for:
- Traefik labels reference
- Environment variables guide
- Troubleshooting tips
- Best practices

## Files in This Skill

- `SKILL.md` - Main skill instructions for Claude
- `examples.md` - Real-world examples from production projects
- `reference.md` - Comprehensive reference documentation
- `templates/` - Template files for generation
  - `github-workflow.yml` - GitHub Actions template
  - `docker-compose-traefik.yml` - Compose with Traefik
  - `docker-compose-simple.yml` - Simple compose without proxy

## Skill Behavior

The skill will:
- ✅ Automatically detect project structure
- ✅ Ask questions only when necessary
- ✅ Update existing files if they match the pattern
- ✅ Create new files if they don't exist
- ✅ Support single and multi-service deployments
- ✅ Support multiple Portainer stacks
- ✅ Preserve existing configuration where possible

## Limitations

This skill:
- ❌ Cannot create Dockerfile (you need one already)
- ❌ Cannot automate GitHub secrets (you must add manually)
- ❌ Cannot configure Portainer (you must set up stacks manually)
- ❌ Assumes you have Traefik already running (if using Traefik)

## Support

If you encounter issues:

1. Check that Dockerfile exists
2. Verify main branch name (main vs master)
3. Review generated files for customization needs
4. Check examples.md for similar use cases
5. Consult reference.md for detailed configuration

## License

Part of the virusgame project.
