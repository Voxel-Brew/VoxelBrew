# Repository Guidelines

## Module Scope

Follow the root `AGENTS.md` in addition to this guide. `exporter` owns format writers, export
selection/adapters, provider discovery, and atomic publication for VXB, ProtoJSON, STL, 3MF, OBJ,
STEP, stored G-code, and slicer project data. Public contracts remain in `api-export`; geometry and
codecs remain in `engine`.

## Export Rules

Validate the document and destination before writing. Publish atomically so cancellation or
failure preserves an existing target. Never export failed, stale, or rolled-back geometry, and
preserve units, identities, and transforms. Keep format-specific implementations headless and
register providers through `META-INF/services`. Where practical, verify output with an independent
reader rather than the writer’s own parser.

## Verification

Add focused tests under `src/test/test/` and fixtures under test resources. Run
`./gradlew :exporter-impl:test`; use `./gradlew :exporter-impl:integrationTest` for STEP, native
geometry, plugin, or round-trip changes. New formats need success, invalid-input, cancellation, and
destination-preservation coverage.
