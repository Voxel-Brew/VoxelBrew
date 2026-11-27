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
- `./gradlew checkstyleAll` checks handwritten Java across the application source sets,
  including test fixtures, integration tests and E2E sources. The exporter plugin fixture
  runs its own Checkstyle tasks through `:engine:checkExporterFixture`.
- To reproduce the SpotBugs gate in CI, run
  `./gradlew basicChecks :engine:checkExporterFixture -PbundleSlicers=false`.
  CI runs SpotBugs, unit tests, and formatting in parallel. Integration waits for all three,
  then the platform checks run. `Full check` reports the overall result.
- `./gradlew spotlessCheck test` runs formatting checks and unit tests.
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

Run `./gradlew checkJavaMemberOrder` to check Java source declaration order. The `check` and
`build` tasks include it. Within each class, start with static final fields, then other static fields,
final instance fields, other instance fields, static nested types, inner classes, static methods,
constructors, and instance methods. Preserve Java initialization order when moving fields.

### Service construction

Resolve services by their interfaces at the wiring point and pass them to consumers through
constructors. Provider classes must not expose static service getters or downcast discovered
services to their implementations.

For a `ServiceLoader` provider with dependencies, expose a constructor that accepts those
dependencies by their service interfaces and a public no-argument constructor for discovery.
The no-argument constructor resolves the dependencies and delegates to the explicit constructor
with `this(...)`. Keep service discovery in that constructor and use the injected dependencies
when initializing shared resources.

## Testing Guidelines

Tests use JUnit 5 and should end in `Test` (for example, `SketchSolverTest`). Add focused unit tests
in the owning module; reserve integration and E2E suites for native, packaged, or UI behavior.
There is no numeric coverage quota, but every behavior change should include regression coverage.

## Pre-commit SpotBugs

The build resolves VoxelBrew artifacts through Maven and fetches shared quality policy
from the public BOM master branch.

HTML and XML reports are under each module?s `build/reports/spotbugs/`. Analysis uses
the JavaFX dependencies from the normal classpaths. SpotBugs has limited coverage of
JavaFX thread violations, binding lifecycle issues, and UI resource leaks.

Before committing, run `./gradlew spotbugsMain` from the application root to analyze
production code across the Java modules. For a change limited to one module, use
its qualified task, for example `./gradlew :gui:spotbugsMain`.
Use `.\gradlew.bat` in place of `./gradlew` on Windows.

## Commit & Pull Request Guidelines

For automatic release merge-backs, configure the Actions secret `RELEASE_PR_TOKEN`
with access to this repository. A fine-grained token needs Contents, Workflows,
and Pull requests write permissions to push workflow changes and open the PR.

When a pull request resolves a known GitHub issue, add `Closes #123` to the PR description,
using the actual issue number. For an issue in another repository, use
`Closes owner/repository#123`. Add the closing reference to the PR targeting
`master` so GitHub closes the issue when the PR is merged.

Start every commit subject with `feat:`, `fix:`, or `chore:` and follow it with a concise,
imperative description, for example `fix: preserve tessellation face mapping`. Keep commits scoped
to one coherent change. Pull requests should explain the motivation and behavior, link relevant
issues, list exact verification commands, and include screenshots for visible GUI changes. Do not
commit `.tools/`, build outputs, downloaded toolchains, or local IDE state.

Prefer **Rebase and merge** for pull requests. Use **Squash and merge** only as an exception.
The **Create a merge commit** option is blocked.

## Lombok in Java code

Use Lombok where it removes routine Java boilerplate without obscuring a public contract. Apply these annotations in Java-bearing repositories and modules; the website has no Java production code.

- `@Getter` / `@Setter`: Generate straightforward accessors for fields that should be exposed or mutable. Prefer field-level annotations when only some fields qualify; retain custom accessors that enforce invariants or make defensive copies.
- `@RequiredArgsConstructor` / `@NoArgsConstructor`: Generate constructors for required final or `@NonNull` fields, or a no-argument constructor when a framework or serializer needs one. Keep explicit constructors when they validate, transform, or document special initialization behavior.
- `@NonNull`: Mark required fields, parameters, and other supported elements to express and enforce non-null inputs. Replace equivalent `Objects.requireNonNull` checks in those places; keep explicit checks when they provide distinct behavior or messages.
- `@ToString`: Generate useful diagnostic string representations for value or state classes. Exclude secrets, large graphs, and recursive relationships.
- `@Data`: Use for mutable data holders that genuinely need getters, setters, equality, hashing, and string representation together. Prefer narrower annotations when only some behavior is appropriate.
- `@Value`: Use for immutable value types when its generated constructor, accessors, equality, hashing, and string representation fit the contract. Preserve defensive copies for mutable components.
- `@EqualsAndHashCode`: Generate value equality for classes whose identity is defined by selected fields. Choose included fields and superclass handling deliberately; avoid mutable fields that would break hash collections.
- `@Slf4j`: Add a logger to classes that emit operational diagnostics. Use parameterized logging and keep credentials and other sensitive data out of logs.
- `@Builder`: Use for objects with many optional or named construction inputs when it improves call-site clarity. Keep required-field validation and invariants explicit.
- `@UtilityClass`: Use for stateless collections of static helpers that must not be instantiated. Do not use it for classes that may need instances, inheritance, or dependency injection.

## AssertJ in Java tests

Use AssertJ (org.assertj:assertj-core) for assertions in Java unit, integration, and end-to-end tests. Keep JUnit Jupiter for test lifecycle, parameterization, and execution. Declare AssertJ without a version in test dependencies; the BOM manages its version. Prefer ssertThat, ssertThatThrownBy, and ssertThatCode with descriptive failure messages and appropriate numeric tolerances. Do not add new JUnit Assertions calls.
