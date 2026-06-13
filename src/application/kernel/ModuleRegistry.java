package application.kernel;

import application.api.Module;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

public class ModuleRegistry {
    private final List<Module> modules = new ArrayList<>();

    public void discoverModules() {
        // Professional solution: Use Java ServiceLoader
        // For this, module implementations must be registered in the
        // META-INF/services/application.api.Module file
        // to be discovered.
        ServiceLoader<Module> loader = ServiceLoader.load(Module.class);
        for (Module module : loader) {
            modules.add(module);
        }
    }

    public List<Module> getModules() {
        return Collections.unmodifiableList(modules);
    }
}