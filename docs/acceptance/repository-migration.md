# Repository split qualification — 2026-11-13

This maintenance migration preserves the complete application history from
`898034a4e92bfa3f80e31de83bec5bee1ea3648f`. Five MIT API repositories retain
directory history; import/export also include the previous import-api/export-api
directories. API Java packages remain unchanged. Coordinates are
`dev.voxelbrew.api:{model,import,export,printer,slicer}:0.1.0-SNAPSHOT`.

## Automated results

- Each API: `gradlew.bat spotlessApply check publishToMavenLocal` passed on Windows.
- Each API: `./gradlew check publishToMavenLocal` passed in the separate Ubuntu
  workspace `/home/martin/voxelbrew-repository-migration`.
- Application: `gradlew.bat test basicChecks verifyFrontendIoDependencies` passed.
  The equivalent Ubuntu command passed. This includes the independent published-artifact
  plugin fixture and all 13 release-automation tests.
- Windows: `gradlew.bat integrationTest :cli:installDist
  :cli:generateInstallationMetadata :gui:generateInstallationMetadata
  -PreuseOcctInstall=true` passed using real OCCT and the installed slicer.
- Windows: `gradlew.bat :cli:e2eTest --tests '*WorkflowTest.referenceWorkflowAcrossFreshProcesses'
  --tests '*WorkflowTest.externalImporterListsProvidersAndPublishesEditableDocumentsAcrossProcesses'
  --tests '*WorkflowTest.publicExportCommandsAndOverwriteGuard' -PreuseOcctInstall=true` passed.
  Reference volume was `19099.469035085127 mm³`; bounds were `(0,0,0)` to `(60,40,8)`.
- Windows: `gradlew.bat :cli:packageCheck :cli:updatePackageCheck
  -PreuseOcctInstall=true` passed with a real bundled JVM, independent format readers,
  plugin loading, and an update from `0.1.0-SNAPSHOT` to fixture version `0.1.1`.
  API versions remained independent of the application version.
- Local parent workspace, user-selected Gradle 9.8: `gradlew.bat
  :voxelbrew:engine:buildExporterFixture :voxelbrew:verifyFrontendIoDependencies
  assemble -PreuseOcctInstall=true --max-workers=4` passed. All repositories retain
  their committed Gradle 9.1.0 wrappers. Composite publication ordering and source
  substitution were exercised.
- actionlint passed for all application and API workflows.

## Hosted API snapshots

Verification and actual GitHub Maven publication succeeded:

| API | Run |
| --- | --- |
| model | [43513293867](https://github.com/Voxel-Brew/api-model/actions/runs/43513293867) |
| import | [43513806040](https://github.com/Voxel-Brew/api-import/actions/runs/43513806040) |
| export | [43513816755](https://github.com/Voxel-Brew/api-export/actions/runs/43513816755) |
| printer | [43513830854](https://github.com/Voxel-Brew/api-printer/actions/runs/43513830854) |
| slicer | [43514341208](https://github.com/Voxel-Brew/api-slicer/actions/runs/43514341208) |

Dependent API publication jobs successfully consumed GitHub Maven snapshots using
their workflow tokens. The [hosted application migration check](https://github.com/Voxel-Brew/VoxelBrew/actions/runs/43516793973)
passed at `74a2bb763e9d6cab2d7491caf7547eefe305df33`, consuming all five APIs from GitHub
Packages without sibling checkouts or Maven Local. The first hosted run exposed
that api-printer was private; the user approved making it public. Dependency checks
now surface underlying Maven resolution failures instead of reporting only a missing API.
The diagnostic change also passed boundary checks on Windows and Ubuntu.

The old `magx2/VoxelBrew` repository was archived after publishing relocation-notice
commit `1dc3bf6`. Its issues and releases remain there. All six active repositories
are public and use master. Application implementation commit: `caa29a3`; final
build-validation commit: `74a2bb7`. API heads at cutover:

| API | Commit |
| --- | --- |
| model | `487a839` |
| import | `4c0904d` |
| export | `510a14e` |
| printer | `731d741` |
| slicer | `a4a0eff` |

## Evidence and limits

Local logs, original Git bundle, extraction backups, and source manifests are in
`D:\repos\VoxelBrew\.migration`; extraction commit maps are in its `history-maps`
directory. Gradle reports remain under each repository's
`build/reports` and module `build/reports/tests` directories. Windows package evidence
is in `cli/build/package-artifacts/result.json`, `update-result.json`,
`launcher-evidence`, and `update-evidence`.

Migration fixes included an old-organization redirect test fixture, a printer
implementation/API JAR filename collision, and updater assumptions that all API
versions matched the application version. Those failures were fixed and affected
checks rerun successfully. Old absolute native build caches were preserved in the
migration backup and regenerated; the existing pinned OCCT installation was reused.

The full E2E/platform matrix was intentionally omitted. No Linux/macOS E2E, GUI
gesture/installer qualification, Linux native integration, hardware validation,
or release publication is claimed. Existing release workflows were linted and
their scripts tested, but actual releases were not dispatched. The new application
repository has no registered self-hosted macOS GUI runner; that release prerequisite
must be provisioned before its configured release job can run.
