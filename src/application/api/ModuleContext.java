package application.api;

import java.nio.file.Path;

public interface ModuleContext {
    Path getConfigDirectory();

    void requestRestart();

    void shutdown();

    /**
     * Returns the profile name that should be started when the application is booted in
     * single-profile mode (headless {@code profile run <name>}), or {@code null} for the
     * normal multi-profile / autostart boot.
     */
    default String getTargetProfileName() {
        return null;
    }

    /**
     * Returns {@code true} when the application runs in headless mode (the {@code -l}
     * flag or no display available) and therefore no GUI shell is started.
     * <p>
     * Modules must not start GUI-dependent engines (e.g. the browser's CEF) nor
     * return UI in this mode, regardless of whether a display exists on the host.
     * The default (false) keeps custom/test contexts GUI-oriented.
     */
    default boolean isHeadless() {
        return false;
    }

    // Itt lehetne egy EventBus is a modulok közötti kommunikációhoz
}
