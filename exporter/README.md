# Export module

The `:exporter-impl` Gradle project implements VoxelBrew's document and geometry exporters,
runtime exporter discovery, and external-slicer file pipeline. Its Java package is
`dev.voxelbrew.exporter`. Both the JavaFX desktop and headless CLI use this module.

Public plugin contracts live in [`:api:export`](../../api/export/README.md), not here. The engine
owns document codecs, evaluation, native geometry and tessellation; this module owns
format implementations and file publication. Production dependencies point from
export to engine, never in the opposite direction.

## Built-in formats

| Format ID       | Output                                                 | Implementation                                    |
| --------------- | ------------------------------------------------------ | ------------------------------------------------- |
| `vxb`           | Editable VoxelBrew document, including feature history | `VoxelBrewExporter` / `VoxelBrewExporterProvider` |
| `json`          | Complete document schema as UTF-8 ProtoJSON            | `JsonExporter` / `JsonExporterProvider`           |
| `step`          | Exact design solids as STEP AP242, in millimeters      | `StepExporter` / `StepExporterProvider`           |
| `stl`           | Binary STL mesh, in millimeters                        | `BinaryStlExporter`                               |
| `3mf`           | Portable 3MF with units and instance placements        | `ThreeMfExporter`                                 |
| `obj`           | Wavefront OBJ mesh, with instance transforms applied   | `ObjExporter`                                     |
| `prusa-project` | PrusaSlicer project from a complete manufacturing view | `printing.PrusaProjectExporter`                   |

The native document extension is `.vxb`. Existing `.vxb` documents remain readable.
**File > Save** uses the native document writer; **File > Export** lists discovered
exporters through one registry. Exporting JSON or STEP does not change the active
project path or save counter. STEP exports complete design solids, without print-bed
placements, supports or editable history.

## Write your own exporter

See the [export API developer guide](../../api/export/README.md) for the public
contract, a complete implementation example, custom dialogs, plugin packaging,
runtime discovery, first-party registration and validation commands.

## Built-in geometry and slicing

Built-in STL, 3MF, OBJ and Prusa project exporters share `MeshExporter`, an
implementation helper in this module. They request a `MeshExportDialog` containing
format-specific options. Desktop and CLI handlers return a destination, tolerance,
body/instance selection and coordinate space. Evaluation and serialization happen
on the calling thread; GUI callers run exports on their file worker, while the CLI
and slicer invoke them directly. `AtomicExport` publishes the completed file atomically.

`ModelView`, `ManufacturingView`, `MeshFormat` and `MeshExportOption` are implementation
helpers, not plugin contracts in `export-api`. The public `ModelExporter` SPI and its
service resource have been removed. External exporters use `ExporterProvider` and
can define their own typed dialog requests. Existing plugins must be recompiled and
migrated to the unified contract; there is no legacy discovery adapter.

Built-in slicer providers call the exporter implementation serializers with an evaluated
manufacturing view. Prusa uses project 3MF with solid supports included. Cura uses binary STL
and Orca uses portable 3MF. Each operation owns its private input and cleans it up after the
backend exits. The application independently inspects the returned artifact before attachment
or publication.

## Verification

Format and slicing tests live under `src/test/integrationTest`; isolated writer,
discovery and dialog tests live under `src/test/test`. The independent consumer
fixture is in [`fixtures/exporter-plugin`](../../fixtures/exporter-plugin).

See [validation commands](../../api/export/README.md#validate-an-exporter) in the API
guide and the [root README](../../README.md) for native build prerequisites. Focused
export tests do not replace full GUI, slicer or packaged-application qualification.
