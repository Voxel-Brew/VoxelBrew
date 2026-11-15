# Repository Guidelines

## Module Scope

Follow the root `AGENTS.md` in addition to this guide. This module implements file readers,
provider discovery, and import invocation under `dev.voxelbrew.importer` (`import` is a Java
keyword). Public contracts remain in the independent `api-import` artifact; document codecs and
editable history belong in `engine`.

## Import Rules

Readers must be headless, bounded, and independent of exporter, CLI, and JavaFX code. Never modify
an input file. Return detached model values, propagate cancellation and read failures, and preserve
clear diagnostics for corrupt or newer-schema documents. Keep ServiceLoader registrations in
`src/main/resources/META-INF/services/`. Do not represent mesh-only imports as editable parametric
history.

## Verification

Use temporary files and realistic malformed inputs in `src/test/test/`. Run
`./gradlew :importer-impl:test` for reader behavior and `./gradlew :importer-impl:integrationTest`
when changes involve native geometry, plugins, or complete document workflows. Format and run the
root basic checks before submission.
