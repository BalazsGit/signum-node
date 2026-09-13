package application.module.node.gui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Lightweight placeholder panel for lazy-loaded node profile tabs.
 * Shows a single centered "Loading ..." hint until the actual heavy
 * NodeProfilePanel is loaded on demand (when the tab becomes visible).
 */
public class NodePlaceholderPanel extends JPanel {

    private final Runnable onLoadCallback;
    private final String profileName;
    private boolean loaded = false;

    /**
     * Creates a placeholder panel that triggers lazy loading when shown.
     *
     * @param profileName    the name of the profile this tab represents
     * @param onLoadCallback callback to execute when tab becomes visible
     */
    public NodePlaceholderPanel(String profileName, Runnable onLoadCallback) {
        this.profileName = profileName;
        this.onLoadCallback = onLoadCallback;

        setLayout(new GridBagLayout());

        // A single, centered "Loading ..." hint - nothing else.
        JLabel loadingLabel = new JLabel("Loading ...");
        add(loadingLabel, new GridBagConstraints());

        // Trigger lazy loading when this component becomes visible (tab selected)
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                if (!loaded) {
                    loaded = true;
                    onLoadCallback.run();
                }
            }
        });
    }

    /**
     * Returns the profile name this placeholder represents.
     */
    public String getProfileName() {
        return profileName;
    }

    /**
     * Marks the placeholder as loaded to prevent repeated callbacks.
     */
    public void markAsLoaded() {
        this.loaded = true;
    }

    public boolean isLoaded() {
        return loaded;
    }
}