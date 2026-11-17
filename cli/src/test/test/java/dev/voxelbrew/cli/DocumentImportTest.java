package dev.voxelbrew.cli;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.importer.DocumentReader;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentImportTest {
    @TempDir
    Path directory;

    @Test
    void daggerImportsVxbAndJsonAndProtectsSourcesAndExistingOutputs() throws Exception {
        var expected = CadDocument.empty();
        for (var format : List.of("vxb", "json")) {
            Path source = directory.resolve("source." + format);
            Path target = directory.resolve(format + "-copy.vxb");
            if (format.equals("vxb")) Files.write(source, DocumentCodec.encode(expected));
            else Files.writeString(source, DocumentCodec.encodeJson(expected));
            byte[] original = Files.readAllBytes(source);
            var cli = VoxelBrew.commandLine();
            cli.setOut(new PrintWriter(new StringWriter()));
            cli.setErr(new PrintWriter(new StringWriter()));
            String[] args = {"document", "import", source.toString(), "--format", format, "--output", target.toString()
            };
            assertThat(cli.execute(args)).isEqualTo(0);
            assertThat(DocumentReader.load(target).id()).isEqualTo(expected.id());
            byte[] output = Files.readAllBytes(target);
            assertThat(cli.execute(args)).isNotEqualTo(0);
            assertThat(Files.readAllBytes(target)).containsExactly(output);
            assertThat(cli.execute(
                            "document",
                            "import",
                            source.toString(),
                            "--format",
                            format,
                            "--output",
                            source.toString(),
                            "--overwrite"))
                    .isNotEqualTo(0);
            assertThat(Files.readAllBytes(source)).containsExactly(original);
        }
    }
}
