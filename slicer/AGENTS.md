# Repository Guidelines

## Module Scope

Follow the root `AGENTS.md` in addition to this guide. `slicer` owns backend discovery,
provider operations, external-process execution, and host-side artifact validation and publication. Public extension contracts remain in `api-slicer`; file serialization belongs in
importer/exporter modules.

## Backend Rules

Keep backend-specific code in packages such as `cura`, `orca`, and `prusa`, with shared internals
under `internal`. Build external commands as argument lists, never shell-interpolated strings.
Isolate configuration by provider ID, preserve destinations on cancellation or failure, close
plugin loaders, and surface stderr and exit failures without leaking secrets. Use exporter implementation serializers on evaluated manufacturing data.

## Verification

Run `./gradlew :slicer-impl:test` for discovery, configuration, and process orchestration. Run
`./gradlew :slicer-impl:integrationTest` for native/plugin workflows. Process doubles are suitable
for failure handling, but qualification of a backend requires its real executable and representative
models.

## Pre-commit SpotBugs

Before committing a change to this module, run
`./gradlew :slicer-impl:spotbugsMain` from the application root.
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
