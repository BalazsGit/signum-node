package application.module.node.profile;

import org.slf4j.Marker;

import application.utils.logging.ProfileLogger;

/**
 * Adapter that bridges the legacy SLF4J Logger in NodeProfile with the new
 * centralized {@link ProfileLogger} system.
 * <p>
 * Instead of modifying every Service class signature to accept a ModuleLogger,
 * this adapter wraps a ProfileLogger and provides the same API as org.slf4j.Logger.
 * Services continue using their existing LOGGER fields while all events flow
 * through the centralized routing system.
 * </p>
 */
public final class NodeProfileAdapter implements org.slf4j.Logger {

    private final ProfileLogger delegate;

    public NodeProfileAdapter(ProfileLogger delegate) {
        this.delegate = delegate;
    }

    /** @return the underlying ProfileLogger instance */
    public ProfileLogger getDelegate() {
        return delegate;
    }

    // ── Name ────────────────────────────────────────────────────────────

    @Override
    public String getName() {
        return delegate.getName();
    }

    // ── Level Checks ────────────────────────────────────────────────────

    @Override
    public boolean isTraceEnabled() {
        return true; // Delegate always handles level filtering
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return true;
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return true;
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return true;
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return true;
    }

    // ── TRACE ───────────────────────────────────────────────────────────

    @Override
    public void trace(String msg) {
        delegate.trace(msg);
    }

    @Override
    public void trace(Marker marker, String msg) {
        delegate.trace(msg);
    }

    @Override
    public void trace(String format, Object arg) {
        delegate.trace(formatString(format, arg));
    }

    @Override
    public void trace(Marker marker, String format, Object arg) {
        delegate.trace(formatString(format, arg));
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
        delegate.trace(formatString(format, arg1, arg2));
    }

    @Override
    public void trace(Marker marker, String format, Object arg1, Object arg2) {
        delegate.trace(formatString(format, arg1, arg2));
    }

    @Override
    public void trace(String format, Object... arguments) {
        delegate.trace(formatString(format, arguments));
    }

    @Override
    public void trace(Marker marker, String format, Object... arguments) {
        delegate.trace(formatString(format, arguments));
    }

    @Override
    public void trace(String msg, Throwable t) {
        delegate.trace(msg + ": " + t.getMessage());
    }

    @Override
    public void trace(Marker marker, String msg, Throwable t) {
        delegate.trace(msg + ": " + t.getMessage());
    }

    // ── DEBUG ───────────────────────────────────────────────────────────

    @Override
    public void debug(String msg) {
        delegate.debug(msg);
    }

    @Override
    public void debug(Marker marker, String msg) {
        delegate.debug(msg);
    }

    @Override
    public void debug(String format, Object arg) {
        delegate.debug(formatString(format, arg));
    }

    @Override
    public void debug(Marker marker, String format, Object arg) {
        delegate.debug(formatString(format, arg));
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        delegate.debug(formatString(format, arg1, arg2));
    }

    @Override
    public void debug(Marker marker, String format, Object arg1, Object arg2) {
        delegate.debug(formatString(format, arg1, arg2));
    }

    @Override
    public void debug(String format, Object... arguments) {
        delegate.debug(formatString(format, arguments));
    }

    @Override
    public void debug(Marker marker, String format, Object... arguments) {
        delegate.debug(formatString(format, arguments));
    }

    @Override
    public void debug(String msg, Throwable t) {
        delegate.debug(msg + ": " + t.getMessage());
    }

    @Override
    public void debug(Marker marker, String msg, Throwable t) {
        delegate.debug(msg + ": " + t.getMessage());
    }

    // ── INFO ────────────────────────────────────────────────────────────

    @Override
    public void info(String msg) {
        delegate.info(msg);
    }

    @Override
    public void info(Marker marker, String msg) {
        delegate.info(msg);
    }

    @Override
    public void info(String format, Object arg) {
        delegate.info(formatString(format, arg));
    }

    @Override
    public void info(Marker marker, String format, Object arg) {
        delegate.info(formatString(format, arg));
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        delegate.info(formatString(format, arg1, arg2));
    }

    @Override
    public void info(Marker marker, String format, Object arg1, Object arg2) {
        delegate.info(formatString(format, arg1, arg2));
    }

    @Override
    public void info(String format, Object... arguments) {
        delegate.info(formatString(format, arguments));
    }

    @Override
    public void info(Marker marker, String format, Object... arguments) {
        delegate.info(formatString(format, arguments));
    }

    @Override
    public void info(String msg, Throwable t) {
        delegate.info(msg + ": " + t.getMessage());
    }

    @Override
    public void info(Marker marker, String msg, Throwable t) {
        delegate.info(msg + ": " + t.getMessage());
    }

    // ── WARN ────────────────────────────────────────────────────────────

    @Override
    public void warn(String msg) {
        delegate.warn(msg);
    }

    @Override
    public void warn(Marker marker, String msg) {
        delegate.warn(msg);
    }

    @Override
    public void warn(String format, Object arg) {
        delegate.warn(formatString(format, arg));
    }

    @Override
    public void warn(Marker marker, String format, Object arg) {
        delegate.warn(formatString(format, arg));
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        delegate.warn(formatString(format, arg1, arg2));
    }

    @Override
    public void warn(Marker marker, String format, Object arg1, Object arg2) {
        delegate.warn(formatString(format, arg1, arg2));
    }

    @Override
    public void warn(String format, Object... arguments) {
        delegate.warn(formatString(format, arguments));
    }

    @Override
    public void warn(Marker marker, String format, Object... arguments) {
        delegate.warn(formatString(format, arguments));
    }

    @Override
    public void warn(String msg, Throwable t) {
        delegate.warn(msg, t);
    }

    @Override
    public void warn(Marker marker, String msg, Throwable t) {
        delegate.warn(msg, t);
    }

    // ── ERROR ───────────────────────────────────────────────────────────

    @Override
    public void error(String msg) {
        delegate.error(msg);
    }

    @Override
    public void error(Marker marker, String msg) {
        delegate.error(msg);
    }

    @Override
    public void error(String format, Object arg) {
        delegate.error(formatString(format, arg));
    }

    @Override
    public void error(Marker marker, String format, Object arg) {
        delegate.error(formatString(format, arg));
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        delegate.error(formatString(format, arg1, arg2));
    }

    @Override
    public void error(Marker marker, String format, Object arg1, Object arg2) {
        delegate.error(formatString(format, arg1, arg2));
    }

    @Override
    public void error(String format, Object... arguments) {
        delegate.error(formatString(format, arguments));
    }

    @Override
    public void error(Marker marker, String format, Object... arguments) {
        delegate.error(formatString(format, arguments));
    }

    @Override
    public void error(String msg, Throwable t) {
        delegate.error(msg, t);
    }

    @Override
    public void error(Marker marker, String msg, Throwable t) {
        delegate.error(msg, t);
    }

    // ── String Formatting ───────────────────────────────────────────────

    private String formatString(String format, Object arg) {
        if (format == null) return null;
        return java.text.MessageFormat.format(format, arg);
    }

    private String formatString(String format, Object arg1, Object arg2) {
        if (format == null) return null;
        return java.text.MessageFormat.format(format, new Object[]{arg1, arg2});
    }

    private String formatString(String format, Object... arguments) {
        if (format == null) return null;
        return java.text.MessageFormat.format(format, arguments);
    }
}