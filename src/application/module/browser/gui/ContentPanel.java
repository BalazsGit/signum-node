package application.module.browser.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Hosts the CEF components of <em>all</em> open tabs (plan §4.1).
 * <p>
 * A windowed CEF browser is expensive to (re)create, so every tab's component
 * stays in this container for the tab's whole lifetime; switching tabs (T4)
 * only toggles visibility. All components stack in the CENTER slot — the
 * visible one is on top.
 */
public final class ContentPanel extends JPanel {

    private final Map<String, JComponent> components = new LinkedHashMap<>();

    public ContentPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(640, 480));
    }

    public void addBrowser(String tabId, JComponent component) {
        components.put(tabId, component);
        add(component, BorderLayout.CENTER);
    }

    public void removeBrowser(String tabId) {
        JComponent component = components.remove(tabId);
        if (component != null) {
            remove(component);
        }
    }

    /** Shows only the active tab's component (T4). */
    public void showBrowser(String activeTabId) {
        components.forEach((id, component) -> component.setVisible(id.equals(activeTabId)));
        repaint();
    }
}
