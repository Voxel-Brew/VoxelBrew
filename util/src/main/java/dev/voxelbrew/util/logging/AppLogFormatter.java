package dev.voxelbrew.util.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

final class AppLogFormatter extends Formatter {
    private final String identity;

    private static String severity(int level) {
        if (level >= Level.SEVERE.intValue()) return "ERROR";
        if (level >= Level.WARNING.intValue()) return "WARN";
        if (level >= Level.INFO.intValue()) return "INFO";
        return level >= Level.FINE.intValue() ? "DEBUG" : "TRACE";
    }

    AppLogFormatter(String component) {
        identity = component + ":" + ProcessHandle.current().pid();
    }

    @Override
    public String format(LogRecord record) {
        var text = new StringBuilder()
                .append(record.getInstant())
                .append(' ')
                .append(severity(record.getLevel().intValue()))
                .append(" [")
                .append(identity)
                .append("/thread-")
                .append(record.getLongThreadID())
                .append("] ")
                .append(record.getLoggerName())
                .append(" - ")
                .append(formatMessage(record))
                .append('\n');
        if (record.getThrown() != null) {
            var stack = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(stack));
            text.append(stack);
        }
        return text.toString();
    }
}
