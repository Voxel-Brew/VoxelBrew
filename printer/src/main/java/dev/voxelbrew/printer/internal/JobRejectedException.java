package dev.voxelbrew.printer.internal;
/** A definite device rejection, safe to report without calling the submission uncertain. */
public final class JobRejectedException extends java.io.IOException {
    private static final long serialVersionUID = 1L;

    public JobRejectedException(String message) {
        super(message);
    }
}
