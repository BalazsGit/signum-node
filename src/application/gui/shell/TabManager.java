package application.gui.shell;

import javax.swing.JTabbedPane;
import javax.swing.JComponent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Manages the main application tabbed pane.
 * 
 * Defensively sets the tabLayoutPolicy explicitly to ensure it inherits the
 * application's configured default from GuiManager, regardless of UIManager state
 * at construction time.
 */
public class TabManager {
    private final JTabbedPane mainTabbedPane;

    public TabManager() {
        this.mainTabbedPane = new JTabbedPane();
        // Defensively apply the application-wide tab layout policy.
        // Even though AppearanceModule.applyDefaultsAfterLaf() sets UIManager defaults,
        // this explicit call guarantees the policy is correct regardless of init timing.
        mainTabbedPane.setTabLayoutPolicy(
                application.utils.gui.GuiManager.getInstance().getTabLayoutPolicy());
    }

    public JTabbedPane getComponent() {
        return mainTabbedPane;
    }

    public void addModuleTab(String title, JComponent content) {
        mainTabbedPane.addTab(title, content);
    }
}
