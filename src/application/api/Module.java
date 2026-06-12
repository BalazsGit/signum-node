package application.api;

import javax.swing.JComponent;

public interface Module {
    String getId();

    String getDisplayName();

    void init(ModuleContext context);

    void start();

    void stop();

    // Visszaadja a modul fő UI komponensét (a tab tartalmát)
    JComponent getUI();
}
