package dev.voxelbrew.cli;

import dev.voxelbrew.api.printer.PrinterProfileCatalog;
import dev.voxelbrew.api.printer.PrinterStore;
import dev.voxelbrew.api.printer.driver.DriverRegistry;
import dev.voxelbrew.engine.feature.FeatureRegistry;
import dev.voxelbrew.util.di.ApplicationModule;
import dev.voxelbrew.util.i18n.Translations;
import java.util.HashMap;
import java.util.function.Supplier;

/** Service-discovered factory; each invocation owns an independent constructor graph. */
public final class DefaultCliFactory implements CliComponent.Factory {
    public DefaultCliFactory() {}

    @Override
    public CliComponent create(ApplicationModule application, FileIoModule files) {
        return new Scope(application, files);
    }

    private static final class Scope implements CliComponent {
        private final ApplicationModule application;
        private final FileIoModule files;
        private final Translations translations;
        private final CommandFactory commands;
        private final PrinterStore printerStore;
        private final PrinterProfileCatalog profiles;

        @Override
        public CommandFactory commands() {
            return commands;
        }

        @Override
        public Translations translations() {
            return translations;
        }

        Scope(ApplicationModule application, FileIoModule files) {
            this.application = application;
            this.files = files;
            this.printerStore = new dev.voxelbrew.printer.FilePrinterStore(application.configuration());
            this.profiles = new dev.voxelbrew.printer.DefaultPrinterProfileCatalog(
                    application.paths().profiles());
            this.translations = new Translations(application.configuration(), application.paths());
            var commands = new HashMap<Class<?>, Supplier<Object>>();
            commands.put(ReportBugCommand.class, () -> reportBugCommand());
            commands.put(PreferenceCommands.class, () -> preferenceCommands());
            commands.put(DeviceCommands.class, () -> deviceCommands());
            commands.put(SessionCommand.class, () -> sessionCommand());
            commands.put(WorkspaceEdits.class, () -> workspaceEdits());
            commands.put(PrintingCommands.Printers.class, () -> printingCommandsPrinters());
            commands.put(BodyStyleCommand.class, () -> bodyStyleCommand());
            commands.put(ImportCommands.Importers.class, () -> importCommandsImporters());
            commands.put(ImportCommands.ImportFile.class, () -> importCommandsImportFile());
            commands.put(VoxelBrew.class, () -> voxelBrew());
            commands.put(VoxelBrew.Exporters.class, () -> voxelBrewExporters());
            commands.put(VoxelBrew.Export.class, () -> voxelBrewExport());
            commands.put(VoxelBrew.Create.class, () -> voxelBrewCreate());
            commands.put(VoxelBrew.Rectangle.class, () -> voxelBrewRectangle());
            commands.put(VoxelBrew.Circle.class, () -> voxelBrewCircle());
            commands.put(VoxelBrew.Extrude.class, () -> voxelBrewExtrude());
            commands.put(VoxelBrew.Cut.class, () -> voxelBrewCut());
            commands.put(VoxelBrew.Edit.class, () -> voxelBrewEdit());
            commands.put(VoxelBrew.Cursor.class, () -> voxelBrewCursor());
            commands.put(VoxelBrew.Repair.class, () -> voxelBrewRepair());
            commands.put(VoxelBrew.History.class, () -> voxelBrewHistory());
            commands.put(VoxelBrew.Inspect.class, () -> voxelBrewInspect());
            commands.put(VoxelBrew.NativeDemo.class, () -> voxelBrewNativeDemo());
            commands.put(PrintingCommands.Split.class, () -> printingCommandsSplit());
            commands.put(PrintingCommands.Place.class, () -> printingCommandsPlace());
            commands.put(PrintingCommands.Duplicate.class, () -> printingCommandsDuplicate());
            commands.put(PrintingCommands.LayOnFace.class, () -> printingCommandsLayOnFace());
            commands.put(PrintingCommands.Settings.class, () -> printingCommandsSettings());
            commands.put(PrintingCommands.Support.class, () -> printingCommandsSupport());
            commands.put(PrintingCommands.RemoveInstance.class, () -> printingCommandsRemoveInstance());
            commands.put(PrintingCommands.RemoveSupport.class, () -> printingCommandsRemoveSupport());
            commands.put(PrintingCommands.Setup.class, () -> printingCommandsSetup());
            commands.put(PrintingCommands.Validate.class, () -> printingCommandsValidate());
            commands.put(PrintingCommands.Slicers.class, () -> printingCommandsSlicers());
            commands.put(PrintingCommands.Slice.class, () -> printingCommandsSlice());
            commands.put(PrintingCommands.InspectJob.class, () -> printingCommandsInspectJob());
            commands.put(FeatureCommands.Types.class, () -> featureCommandsTypes());
            commands.put(FeatureCommands.Custom.class, () -> featureCommandsCustom());
            commands.put(SketchCommands.class, () -> sketchCommands());
            commands.put(SketchCommands.Draw.class, () -> sketchCommandsDraw());
            commands.put(SketchCommands.EditText.class, () -> sketchCommandsEditText());
            commands.put(SketchCommands.Create.class, () -> sketchCommandsCreate());
            commands.put(SketchCommands.Convert.class, () -> sketchCommandsConvert());
            commands.put(SketchCommands.PutEntity.class, () -> sketchCommandsPutEntity());
            commands.put(SketchCommands.Constrain.class, () -> sketchCommandsConstrain());
            commands.put(SketchCommands.RemoveConstraint.class, () -> sketchCommandsRemoveConstraint());
            commands.put(SketchCommands.RemoveEntity.class, () -> sketchCommandsRemoveEntity());
            commands.put(SketchCommands.Profile.class, () -> sketchCommandsProfile());
            commands.put(SketchCommands.Solve.class, () -> sketchCommandsSolve());
            commands.put(SketchCommands.Listing.class, () -> sketchCommandsListing());
            commands.put(SketchCommands.Move.class, () -> sketchCommandsMove());
            commands.put(SketchCommands.Polygon.class, () -> sketchCommandsPolygon());
            commands.put(ParameterCommands.Parameters.class, () -> parameterCommandsParameters());
            commands.put(ParameterCommands.Configurations.class, () -> parameterCommandsConfigurations());
            commands.put(ParameterCommands.ListParameters.class, () -> parameterCommandsListParameters());
            commands.put(ParameterCommands.SetParameter.class, () -> parameterCommandsSetParameter());
            commands.put(ParameterCommands.RenameParameter.class, () -> parameterCommandsRenameParameter());
            commands.put(ParameterCommands.RemoveParameter.class, () -> parameterCommandsRemoveParameter());
            commands.put(ParameterCommands.Bind.class, () -> parameterCommandsBind());
            commands.put(ParameterCommands.ListConfigurations.class, () -> parameterCommandsListConfigurations());
            commands.put(ParameterCommands.AddConfiguration.class, () -> parameterCommandsAddConfiguration());
            commands.put(ParameterCommands.Override.class, () -> parameterCommandsOverride());
            commands.put(ParameterCommands.Activate.class, () -> parameterCommandsActivate());
            commands.put(ParameterCommands.RenameConfiguration.class, () -> parameterCommandsRenameConfiguration());
            commands.put(ParameterCommands.RemoveConfiguration.class, () -> parameterCommandsRemoveConfiguration());
            commands.put(UpdateCommands.Update.class, () -> updateCommandsUpdate());
            commands.put(UpdateCommands.Check.class, () -> updateCommandsCheck());
            this.commands = new CommandFactory(commands);
        }

        private ReportBugCommand reportBugCommand() {
            return new ReportBugCommand();
        }

        private PreferenceCommands preferenceCommands() {
            return new PreferenceCommands(application.configuration(), profiles, files.slicers());
        }

        private DeviceCommands deviceCommands() {
            return new DeviceCommands(
                    printerStore,
                    () -> java.util.ServiceLoader.load(DriverRegistry.class)
                            .findFirst()
                            .orElseThrow(() ->
                                    new IllegalStateException("No DriverRegistry service found; install printer-impl")),
                    files.source(),
                    files.sink(),
                    profiles);
        }

        private SessionCommand sessionCommand() {
            return new SessionCommand(files.source(), files.sink(), files.gcode());
        }

        private WorkspaceEdits workspaceEdits() {
            return new WorkspaceEdits(files.source(), files.sink());
        }

        private PrintingCommands.Printers printingCommandsPrinters() {
            return new PrintingCommands.Printers(profiles);
        }

        private BodyStyleCommand bodyStyleCommand() {
            return new BodyStyleCommand(files.source(), files.sink());
        }

        private ImportCommands.Importers importCommandsImporters() {
            return new ImportCommands.Importers(files.importers());
        }

        private ImportCommands.ImportFile importCommandsImportFile() {
            return new ImportCommands.ImportFile(files.importers(), files.sink());
        }

        private VoxelBrew voxelBrew() {
            return new VoxelBrew();
        }

        private VoxelBrew.Exporters voxelBrewExporters() {
            return new VoxelBrew.Exporters(files.exporters());
        }

        private VoxelBrew.Export voxelBrewExport() {
            return new VoxelBrew.Export(files.source(), files.exporters());
        }

        private VoxelBrew.Create voxelBrewCreate() {
            return new VoxelBrew.Create(files.source(), files.sink());
        }

        private VoxelBrew.Rectangle voxelBrewRectangle() {
            return new VoxelBrew.Rectangle(files.source(), files.sink());
        }

        private VoxelBrew.Circle voxelBrewCircle() {
            return new VoxelBrew.Circle(files.source(), files.sink());
        }

        private VoxelBrew.Extrude voxelBrewExtrude() {
            return new VoxelBrew.Extrude(files.source(), files.sink());
        }

        private VoxelBrew.Cut voxelBrewCut() {
            return new VoxelBrew.Cut(files.source(), files.sink());
        }

        private VoxelBrew.Edit voxelBrewEdit() {
            return new VoxelBrew.Edit(files.source(), files.sink());
        }

        private VoxelBrew.Cursor voxelBrewCursor() {
            return new VoxelBrew.Cursor(files.source(), files.sink());
        }

        private VoxelBrew.Repair voxelBrewRepair() {
            return new VoxelBrew.Repair(files.source(), files.sink());
        }

        private VoxelBrew.History voxelBrewHistory() {
            return new VoxelBrew.History(files.source(), files.sink());
        }

        private VoxelBrew.Inspect voxelBrewInspect() {
            return new VoxelBrew.Inspect(files.source(), files.sink());
        }

        private VoxelBrew.NativeDemo voxelBrewNativeDemo() {
            return new VoxelBrew.NativeDemo();
        }

        private PrintingCommands.Split printingCommandsSplit() {
            return new PrintingCommands.Split(files.source(), files.sink());
        }

        private PrintingCommands.Place printingCommandsPlace() {
            return new PrintingCommands.Place(files.source(), files.sink());
        }

        private PrintingCommands.Duplicate printingCommandsDuplicate() {
            return new PrintingCommands.Duplicate(files.source(), files.sink());
        }

        private PrintingCommands.LayOnFace printingCommandsLayOnFace() {
            return new PrintingCommands.LayOnFace(files.source(), files.sink());
        }

        private PrintingCommands.Settings printingCommandsSettings() {
            return new PrintingCommands.Settings(files.source(), files.sink(), profiles);
        }

        private PrintingCommands.Support printingCommandsSupport() {
            return new PrintingCommands.Support(files.source(), files.sink());
        }

        private PrintingCommands.RemoveInstance printingCommandsRemoveInstance() {
            return new PrintingCommands.RemoveInstance(files.source(), files.sink());
        }

        private PrintingCommands.RemoveSupport printingCommandsRemoveSupport() {
            return new PrintingCommands.RemoveSupport(files.source(), files.sink());
        }

        private PrintingCommands.Setup printingCommandsSetup() {
            return new PrintingCommands.Setup(files.source(), files.sink());
        }

        private PrintingCommands.Validate printingCommandsValidate() {
            return new PrintingCommands.Validate(files.source(), files.sink());
        }

        private PrintingCommands.Slicers printingCommandsSlicers() {
            return new PrintingCommands.Slicers(files.slicers());
        }

        private PrintingCommands.Slice printingCommandsSlice() {
            return new PrintingCommands.Slice(
                    files.source(), files.sink(), files.slicers(), printerStore, application.configuration());
        }

        private PrintingCommands.InspectJob printingCommandsInspectJob() {
            return new PrintingCommands.InspectJob(files.gcode(), files.source());
        }

        private FeatureCommands.Types featureCommandsTypes() {
            return new FeatureCommands.Types(FeatureRegistry::new);
        }

        private FeatureCommands.Custom featureCommandsCustom() {
            return new FeatureCommands.Custom(files.source(), files.sink(), FeatureRegistry::new);
        }

        private SketchCommands sketchCommands() {
            return new SketchCommands();
        }

        private SketchCommands.Draw sketchCommandsDraw() {
            return new SketchCommands.Draw(files.source(), files.sink());
        }

        private SketchCommands.EditText sketchCommandsEditText() {
            return new SketchCommands.EditText(files.source(), files.sink());
        }

        private SketchCommands.Create sketchCommandsCreate() {
            return new SketchCommands.Create(files.source(), files.sink());
        }

        private SketchCommands.Convert sketchCommandsConvert() {
            return new SketchCommands.Convert(files.source(), files.sink());
        }

        private SketchCommands.PutEntity sketchCommandsPutEntity() {
            return new SketchCommands.PutEntity(files.source(), files.sink());
        }

        private SketchCommands.Constrain sketchCommandsConstrain() {
            return new SketchCommands.Constrain(files.source(), files.sink());
        }

        private SketchCommands.RemoveConstraint sketchCommandsRemoveConstraint() {
            return new SketchCommands.RemoveConstraint(files.source(), files.sink());
        }

        private SketchCommands.RemoveEntity sketchCommandsRemoveEntity() {
            return new SketchCommands.RemoveEntity(files.source(), files.sink());
        }

        private SketchCommands.Profile sketchCommandsProfile() {
            return new SketchCommands.Profile(files.source(), files.sink());
        }

        private SketchCommands.Solve sketchCommandsSolve() {
            return new SketchCommands.Solve(files.source(), files.sink());
        }

        private SketchCommands.Listing sketchCommandsListing() {
            return new SketchCommands.Listing(files.source(), files.sink());
        }

        private SketchCommands.Move sketchCommandsMove() {
            return new SketchCommands.Move(files.source(), files.sink());
        }

        private SketchCommands.Polygon sketchCommandsPolygon() {
            return new SketchCommands.Polygon(files.source(), files.sink());
        }

        private ParameterCommands.Parameters parameterCommandsParameters() {
            return new ParameterCommands.Parameters();
        }

        private ParameterCommands.Configurations parameterCommandsConfigurations() {
            return new ParameterCommands.Configurations();
        }

        private ParameterCommands.ListParameters parameterCommandsListParameters() {
            return new ParameterCommands.ListParameters(files.source(), files.sink());
        }

        private ParameterCommands.SetParameter parameterCommandsSetParameter() {
            return new ParameterCommands.SetParameter(files.source(), files.sink());
        }

        private ParameterCommands.RenameParameter parameterCommandsRenameParameter() {
            return new ParameterCommands.RenameParameter(files.source(), files.sink());
        }

        private ParameterCommands.RemoveParameter parameterCommandsRemoveParameter() {
            return new ParameterCommands.RemoveParameter(files.source(), files.sink());
        }

        private ParameterCommands.Bind parameterCommandsBind() {
            return new ParameterCommands.Bind(files.source(), files.sink());
        }

        private ParameterCommands.ListConfigurations parameterCommandsListConfigurations() {
            return new ParameterCommands.ListConfigurations(files.source(), files.sink());
        }

        private ParameterCommands.AddConfiguration parameterCommandsAddConfiguration() {
            return new ParameterCommands.AddConfiguration(files.source(), files.sink());
        }

        private ParameterCommands.Override parameterCommandsOverride() {
            return new ParameterCommands.Override(files.source(), files.sink());
        }

        private ParameterCommands.Activate parameterCommandsActivate() {
            return new ParameterCommands.Activate(files.source(), files.sink());
        }

        private ParameterCommands.RenameConfiguration parameterCommandsRenameConfiguration() {
            return new ParameterCommands.RenameConfiguration(files.source(), files.sink());
        }

        private ParameterCommands.RemoveConfiguration parameterCommandsRemoveConfiguration() {
            return new ParameterCommands.RemoveConfiguration(files.source(), files.sink());
        }

        private UpdateCommands.Update updateCommandsUpdate() {
            return new UpdateCommands.Update(application.updates());
        }

        private UpdateCommands.Check updateCommandsCheck() {
            return new UpdateCommands.Check(application.updates());
        }
    }
}
