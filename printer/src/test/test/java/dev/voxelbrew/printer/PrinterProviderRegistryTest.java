package dev.voxelbrew.printer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.printer.driver.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterProviderRegistryTest {
    @TempDir
    Path directory;

    private static Driver driver(String id) {
        return new Driver() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String name() {
                return id;
            }

            @Override
            public PrintResult print(DriverConfiguration c, dev.voxelbrew.api.model.printing.PrintArtifact g) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DriverStatus readPrinterStatus(DriverConfiguration c) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DriverGui gui() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void firstSuppliedDriverWinsAndClosedRegistryRejectsLookups() throws Exception {
        Driver first = driver("same"), second = driver("same");
        var registry = new ServiceLoaderDriverRegistry(directory, List.of(first, second));
        assertThat(registry.find("same")).contains(first);
        assertThat(registry.drivers()).contains(first).doesNotContain(second);
        assertThat(registry.find("missing")).isEmpty();
        registry.close();
        registry.close();
        assertThatThrownBy(() -> registry.find("same")).isInstanceOf(IllegalStateException.class);
    }
}
