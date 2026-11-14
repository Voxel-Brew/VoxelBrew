# CLI instructions

- Read `docs/editable-workflow.md` and the public reference scripts under `scripts/`.
- Use picocli under `dev.voxelbrew.cli`. Delegate modeling/history to engine and
  file reading/writing, export, slicing and device operations to the corresponding
  public runtime APIs. Implementation modules remain runtime dependencies.
- Preserve useful diagnostics and nonzero failure exit codes. Reject invalid
  inputs before publishing output; failed commands must not corrupt documents.
- Keep output usable in scripts. Check save/reopen/edit workflows across fresh
  JVM processes, including native loading and paths containing spaces.
- Exercise public commands through the installed distribution when validating
  distribution behavior. Use `:cli:installDist`; the development distribution
  still requires Java 21 and must not be described as a bundled-JVM installer.
- Keep `scripts/reference-part.ps1` and `scripts/reference-part.sh` equivalent.
  Use a fresh destination for demos; preserve their overwrite protection.
- Use targeted `:cli:e2eTest --tests <class>` runs for fresh-process workflows;
  follow root E2E scope rules for milestone gates.
  Changes to launchers, native loading, or process invocation need Windows and
  Ubuntu checks. Use argument lists for external processes, not shell interpolation.
