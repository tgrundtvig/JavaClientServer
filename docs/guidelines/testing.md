# Testing Guidelines

## Philosophy

### Test Behavior, Not Implementation
- Test observable behavior through public APIs
- Internal structure is implementation detail
- Refactoring internals shouldn't break tests

### Test at Module Boundaries
- Each module has a public API — test that
- Higher confidence than isolated unit tests
- Catches integration issues

## Test Organization

### Location
```
foo-api/
  src/test/java/       # Contract tests (abstract)

foo-impl/
  src/test/java/       # Implementation tests

foo-mock/
  src/main/java/       # Mock implementations (not tests)
```

### Contract Tests
- Define expected behavior in API module
- Abstract test class, implementations extend it
- All implementations verified against same contract

```java
// In foo-api test
abstract class CharacterAttributeContractTest
{
    abstract CharacterAttribute createInstance(int min, int max);

    @Test
    void shouldKeepMinBelowMax()
    {
        var attr = createInstance(1, 10);
        assertTrue(attr.getMinValue() <= attr.getMaxValue());
    }
}

// In foo-impl test
class DefaultCharacterAttributeTest extends CharacterAttributeContractTest
{
    @Override
    CharacterAttribute createInstance(int min, int max)
    {
        return new DefaultCharacterAttribute(min, max);
    }
}
```

## Mock Modules

### Purpose
- Alternative implementations for testing
- Reusable across modules
- Verified by contract tests

### Design
- Predictable, in-memory behavior
- Configurable for test scenarios
- State inspection for assertions

```java
class MockEmailService implements EmailService
{
    private final List<Email> sentEmails = new ArrayList<>();
    private boolean shouldFail = false;

    @Override
    public void send(Email email)
    {
        if (shouldFail) throw new EmailException("simulated");
        sentEmails.add(email);
    }

    // Test helpers
    public int sendCount() { return sentEmails.size(); }
    public Email lastSent() { return sentEmails.getLast(); }
    public void setShouldFail(boolean fail) { shouldFail = fail; }
}
```

### No Mockito
- Hand-written mocks are explicit and debuggable
- AI generates them easily
- Mocks run against contract tests
- One less dependency

## Test Naming

### Pattern
```java
// should[ExpectedBehavior]When[Condition]
@Test
void shouldReturnEmptyWhenCharacterNotFound()

@Test
void shouldThrowWhenMinExceedsMax()
```

### Principles
- Name describes behavior, not method being tested
- Failure message is informative
- No `test` prefix (annotation is sufficient)

## Test Structure

### Arrange-Act-Assert
```java
@Test
void shouldCalculateMean()
{
    // Arrange
    var attribute = factory.create(2, 10);

    // Act
    int mean = attribute.getMeanValue();

    // Assert
    assertEquals(6, mean);
}
```

### Keep Tests Short
- One behavior per test
- Comments optional if structure is clear

## Assertions

### Prefer Specific Assertions
```java
// Good
assertThat(result).isEmpty();
assertEquals(6, mean);

// Avoid
assertTrue(result.isEmpty());
assertTrue(mean == 6);
```

### Use AssertJ or JUnit 5
- Readable failure messages
- Fluent API for complex assertions

## When to Test

### Always Test
- Contract behavior (via contract tests)
- Edge cases and error paths
- Business logic

### Don't Test
- Simple delegation
- Framework behavior
- Trivial code covered by integration tests

### Avoid
- 100% coverage as a goal
- Tests coupled to implementation details
