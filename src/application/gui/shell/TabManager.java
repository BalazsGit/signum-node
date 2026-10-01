package application.gui.shell;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;

/**
 * Manages the main application tabbed pane.
 *
 * <p>
 * The exposed component is the bare {@code JTabbedPane} itself. The
 * optional trailing button (the shutdown icon) lives at the right end of
 * the module tab row through FlatLaf's {@code JTabbedPane.trailingComponent}
 * client property — a real component inside the row, so it costs no
 * pixels below the tab row (an EAST-side panel next to the pane would
 * span the pane's full height and leave a dead band beside the content).
 * </p>
 *
 * <p>
 * Defensively sets the tabLayoutPolicy explicitly to ensure it inherits the
 * application's configured default from GuiManager, regardless of UIManager
 * state at construction time.
 * </p>
 */
public class TabManager {

    private final JTabbedPane mainTabbedPane;

    public TabManager() {
        mainTabbedPane = new JTabbedPane();
        // Defensively apply the application-wide tab layout policy.
        // Even though AppearanceModule.applyDefaultsAfterLaf() sets UIManager defaults,
        // this explicit call guarantees the policy is correct regardless of init timing.
        mainTabbedPane.setTabLayoutPolicy(
                application.utils.gui.GuiManager.getInstance().getTabLayoutPolicy());
    }

    /**
     * Installs the trailing content at the right end of the module tab row.
     * The content is hosted in a small transparent container (the client
     * property requires a {@link JPanel} container) and rendered by the
     * Look and Feel inside the tab row — no dead band below it.
     *
     * @param content the trailing content (e.g. a row holding the restart and
     *                shutdown icons), ignored when {@code null}
     */
    public void setTrailingComponent(JComponent content) {
        if (content == null) {
            return;
        }
        // Pin the content to the trailing (right) edge with EAST, not CENTER:
        // FlatLaf renders the trailingComponent and, with the default
        // TabbedPane.tabAreaAlignment=leading, stretches it to fill the leftover
        // tab-row width (from the last tab to the right edge). CENTER would let the
        // content expand to fill that whole band; EAST keeps it at its preferred
        // width, right-aligned (directly under the window's close X).
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(content, BorderLayout.EAST);
        mainTabbedPane.putClientProperty("JTabbedPane.trailingComponent", wrap);
    }

    public JComponent getComponent() {
        return mainTabbedPane;
    }

    public void addModuleTab(String title, JComponent content) {
        mainTabbedPane.addTab(title, content);
    }
}
