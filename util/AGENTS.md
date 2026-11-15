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
