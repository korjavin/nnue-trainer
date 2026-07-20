# Configure Spotless Format Verification

Configure spotless or checkstyle Maven plugin to ensure code formatting consistency.

## Tasks

- [ ] Task 1: Configure Spotless Maven plugin in pom.xml and add formatting check to GitHub Actions

### Task 1: Configure Spotless Maven plugin in pom.xml and add formatting check to GitHub Actions
1. Add `spotless-maven-plugin` configuration to `pom.xml` using google-java-format.
2. Verify you can run `./mvnw spotless:check` and `./mvnw spotless:apply` locally.
3. Update `.github/workflows/ci.yml` to run format checks:
   `./mvnw spotless:check`
   as part of the build pipeline.\n