package dev.voxelbrew.printer.moonraker;

record Connection(String address, String serial, String secret) {
    @Override
    public String toString() {
        return "Connection[redacted]";
    }
}
