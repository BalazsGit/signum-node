package application.gui.shell;

import javax.swing.JTabbedPane;
import javax.swing.JComponent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class TabManager {
    private final JTabbedPane mainTabbedPane;

    public TabManager() {
        this.mainTabbedPane = new JTabbedPane();
    }

    public JTabbedPane getComponent() {
        return mainTabbedPane;
    }

    public void addModuleTab(String title, JComponent content) {
        mainTabbedPane.addTab(title, content);
    }
}
