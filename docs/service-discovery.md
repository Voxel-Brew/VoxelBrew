# Application service discovery

The CLI and desktop application discover their component factories through
`META-INF/services`. `DefaultCliFactory` and `DefaultGuiFactory` have public
no-argument constructors. Their `create` methods receive runtime configuration,
file ports, and (for the desktop) the stage and plugin directory explicitly.

`Services.load` resolves dependencies requiring exactly one provider. Missing or
ambiguous registrations fail with the service name and candidate provider names.
It does not cache instances. Existing plugin registries continue to discover
multiple format and backend providers using their existing selection rules.

Registered services with dependencies expose a complete dependency constructor
(using Lombok `@RequiredArgsConstructor` where appropriate) and a public
no-argument constructor delegating to it with resolved services. Use interfaces
for interchangeable implementations. Keep application-internal contracts here;
public plugin contracts belong in the independent API repositories.

Each factory creates an independent scope. CLI constructor suppliers create fresh
command instances for each parser. Desktop scopes share their document controller,
printer runtime, and workers within one window. Dialog factories receive their
transaction inputs explicitly. About dialogs remain lazy and are created anew.
Window resources retain ownership of worker shutdown and plugin-loader disposal,
including cleanup when workbench construction fails. Update services remain lazy;
constructing a scope or displaying help does not initiate an update check.

Tests can pass dependencies directly through constructors and component factory
inputs, without changing global service state.

## Utility services

`VoxelBrew:util` supplies the model API services through `ServiceLoader`.

- `UserConfigService` reads and merges UTF-8 user preferences. The `voxelbrew.home` and
  `voxelbrew.config` JVM properties select the application home and a file within it.
  Each service takes an in-memory snapshot at startup. Single-key reads use that
  snapshot. Bulk reads and successful writes refresh it.
  The slicer store reads provider defaults from its preferences files and supports
  the older settings layouts.
- `TranslationService` registers the bundled language catalogs at startup.
  Application modules list bundles in `META-INF/voxelbrew/translations`. The service
  then discovers all `TranslationProvider` implementations, including the properties
  catalogs from the export, import, and printer APIs, and merges their messages.
  User catalogs in the application home's `i18n` folder can override
  registered messages. Standalone consumers can resolve messages from API bundles.
  CLI and desktop scopes load their catalog through `ServiceLoader` at startup.
  Registrations belong to the service instance.
- `MultithreadService` owns the process-wide executor. The saved
  `voxelbrew.compute.threads` preference accepts 1 through 32. Changes take effect
  after restart. Desktop operations use ordered queues on the shared executor.
  Window disposal drains those queues before releasing plugin loaders.
- `ReleaseSource` resolves its transport when the updater is created.
