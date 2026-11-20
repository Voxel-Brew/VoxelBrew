package dev.voxelbrew.cli;

import dev.voxelbrew.api.importer.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;
import picocli.CommandLine.*;

final class ImportCommands {
    @Command(name = "importers", mixinStandardHelpOptions = true, description = "List built-in and external importers")
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Importers implements Callable<Integer> {
        private final java.util.function.Supplier<ImporterProviderRegistry> importers;

        @Option(names = "--plugin-dir")
        Path plugins = dev.voxelbrew.util.services.Services.load(dev.voxelbrew.api.userconfig.UserConfigService.class)
                .pluginsFolder();

        @Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try (var registry = importers.get().withDirectory(plugins)) {
                for (var descriptor : registry.descriptors()) {
                    spec.commandLine()
                            .getOut()
                            .println(descriptor.id() + "\t" + descriptor.name() + "\t" + descriptor.version());
                }
            }
            return 0;
        }
    }

    @Command(name = "import", mixinStandardHelpOptions = true, description = "Import a document to .vxb")
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class ImportFile implements Callable<Integer> {
        private final java.util.function.Supplier<ImporterProviderRegistry> importers;
        private final DocumentSave documents;

        @Parameters(index = "0")
        Path source;

        @Option(names = "--format", required = true)
        String format;

        @Option(names = "--output", description = "Destination .vxb for document importers")
        Path output;

        @Option(names = "--option", split = ",")
        Map<String, String> options = new LinkedHashMap<>();

        @Option(names = "--plugin-dir")
        Path plugins = dev.voxelbrew.util.services.Services.load(dev.voxelbrew.api.userconfig.UserConfigService.class)
                .pluginsFolder();

        @Option(names = "--overwrite")
        boolean overwrite;

        @Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try (var registry = importers.get().withDirectory(plugins)) {
                if (output == null) throw new IllegalArgumentException("Document import requires --output");
                if (source.toAbsolutePath()
                                .normalize()
                                .equals(output.toAbsolutePath().normalize())
                        || java.nio.file.Files.exists(output) && java.nio.file.Files.isSameFile(source, output)) {
                    throw new IllegalArgumentException("Import destination must differ from its source");
                }
                var imported = registry.require(format).create().importFrom(new CliImportWindow(source, options));
                if (imported.isPresent()) {
                    var document = imported.orElseThrow();
                    documents.save(document, output, overwrite);
                    spec.commandLine().getOut().println("Imported " + document.id() + " to " + output);
                }
            }
            return 0;
        }
    }

    static void register(CommandLine command) {
        command.addSubcommand("importers", new CommandLine(Importers.class, command.getFactory()));
        command.addSubcommand("import", new CommandLine(ImportFile.class, command.getFactory()));
    }

    private ImportCommands() {}
}
