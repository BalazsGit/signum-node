package application.api;

import java.nio.file.Path;

public interface ModuleContext {
    Path getConfigDirectory();

    void requestRestart();

    void shutdown();
    // Itt lehetne egy EventBus is a modulok közötti kommunikációhoz
}
