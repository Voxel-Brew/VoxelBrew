# Repository Guidelines

## Module Scope

Follow the root `AGENTS.md` in addition to this guide. `printer` owns device configuration,
persistence, provider discovery, and network transports such as Moonraker, FTP, and Bambu
integration. Public device contracts remain in `api-printer`. Keep physical printer devices
distinct from slicer printer presets.

## Device Safety

Never log access codes, tokens, passwords, or complete authenticated URLs, and never store them in
project documents. Validate addresses and capabilities before opening sessions. Do not
automatically retry ambiguous state-changing requests such as starting or cancelling a print;
report uncertainty so the caller can reconcile device state. Make session ownership and cleanup
explicit.

## Verification

Use local protocol fixtures and deterministic fakes for unit tests; tests must not contact real
devices by default. Run `./gradlew :printer-impl:test` for module changes. Record manual hardware
qualification separately, including printer model, firmware, transport, and the operation tested.

## Pre-commit SpotBugs

Before committing a change to this module, run
`./gradlew :printer-impl:spotbugsMain` from the application root.
Use `.\gradlew.bat` in place of `./gradlew` on Windows.

## Pull requests

When a pull request resolves a known GitHub issue, add `Closes #123` to the PR description,
using the actual issue number. For an issue in another repository, use
`Closes owner/repository#123`. Add the closing reference to the PR targeting
`master` so GitHub closes the issue when the PR is merged.

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
