package consumer;

import dev.voxelbrew.api.exporter.*;
import java.nio.file.Files;

/** Independent API-only document exporter used to qualify runtime JAR discovery. */
public class DocumentProbeProvider implements ExporterProvider<FileExportDialog.Destination> {
    public static final class Duplicate extends DocumentProbeProvider {}

    public static final class Failing extends DocumentProbeProvider {
        @Override
        public ExporterDescriptor descriptor() {
            return new ExporterDescriptor("failing", "Failure", "1.0");
        }

        @Override
        public Exporter<FileExportDialog.Destination> create() {
            return (document, window) -> {
                if (window.show(new FileExportDialog("Failure", "txt")).isEmpty()) return java.util.Optional.empty();
                throw new java.io.IOException("Deliberate provider failure");
            };
        }
    }

    public DocumentProbeProvider() {}

    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("document-probe", "Document probe", "1.0");
    }

    @Override
    public Exporter<FileExportDialog.Destination> create() {
        return (document, window) -> {
            var selected = window.show(new FileExportDialog("Document probe", "txt"));
            if (selected.isEmpty()) return java.util.Optional.empty();
            var destination = selected.get();
            var options = destination.overwrite()
                    ? new java.nio.file.OpenOption[] {
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
                    }
                    : new java.nio.file.OpenOption[] {java.nio.file.StandardOpenOption.CREATE_NEW};
            Files.writeString(destination.path(), "document=" + document.id(), options);
            return selected;
        };
    }
}
