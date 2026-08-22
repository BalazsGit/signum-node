package application.module.system;

import java.util.ArrayList;

import javax.swing.JComponent;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyledDocument;

import application.api.Module;
import application.api.ModuleContext;
import application.module.appearance.AppearanceModule;
import application.module.node.Signum;
import application.module.node.gui.SystemConsoleSubscriber;
import application.utils.gui.console.ConsolePanelConfiguration;
import application.utils.gui.console.UnifiedConsolePanel;
import application.utils.logging.ConsoleColorScheme;
import application.utils.logging.SystemLogger;
import application.utils.logging.event.LogFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * System Console module that aggregates ALL log events from every running profile.
 * <p>
 * Architecture:
 * <pre>
 *   ProfileLogRouter.RouterJULHandler.publish(LogRecord)
 *       |
 *       +-- dispatchToGlobalSubscribers() --> SystemConsoleSubscriber -> this UI
 *       |
 *       +-- ProfileLogContext.dispatch() --> ProfileConsoleSubscriber -> Profile UI tab
 * </pre>
 * <p>
 * Uses {@link UnifiedConsolePanel} for all console rendering, filtering, and input.
 * This module is now a thin coordinator that delegates to the unified panel.
 * </p>
 */
public class SystemConsoleModule implements Module {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemConsoleModule.class);

    private UnifiedConsolePanel unifiedPanel;
    private Runnable appearanceListener;

    @Override
    public String getId() {
        return "system-console";
    }

    @Override
    public String getDisplayName() {
        return "System Console";
    }

    @Override
    public void init(ModuleContext context) {
        // 1. Create unified panel with system console configuration
        ConsolePanelConfiguration config = ConsolePanelConfiguration.systemConsole()
                .withShowFilterHeader(true)
                .withShowCommandInput(true)
                .withMaxLines(1000)
                .withColorScheme(ConsoleColorScheme.getDefault())
                .withCommandHandler(cmd -> {
                    // PLACEHOLDER (P3 Unit 8): This System Console is a cross-profile aggregator and
                    // owns no single node facade, so there is no architecture-correct way to route a
                    // node command here yet. The legacy Signum.processCommand(cmd) bridge has been
                    // removed. Command routing is expected to be defined later — most likely via
                    // targeted controls that address a specific module/node so each command is
                    // delivered to the right instance. Until then this is a deliberate no-op.
                    LOGGER.info("[SystemConsole] Command input received but routing not yet defined: '{}'", cmd);
                });

        this.unifiedPanel = new UnifiedConsolePanel(config, SystemConsoleSubscriber.class);

        // 2. Register subscriber with SystemLogger
        SystemConsoleSubscriber subscriber = (SystemConsoleSubscriber) unifiedPanel.getSubscriber();
        SystemLogger.getInstance().addSubscriber(subscriber);

        // 3. Initialize filter header profile list
        if (unifiedPanel.getFilterHeader() != null) {
            unifiedPanel.getFilterHeader().setProfiles("(all)");
            unifiedPanel.getFilterHeader().rebuildFilter();
        }

        // 4. Flush bootstrap logs that occurred before GUI initialized
        flushBootstrapLogs();

        // 5. Scroll to bottom after bootstrap logs rendered
        SwingUtilities.invokeLater(() -> unifiedPanel.scrollToBottom());

        // 6. Subscribe to appearance (LAF) changes
        appearanceListener = () -> {
            if (unifiedPanel != null) {
                unifiedPanel.applyAppearanceUpdate();
            }
        };
        AppearanceModule.registerAppearanceListener(appearanceListener);
    }

    /**
     * Flushes bootstrap logs that occurred before GUI initialized.
     * <p>
     * All lines are written in a SINGLE EDT dispatch to prevent multiple competing
     * scroll-to-bottom calls. Each line would previously trigger its own invokeLater,
     * causing scrollbar position jumping during panel initialization.
     * </p>
     */
    private void flushBootstrapLogs() {
        JTextPane textPane = unifiedPanel.getTextPane();
        if (textPane == null) {
            return;
        }

        // Signum.BOOTSTRAP_LOGS is a synchronized ArrayList - copy to avoid holding lock during EDT dispatch
        ArrayList<String> snapshot;
        synchronized (Signum.BOOTSTRAP_LOGS) {
            snapshot = new ArrayList<>(Signum.BOOTSTRAP_LOGS);
        }

        // Consolidate all bootstrap lines into ONE EDT callback to prevent scroll jumping
        final ArrayList<String> linesToAppend = new ArrayList<>(snapshot);
        linesToAppend.add("--- System Console initialized ---");

        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument doc = (StyledDocument) textPane.getDocument();
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                for (String line : linesToAppend) {
                    if (line != null && !line.isEmpty()) {
                        int len = doc.getLength();
                        doc.insertString(len, line + "\n", attrs);
                    }
                }
            } catch (BadLocationException e) {
                // Ignore - document may have been disposed
            }
        });
    }

    /**
     * Sets the active filter on this console module.
     */
    public void setActiveFilter(LogFilter filter) {
        if (unifiedPanel != null) {
            unifiedPanel.setActiveFilter(filter);
        }
    }

    /**
     * Updates the profile dropdown in the filter header with current profiles.
     */
    public void updateProfileList(java.util.List<String> profiles) {
        if (unifiedPanel.getFilterHeader() != null) {
            SwingUtilities.invokeLater(() -> unifiedPanel.getFilterHeader().setProfiles(profiles));
        }
    }

    @Override
    public void start() {
        // Nothing to start - subscriber auto-processes events
    }

    @Override
    public void stop() {
        // Unregister from SystemLogger
        if (unifiedPanel != null) {
            SystemConsoleSubscriber subscriber = (SystemConsoleSubscriber) unifiedPanel.getSubscriber();
            if (subscriber != null) {
                SystemLogger.getInstance().removeSubscriber(subscriber);
            }
            unifiedPanel.dispose();
            unifiedPanel = null;
        }

        // Unregister appearance listener
        if (appearanceListener != null) {
            AppearanceModule.removeAppearanceListener(appearanceListener);
            appearanceListener = null;
        }
    }

    @Override
    public JComponent getUI() {
        return unifiedPanel;
    }

    /**
     * Returns the active SystemConsoleSubscriber (for external access if needed).
     */
    public SystemConsoleSubscriber getSubscriber() {
        if (unifiedPanel != null && unifiedPanel.getSubscriber() instanceof SystemConsoleSubscriber) {
            return (SystemConsoleSubscriber) unifiedPanel.getSubscriber();
        }
        return null;
    }
}