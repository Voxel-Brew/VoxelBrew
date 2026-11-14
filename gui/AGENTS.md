# Desktop GUI instructions

- Read the relevant feature documentation and acceptance cases in
  `docs/implementation-plan.md`; the desktop already includes work beyond M3.
- Use `dev.voxelbrew.gui` and engine services for model edits, history and tessellation;
  use public import/export, slicer and printer runtime APIs for those workflows.
  Keep implementation modules runtime-only and every service module headless.
- Obtain render meshes and triangle-to-face mapping from engine. Preserve model
  millimeter coordinates when converting to viewport and sketch-plane coordinates.
- Run expensive rebuilds away from the JavaFX application thread. Track revision
  identity, discard stale results, and close native resources that are superseded.
- Keep drag previews transient: release or accepting a typed distance commits
  exactly one operation; Escape discards the preview without an undo entry.
- Keep timeline rollback separate from undo/redo. Show feature diagnostics and
  prevent export of stale geometry after a failed rebuild.
- Populate export choices from the export module's plugin registry. Avoid frontend-only
  hardcoded exporter lists or duplicate modeling rules.
- Verify the full reference workflow and GUI-saved document parity through CLI.
  Record actual gesture/rendering checks separately from headless tests. A
  `NO-SOURCE` test task or successful compilation is not GUI acceptance evidence.
