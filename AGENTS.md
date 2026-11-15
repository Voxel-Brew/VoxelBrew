# Repository Guidelines

## Project Structure & Module Organization

VoxelBrew is a Java 21 Gradle multi-project application. Core geometry, persistence, and the
Open CASCADE bridge live in `engine/`; shared application services are in `util/`. File workflows
are split across `importer/`, `exporter/`, `slicer/`, and `printer/`. User entry points are `cli/`
and the JavaFX desktop application in `gui/`. Native C++ sources are under `engine/native/`, while
shared sample models and plugin consumers belong in `fixtures/`. Build logic is centralized in
`build.gradle.kts` and `gradle/`; automation and bootstrap helpers live in `scripts/`.

Java production code uses `src/main/java`. Tests intentionally use
`src/test/test/java`, with additional `src/test/integrationTest/java` and
`src/test/e2eTest/java` suites. Keep resources beside the corresponding source set.

## Build, Test, and Development Commands

- `./gradlew assemble` compiles and packages all modules.
- `./gradlew :gui:run` launches the desktop application; `./gradlew :cli:run --args="--help"`
  runs the CLI.
- `./gradlew spotlessApply` formats supported sources and documentation.
- `./gradlew spotlessCheck basicChecks test` reproduces the fast CI gate: formatting, SpotBugs,
  and unit tests.
- `./gradlew integrationTest` runs native-backed integration tests. Bootstrap platform
  dependencies first with `scripts/bootstrap-native.sh` and `scripts/bootstrap-slicer.sh`.
- `./gradlew check` runs the complete platform verification, including E2E tests, and is slower.

On Windows, use `gradlew.bat` and the matching PowerShell bootstrap scripts.

## Coding Style & Naming Conventions

Use four-space indentation, UTF-8, Unix line endings, and the `dev.voxelbrew` package hierarchy.
Spotless applies Palantir Java Format, ktfmt for Gradle Kotlin, clang-format for C++/Protobuf, and
Prettier for Markdown, YAML, and CSS. Java compilation enables `-Xlint:all -Werror`; resolve
warnings instead of suppressing them broadly. Use `UpperCamelCase` for classes, `lowerCamelCase`
for methods and fields, and descriptive feature-oriented package names.

## Testing Guidelines

Tests use JUnit 5 and should end in `Test` (for example, `SketchSolverTest`). Add focused unit tests
in the owning module; reserve integration and E2E suites for native, packaged, or UI behavior.
There is no numeric coverage quota, but every behavior change should include regression coverage.

## Commit & Pull Request Guidelines

Recent history favors concise, imperative subjects such as `Fix collapsed tessellation triangles`;
documentation commits may use a `docs:` prefix. Keep commits scoped to one coherent change. Pull
requests should explain the motivation and behavior, link relevant issues, list exact verification
commands, and include screenshots for visible GUI changes. Do not commit `.tools/`, build outputs,
downloaded toolchains, or local IDE state.
