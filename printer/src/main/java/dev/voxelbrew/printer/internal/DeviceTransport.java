package dev.voxelbrew.printer.internal;

import dev.voxelbrew.api.printer.driver.DriverStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.DoubleConsumer;

/** Blocking operations; hosts invoke these on their device worker, never the UI thread. */
public interface DeviceTransport extends AutoCloseable {

    record Upload(String filename, String sha256) {}

    DriverStatus status() throws IOException;

    Upload upload(Path artifact, DoubleConsumer progress) throws IOException;

    void start(Upload upload) throws IOException;

    @Override
    void close();
}
