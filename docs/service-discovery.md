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

| Service             | Default provider         | Explicit inputs                                                         |
| ------------------- | ------------------------ | ----------------------------------------------------------------------- |
| `ApplicationPaths`  | `SystemApplicationPaths` | Application home and configuration file paths                           |
| `UserConfiguration` | `FileUserConfiguration`  | `ApplicationPaths`, or a file path for tests and configuration sections |
| `Translations`      | `Translations`           | `UserConfiguration` and `ApplicationPaths`                              |
| `ReleaseSource`     | `GitHubReleaseSource`    | Release endpoint and timeout inputs for transport tests                 |

Each provider has a public no-argument constructor. Providers with service dependencies
delegate to constructors accepting those dependencies. Application scopes construct
translations with their own configuration and paths, retaining language and window isolation.
`UserConfiguration.user()` and `Translations.current()` resolve registered providers;
the latter retains its existing process-wide, read-once convenience behavior.

`SystemApplicationPaths` captures `voxelbrew.home` and `voxelbrew.config` when constructed.
The configuration override takes precedence over the file under the application home.
New service instances observe changed properties; existing instances keep their paths.
Creating these services does not create directories, write configuration, or fetch releases.
The updater resolves its release transport only when explicitly created.

`ApplicationPaths` and `UserConfiguration` are application-internal interfaces for
alternative providers. Consumers constructing a file configuration explicitly use
`new FileUserConfiguration(path)` instead of the former concrete `UserConfiguration` constructor.
This changes the util implementation API; the independent public API repositories are unaffected.

Pure formatting, parsing, geometry, and concurrency helpers remain utilities. Mutable
document identifiers, update transactions, cancellation tokens, and window resources
retain their explicit owners. Logging bootstrap continues to manage process-wide handlers
explicitly rather than becoming an automatically initialized service.
