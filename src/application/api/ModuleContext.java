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

    // Itt lehetne egy EventBus is a modulok közötti kommunikációhoz
}
