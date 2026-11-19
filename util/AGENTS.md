# Repository Guidelines

## Module Scope

Follow the root `AGENTS.md` in addition to this guide. `util` owns CAD-independent application
services: configuration, localization, logging, updates, generic concurrency, and shared test
fixtures. Keep it independent of `engine`, JavaFX, picocli, Protobuf, and native CAD libraries.
Domain values belong in the public model API; geometry and document persistence belong in
`engine`.

## Implementation Rules

Preserve unknown configuration keys and write configuration or update state atomically. Startup
must not contact the network implicitly. Keep diagnostic output off stdout so CLI consumers retain
stable, script-friendly output. Add reusable testing support under `src/test/fixtures/`, not
production packages.

## Verification

Place unit tests in `src/test/test/java` and use temporary paths for filesystem behavior. Run
`./gradlew :util:test` for isolated changes and `./gradlew :util:integrationTest` for packaged JAR,
process, or service-boundary behavior. Also run the root formatting and basic-check commands before
submitting changes.

## Pre-commit SpotBugs

Before committing a change to this module, run
`./gradlew :util:spotbugsMain` from the application root.
Use `.\gradlew.bat` in place of `./gradlew` on Windows.

## Lombok in Java code

Use Lombok where it removes routine Java boilerplate without obscuring a public contract. Apply these annotations in Java-bearing repositories and modules; the website has no Java production code.

- `@Getter` / `@Setter`: Generate straightforward accessors for fields that should be exposed or mutable. Prefer field-level annotations when only some fields qualify; retain custom accessors that enforce invariants or make defensive copies.
- `@RequiredArgsConstructor` / `@NoArgsConstructor`: Generate constructors for required final or `@NonNull` fields, or a no-argument constructor when a framework or serializer needs one. Keep explicit constructors when they validate, transform, or document special initialization behavior.
- `@NonNull`: Mark required fields, parameters, and other supported elements to express and enforce non-null inputs. Replace equivalent `Objects.requireNonNull` checks in those places; keep explicit checks when they provide distinct behavior or messages.
- `@ToString`: Generate useful diagnostic string representations for value or state classes. Exclude secrets, large graphs, and recursive relationships.
- `@Data`: Use for mutable data holders that genuinely need getters, setters, equality, hashing, and string representation together. Prefer narrower annotations when only some behavior is appropriate.
- `@Value`: Use for immutable value types when its generated constructor, accessors, equality, hashing, and string representation fit the contract. Preserve defensive copies for mutable components.
- `@EqualsAndHashCode`: Generate value equality for classes whose identity is defined by selected fields. Choose included fields and superclass handling deliberately; avoid mutable fields that would break hash collections.
- `@Slf4j`: Add a logger to classes that emit operational diagnostics. Use parameterized logging and keep credentials and other sensitive data out of logs.
- `@Builder`: Use for objects with many optional or named construction inputs when it improves call-site clarity. Keep required-field validation and invariants explicit.
- `@UtilityClass`: Use for stateless collections of static helpers that must not be instantiated. Do not use it for classes that may need instances, inheritance, or dependency injection.
