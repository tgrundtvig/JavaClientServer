# DevOps Guidelines

## Git Branching

### Trunk-Based Development
```
main (always deployable)
  └── feature/add-character-movement
  └── fix/connection-timeout-bug
```

### Principles
- `main` is primary branch, always working
- Short-lived feature branches
- Merge back frequently
- Delete branches after merge

### Branch Naming
```
feature/short-description
fix/short-description
refactor/short-description
```
Lowercase, hyphens, brief.

## Commit Messages

### Conventional Commits
```
type: short description

Optional longer explanation.
```

### Types
| Type | When |
|------|------|
| feat | New feature |
| fix | Bug fix |
| refactor | Code change, no new feature or fix |
| docs | Documentation only |
| test | Adding or updating tests |
| chore | Build, CI, dependencies |

### Examples
```
feat: add character movement system

fix: prevent negative attribute values

refactor: extract validation to separate class

chore: update maven dependencies
```

### Format
- Lowercase, no period
- Imperative mood ("add" not "added")
- First line under 72 characters

## Pull Requests

### Workflow
1. Create feature branch from main
2. Make commits
3. Open PR when ready
4. CI runs automatically
5. Review (or self-review)
6. Squash merge to main
7. Delete branch

### Squash Merge
- Clean main history
- One commit per feature
- Easy to revert

### PR Description
```markdown
## Summary
Brief description.

## Changes
- Added X
- Modified Y

## Testing
How was this tested?
```

## GitHub Issues

### When to Create
- Features to implement
- Bugs discovered
- Design decisions to discuss

### Labels
| Label | Purpose |
|-------|---------|
| feature | New functionality |
| bug | Something broken |
| design | Needs discussion |

### Linking
- Branch: `feature/42-add-combat`
- Commit: `feat: add combat (#42)`
- PR: "Closes #42" auto-closes issue

### Keep Lightweight
- Not every change needs an issue
- Use for tracking, not bureaucracy

## CI (GitHub Actions)

### On Every Push/PR
```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'

      - name: Build and test
        run: mvn verify
```

### Checks
- Build verification
- Tests run automatically
- Formatting enforced

## CD (Continuous Deployment)

### Start Simple
- Manual trigger initially
- Automate when stable

### Workflow
```yaml
name: Deploy

on:
  workflow_dispatch:
    inputs:
      environment:
        description: 'Target'
        default: 'staging'

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build
        run: mvn package -DskipTests
      - name: Build Docker image
        run: docker build -t app-server .
      - name: Deploy
        run: # deployment steps
```

## Docker Compose

### Structure
```yaml
services:
  server:
    build: ./server
    ports:
      - "8080:8080"
    depends_on:
      - persistence

  persistence:
    image: postgres:16
    volumes:
      - data:/var/lib/postgresql/data
    environment:
      - POSTGRES_DB=app
      - POSTGRES_PASSWORD=${DB_PASSWORD}

volumes:
  data:
```

### Principles
- One process per container
- Environment variables for config
- Volumes for persistence
- Service names for internal networking

### Environments
```
docker-compose.yml          # Base
docker-compose.dev.yml      # Development overrides
docker-compose.prod.yml     # Production overrides
```

## Versioning

### Semantic Versioning
```
MAJOR.MINOR.PATCH

1.0.0 → 1.0.1  (patch: bug fix)
1.0.1 → 1.1.0  (minor: new feature, compatible)
1.1.0 → 2.0.0  (major: breaking change)
```

### During Development
- Start at `0.1.0-SNAPSHOT`
- All modules share version
- Move to `1.0.0` when stable

## Releases

### When to Release
- Milestone reached
- Stable, deployable point
- Not every merge

### Process
1. Update version in pom.xml
2. Commit: `chore: release v0.1.0`
3. Tag: `git tag v0.1.0`
4. Push with tags
5. Create GitHub Release

### Tagging
```bash
git tag v0.1.0
git push origin v0.1.0
```

## Build (Maven)

### Multi-Module Structure
```
project/
  pom.xml              # Parent
  foo-api/
    pom.xml
  foo-impl/
    pom.xml
```

### Commands
```bash
mvn verify             # Build and test
mvn package            # Create artifacts
mvn spotless:apply     # Format code
```
