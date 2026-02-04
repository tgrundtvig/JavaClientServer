# Project Template

Java project template with AI-assisted development guidelines.

## Getting Started

### Using as Template

1. Click "Use this template" on GitHub
2. Clone your new repository
3. Run setup script: `./setup.sh`
4. Fill in `docs/project/overview.md`
5. Start coding

### Manual Setup

1. Update `pom.xml`:
   - `groupId`: your organization
   - `artifactId`: project name
   - `name`: display name

2. Update `docs/project/overview.md` with project description

3. Delete this section from README and add project-specific content

## Documentation

- [Project Overview](docs/project/overview.md)
- [Architecture](docs/project/architecture.md)
- [Domain Model](docs/project/domain.md)

### Guidelines

- [Collaboration](docs/guidelines/collaboration-guidelines.md)
- [Coding](docs/guidelines/coding.md)
- [Testing](docs/guidelines/testing.md)
- [DevOps](docs/guidelines/devops.md)

## Building

```bash
mvn verify
```

## License

[Choose a license]
