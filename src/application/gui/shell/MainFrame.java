package application.gui.shell;

import application.gui.glassPanel.GlassPanelManager;
import application.kernel.ApplicationShutdown;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JToolBar;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * Main application frame containing the toolbar and tabbed module area.
 * 
 * The top toolbar contains global application actions (Shutdown, Restart).
 * Module-specific tabs are managed by TabManager below the toolbar.
 */
public class MainFrame extends JFrame {

    private final TabManager tabManager;
    private final JToolBar mainToolbar;

    public MainFrame() {
        setTitle("Signum Platform");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Initialize the GlassPanel at the application level.
        GlassPanelManager.initialize(this);

        // Create top toolbar with global actions
        mainToolbar = createMainToolbar();
        add(mainToolbar, BorderLayout.NORTH);

        // Tab area for modules
        this.tabManager = new TabManager();
        add(tabManager.getComponent(), BorderLayout.CENTER);
    }

    /**
     * Creates the main application toolbar containing global action buttons.
     */
    private JToolBar createMainToolbar() {
        JToolBar toolbar = new JToolBar("");
        toolbar.setRollover(true);
        toolbar.setFloatable(false);
        toolbar.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        // Shutdown button
        JButton shutdownButton = createShutdownButton();
        toolbar.add(shutdownButton);

        return toolbar;
    }

    /**
     * Creates the Shutdown button with proper styling and confirmation dialog.
     */
    private JButton createShutdownButton() {
        JButton button = new JButton("Shutdown");
        button.setToolTipText("Gracefully shut down all components and exit the application");

        // Make the font slightly bold for visibility
        Font currentFont = button.getFont();
        if (currentFont != null) {
            button.setFont(currentFont.deriveFont(currentFont.getStyle() | Font.BOLD));
        }

        button.addActionListener(e -> confirmAndShutdown());

        return button;
    }

    /**
     * Shows a confirmation dialog before initiating the shutdown sequence.
     */
    private void confirmAndShutdown() {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to shut down the entire application?\n" +
                "All running nodes will be stopped.",
                "Confirm Application Shutdown",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (result == JOptionPane.YES_OPTION) {
            initiateShutdown();
        }
    }

    /**
     * Initiates the graceful shutdown sequence.
     * 1. Disable this frame to prevent further user interaction.
     * 2. Execute the ApplicationShutdown orchestrator which calls all registered
     *    module stop() methods in priority order.
     * 3. Exit the JVM after all components are cleaned up.
     */
    private void initiateShutdown() {
        // Disable the frame to prevent further interaction
        this.setEnabled(false);

        // Show shutdown progress feedback on the title bar
        this.setTitle("Signum Platform - Shutting down...");

        // Execute the shutdown orchestrator
        ApplicationShutdown.getInstance().executeShutdownSequence();

        // Exit JVM with clean code
        System.exit(0);
    }

    public TabManager getTabManager() {
        return tabManager;
    }

    public JToolBar getMainToolbar() {
        return mainToolbar;
    }
}
