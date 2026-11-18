package dev.voxelbrew.printer;

/** Public no-argument entry point required by classpath ServiceLoader discovery. */
public final class DefaultDriverRegistry extends ServiceLoaderDriverRegistry {
    public DefaultDriverRegistry() {
        super();
    }
}
