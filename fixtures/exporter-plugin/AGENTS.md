# Independent provider fixture

- This is a standalone Java 21 consumer build inside the application repository,
  not an application subproject or a separate Git repository.
- Keep import/export/slicer providers compiling against published API artifacts
  only. Native feature providers use the separate `features` source set with an
  explicit engine dependency. Never bundle host classes in the plugin JAR.
- Preserve the JAR task's dependency-boundary assertion and ServiceLoader entries.
- Build through `:engine:buildExporterFixture` from the application root so required
  fixture artifacts are published first; follow root instructions for formatting.
- Validate provider discovery, duplicate handling and failure handling with relevant
  host tests. Compilation alone does not verify plugin loading or native behavior.

## Pre-commit SpotBugs

When the fixture applies the BOM SpotBugs convention, use the following pre-commit steps.
Before committing fixture changes, run `./gradlew :engine:buildExporterFixture` from
the application root to prepare the fixture dependencies. Then run
`./gradlew -p fixtures/exporter-plugin spotbugsMain spotbugsFeatures`.
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

## Service injection

Resolve services by their interfaces at the wiring point and pass them to consumers through
constructors. Provider classes must not expose static service getters or downcast discovered
services to their implementations.
