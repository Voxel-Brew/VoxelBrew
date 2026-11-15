# Repository Guidelines

## Module Scope

Follow the root `AGENTS.md` in addition to this guide. `engine` owns editable model history,
geometry evaluation, sketches, printing geometry, persistence codecs, Protobuf schema, and the
Open CASCADE JNI bridge in `native/`. Keep production code independent of importer, exporter,
slicer, printer, CLI, and GUI implementations.

## Geometry & Persistence Rules

Use millimeters and double precision; reject non-finite dimensions at public boundaries. Preserve
stable feature IDs, ordered history, and the distinction between timeline rollback and undo/redo.
Failed rebuilds must retain editable definitions and must not expose stale geometry for export.
Close every owning native `Solid` and keep JNI narrow with translated, actionable failures.

Evolve `src/main/proto/document.proto` compatibly: reserve retired field numbers and never edit
generated sources. Preserve unknown/newer document data where supported.

## Verification

Use `./gradlew :engine:test` for Java logic, `:engine:integrationTest` for real-kernel workflows,
and `:engine:nativeIntegrationTest` for the native subset. Native changes require platform-native
libraries and validation on each affected OS. Run Spotless after editing Java, C++, or Protobuf.
