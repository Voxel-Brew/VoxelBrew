# Repository Guidelines

## Module Scope

Follow the root `AGENTS.md` in addition to this guide. This module implements file readers,
and synchronous built-in importers under `dev.voxelbrew.importer` (`import` is a Java
keyword). Public contracts remain in the independent `api-import` artifact; document codecs and
editable history belong in `engine`. Direct `DocumentSource`, `GcodeSource`, and `ImportRuntime`
reader ports are application-only types in this module, not public plugin contracts.

## Import Rules

Readers must be headless, bounded, and independent of exporter, CLI, and JavaFX code. Provider
discovery belongs to the public `ImporterProviderRegistry`; register built-ins through its
ServiceLoader resource. Never modify
an input file. Return detached model values, propagate cancellation and read failures, and preserve
clear diagnostics for corrupt or newer-schema documents. Keep ServiceLoader registrations in
`src/main/resources/META-INF/services/`. Do not represent mesh-only imports as editable parametric
history.

## Verification

Use temporary files and realistic malformed inputs in `src/test/test/`. Run
`./gradlew :importer-impl:test` for reader behavior and `./gradlew :importer-impl:integrationTest`
when changes involve native geometry, plugins, or complete document workflows. Format and run the
root basic checks before submission.

## Pre-commit SpotBugs

Before committing a change to this module, run
`./gradlew :importer-impl:spotbugsMain` from the application root.
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
