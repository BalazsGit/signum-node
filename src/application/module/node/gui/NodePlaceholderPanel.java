package application.module.node.gui;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Lightweight placeholder panel for lazy-loaded node profile tabs.
 * Displays a loading spinner and profile name until the actual heavy
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

        setLayout(new FlowLayout(FlowLayout.CENTER, 20, 20));
        setBorder(BorderFactory.createEmptyBorder(40, 40, 40, 40));

        // Loading spinner
        JSpinner spinner = new JSpinner();

        // Profile name label
        JLabel titleLabel = new JLabel("Node Profile: " + profileName);
        titleLabel.setFont(getFont().deriveFont(java.awt.Font.BOLD, getFont().getSize() + 2));

        // Description label
        JLabel descLabel = new JLabel("Loading profile panel on demand...");

        add(spinner);
        add(titleLabel);
        add(descLabel);

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