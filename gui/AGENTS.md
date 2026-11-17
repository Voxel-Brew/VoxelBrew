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
