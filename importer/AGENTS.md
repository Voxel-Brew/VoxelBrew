# File import instructions

- Use `dev.voxelbrew.importer` (Java reserves the word `import`).
- Own bounded VXB/ProtoJSON/G-code reading, importer discovery and invocation.
  Return detached api/model values; engine owns document codecs.
- Preserve importer-owned typed dialogs and asynchronous `importFrom(ImportWindow)`
  results. Never modify input files; propagate cancellation and read failures.
- Put only public importer contracts in api/import; keep concrete readers and ServiceLoader registrations here.
- Keep readers headless and independent of export, CLI and JavaFX.
- Preserve .vxb schema compatibility, opaque plugin payloads and corruption/newer-schema diagnostics.
- Do not reinterpret a mesh import as editable CAD history or claim unsupported formats work.
- Unit tests use temporary files. Run focused reader/integration checks for changed
  formats and follow root validation rules for milestone gates.
