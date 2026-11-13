# Independent API repositories

The application remains AGPL-3.0. The five public API repositories are MIT and
retain their Java packages. They publish `dev.voxelbrew.api:model`, `:import`,
`:export`, `:printer`, and `:slicer`, each initially at `0.1.0-SNAPSHOT`.
The previous `dev.voxelbrew:*-api` coordinates are no longer published.
Import/export/printer depend on model; slicer depends on model and export.

## Local workspace

`D:\repos\VoxelBrew` is an unversioned Gradle composite workspace. The application
checkout is `VoxelBrew`; API checkouts are `api/model`, `api/import`, `api/export`,
`api/printer`, and `api/slicer`. Each checkout has its own Git history and wrapper.
The parent `gradlew.bat` / `gradlew` uses Gradle 9.8 for local development; each
repository retains its pinned Gradle 9.1.0 wrapper. The composite publishes current
API sources to Maven Local before compiling the independent plugin fixture.
`assemble`, `check`, `build`, `clean`, and `publishToMavenLocal` aggregate the builds.
Composite dependency substitution makes local API source edits visible immediately.
The parent build files and launchers are intentionally outside Git.

Each API supports `./gradlew publishToMavenLocal`. Standalone consumers opt in
with `-PuseMavenLocal=true`; otherwise the application resolves GitHub Packages.
API versions are set independently in `gradle.properties` (`modelApiVersion`, etc.).
Changing the application release version does not change API dependency versions.
Installation metadata records separate API identities for updater validation.

## Authentication and automation

Set `GITHUB_PACKAGES_USER` and `GITHUB_PACKAGES_TOKEN` (classic read:packages token),
or `githubPackagesUser` and `githubPackagesToken` in your user Gradle properties.
Do not commit credentials. Each API publishes to
`https://maven.pkg.github.com/voxel-brew/api-NAME` using its own Actions token.
Repository/organization `PACKAGES_READ_TOKEN` and `PACKAGES_USER` configure
cross-repository reads; workflows fall back to their GitHub token where permitted.
Bootstrap publications in order: model, import/export/printer, slicer, application.
API pull requests verify against public source checkouts without package credentials.

The `Repository migration checks` workflow validates published API consumption,
formatting, unit tests, dependency boundaries, and release-script tests without E2E.
Existing platform/release workflows retain their qualification behavior.
Migration commits marked `[migration]` run the targeted workflow and suppress the
normal platform matrix; ordinary commits retain the existing CI matrix.
No release is published as part of repository migration.

## History and licensing

The application retains the complete original history. APIs retain directory history,
including the earlier import-api/export-api directories. Extraction rewrites commit
IDs; pre-extraction mixed-module history remains in the application and archive.
Historical acceptance records refer to the original layout and commands.
API binary and source JARs include MIT license notices. This does not relicense
the application or its native feature-provider API.
