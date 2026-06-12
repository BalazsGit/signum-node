package application.kernel;

import application.api.Module;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

public class ModuleRegistry {
    private final List<Module> modules = new ArrayList<>();

    public void discoverModules() {
        // Professzionális megoldás: Java ServiceLoader használata
        // Ehhez a modulok META-INF/services/application.api.Module fájljában kell
        // regisztrálni az impl-eket
        ServiceLoader<Module> loader = ServiceLoader.load(Module.class);
        for (Module module : loader) {
            modules.add(module);
        }
    }

    public List<Module> getModules() {
        return Collections.unmodifiableList(modules);
    }
}