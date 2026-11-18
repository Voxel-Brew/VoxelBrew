package dev.voxelbrew.printer.bambulab;

record Connection(String address, String serial, String secret) {
    @Override
    public String toString() {
        return "Connection[redacted]";
    }
}
