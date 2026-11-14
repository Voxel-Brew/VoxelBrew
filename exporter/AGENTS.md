# File export instructions

- Use `dev.voxelbrew.exporter`; keep format writers, export-view adapters/selection, provider discovery and file publication here.
- Keep shared CAD values in api/model, native evaluation and codecs in engine,
  exporter contracts in api/export and slicer contracts in api/slicer.
- Own VXB/ProtoJSON, STL/3MF/OBJ, STEP and stored G-code exporters, plus Prusa
  project serialization. Slicer execution and job pipelines belong in slicer.
- Preserve `Exporter<R>.export(CadDocument, ExportWindow)`: providers own typed
  dialogs and asynchronous publication. Do not restore the removed `ModelExporter` SPI.
- Validate before publishing; failed operations must preserve existing destinations.
- Never export failed or rolled-back geometry. Preserve units, identities and transforms.
- Use import readers to independently validate document and G-code files where required.
- Run focused exporter tests and real-kernel integration tests for changed formats.
  Follow root validation rules for milestone gates; instruction-only edits need no E2E run.
