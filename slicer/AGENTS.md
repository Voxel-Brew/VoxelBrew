# Repository Guidelines

## Module Scope

Follow the root `AGENTS.md` in addition to this guide. `slicer` owns backend discovery,
configuration, external-process execution, printer-profile catalogs, and atomic slicing-job
publication. Public extension contracts remain in `api-slicer`; file serialization belongs in
importer/exporter modules.

## Backend Rules

Keep backend-specific code in packages such as `cura`, `orca`, and `prusa`, with shared internals
under `internal`. Build external commands as argument lists, never shell-interpolated strings.
Isolate configuration by provider ID, preserve destinations on cancellation or failure, close
plugin loaders, and surface stderr and exit failures without leaking secrets. Use exporter
providers to create backend input rather than duplicating file writers.

## Verification

Run `./gradlew :slicer-impl:test` for discovery, configuration, and process orchestration. Run
`./gradlew :slicer-impl:integrationTest` for native/plugin workflows. Process doubles are suitable
for failure handling, but qualification of a backend requires its real executable and representative
models.
