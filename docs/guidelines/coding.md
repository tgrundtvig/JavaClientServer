# Coding Guidelines

## Type System

### Interfaces
- Use interfaces for all behavioral types
- Interface gets the clean name: `CharacterAttribute`, `Location`, `Item`
- No class references as types (except below)

### Records
- Use for immutable data: DTOs, events, value objects
- Direct construction allowed: `new Point(3, 4)`
- Methods must be pure functions of own fields only

### Enums
- Use for fixed value sets
- Allowed as types (like records)

### Exceptions
- Extend RuntimeException (unchecked only)
- Class references allowed (Java requirement)

### JDK Types
- Use when needed: `String`, `BigDecimal`, `LocalDateTime`
- Prefer abstract types: `List` over `ArrayList`

## Naming

### Interfaces and Implementations
- Interface: clean concept name (`CharacterAttribute`)
- Implementation: `DefaultFoo` for standard, `DescriptiveFoo` for variants
- Avoid `FooImpl` (carries no meaning)

### Factories
- `FooFactory` (interface)
- `DefaultFooFactory` (implementation)

### Methods
- `find...` returns `Optional` (might not exist)
- `get...` returns value (throws if missing)

## Factory Pattern

### Scope
- Factories for behavioral objects (interfaces)
- Direct construction for records and enums

### Structure
```java
interface CharacterAttributeFactory
{
    CharacterAttribute create(int min, int max);
}

class DefaultCharacterAttributeFactory implements CharacterAttributeFactory
{
    public CharacterAttribute create(int min, int max)
    {
        return new DefaultCharacterAttribute(min, max);
    }
}
```

### Factories as Interfaces
- Factory interfaces allow injection and mocking
- No static factory methods

## Module Structure (JPMS)

### Separate API and Implementation
```
foo-api/        # Interfaces, records, enums
foo-impl/       # Default implementation
foo-mock/       # Mock implementation
```

### Module Naming
- `foo-api` — Interfaces, records, enums
- `foo-impl` or `foo-default` — Default implementation
- `foo-mock` — Mock for testing
- Descriptive variants: `foo-postgres`, `foo-inmemory`

### Package Structure
- API: `dk.example.foo` (exported)
- Impl: `dk.example.foo.impl` (not exported)

### Module Info
```java
// foo-api
module foo.api
{
    exports dk.example.foo;
}

// foo-impl
module foo.impl
{
    requires foo.api;
    // impl package not exported
}
```

### Principles
- Many small, focused modules
- One concept per module
- API modules contain contracts only

## Exception Handling

### Unchecked Only
- All exceptions extend RuntimeException
- Simpler, lambda-friendly, no abstraction leaks

### Fail Fast
- Validate at entry points
- Throw early with context

### Hierarchy
- Start flat, let hierarchy emerge
- Don't pre-create base exceptions

## Null Handling

### Return Types
- `Optional<T>` when absence is valid
- Direct return when value must exist (throw if missing)

### Parameters
- Never accept null
- Use overloading for optional parameters
- Validate: `Objects.requireNonNull(foo, "foo")`

### Collections
- Never null, use empty collection

### Records
- All fields non-null
- Use `Optional` for genuinely optional fields

## Code Style

### Immutability
- Prefer immutable by default
- Records, final fields
- Return new instances, don't mutate
- Mutable state: explicit, contained, justified

### Method Length
- No hard limit
- Single responsibility
- One level of abstraction
- If you can't see it all, consider extracting

### Comments
- Javadoc on public API (interfaces, public methods)
- Document what and why, not how
- Minimal inline comments
- Code should be self-documenting

### Formatting
- Allman brace style (braces on new line, aligned)
- Use IDE formatter for consistency

## Dependency Injection

### Constructor Injection
```java
class DefaultCharacterService implements CharacterService
{
    private final CharacterRepository repository;

    public DefaultCharacterService(CharacterRepository repository)
    {
        this.repository = Objects.requireNonNull(repository);
    }
}
```

### No Framework
- Explicit wiring at application entry point
- Compile-time safety
- No magic, no reflection

## Logging

### API
- SLF4J facade
- `private static final Logger LOG = LoggerFactory.getLogger(Foo.class);`

### Levels
- ERROR: Failed, needs attention
- WARN: Unexpected but handled
- INFO: Significant events
- DEBUG: Troubleshooting detail

### Content
- Include context: `LOG.info("Player joined: playerId={}", id)`
- Log exceptions with cause: `LOG.error("Failed: id={}", id, exception)`
- Don't log sensitive data
