# Independent provider fixture

- This is a standalone Java 21 consumer build inside the application repository,
  not an application subproject or a separate Git repository.
- Keep import/export/slicer providers compiling against published API artifacts
  only. Native feature providers use the separate `features` source set with an
  explicit engine dependency. Never bundle host classes in the plugin JAR.
- Preserve the JAR task's dependency-boundary assertion and ServiceLoader entries.
- Build through `:engine:buildExporterFixture` from the application root so required
  fixture artifacts are published first; follow root instructions for formatting.
- Validate provider discovery, compatibility and failure handling with relevant
  host tests. Compilation alone does not verify plugin loading or native behavior.
