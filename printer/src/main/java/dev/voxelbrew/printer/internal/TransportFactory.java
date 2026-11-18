package dev.voxelbrew.printer.internal;

import dev.voxelbrew.api.printer.driver.DriverConfiguration;
import java.io.IOException;

@FunctionalInterface
public interface TransportFactory {
    DeviceTransport open(DriverConfiguration configuration) throws IOException;
}
