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
