# Repository Guidelines

## Module Scope

Follow the root `AGENTS.md` in addition to this guide. The CLI uses picocli under
`dev.voxelbrew.cli`. Commands orchestrate `engine` and public import, export, slicer, and printer
runtime APIs; implementation modules must remain runtime dependencies rather than command-layer
business logic.

## Command Behavior

Keep stdout stable and machine-readable where promised; send diagnostics to stderr and return
nonzero exit codes for failures. Validate inputs before publishing output, preserve existing files
on failure, and pass external-process arguments as lists. Fresh-process workflows must handle
spaces in paths and native-library discovery. Keep `scripts/reference-part.sh` and
`scripts/reference-part.ps1` behavior equivalent.

## Verification

Run `./gradlew :cli:test` for command parsing and services. Use `./gradlew :cli:installDist` before
testing launchers or distributions, then invoke the installed executable. Run targeted
`:cli:e2eTest --tests <ClassName>` checks for save/reopen/edit, native loading, or packaged-process
changes; avoid the full E2E suite unless the change requires it.

## Pre-commit SpotBugs

Before committing a change to this module, run
`./gradlew :cli:spotbugsMain` from the application root.
Use `.\gradlew.bat` in place of `./gradlew` on Windows.

## Pull requests

When a commit addresses a known GitHub issue, include the issue number in the
commit subject as the scope, for example `feat(#1337): foo boo bar baz`.

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
