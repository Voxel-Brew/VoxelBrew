# Repository Guidelines

## Module Scope

Follow the root `AGENTS.md` in addition to this guide. `gui` is the JavaFX desktop frontend. Keep
modeling, history, tessellation, and file/device behavior in engine or service modules; the GUI
coordinates those APIs and owns presentation, interaction state, styles, icons, and workspaces.

## JavaFX Rules

Perform expensive rebuilds off the JavaFX application thread, discard results for stale revisions,
and close superseded native resources. Keep drag previews transient: accepting or releasing commits
one operation, while Escape commits none. Preserve millimeter coordinates across model, viewport,
and sketch-plane conversions. Populate import/export and device choices from runtime registries;
do not duplicate backend lists or modeling rules in controllers.

## Verification

Run `./gradlew :gui:test` for view-model and non-rendered behavior. Use targeted
`:gui:e2eTest --tests <ClassName>` for gestures, rendering, or complete workspace flows; Linux UI
tests may require `scripts/with-virtual-desktop.sh`. Capture screenshots for visible changes and
report manual gesture/render checks separately from headless test results.
