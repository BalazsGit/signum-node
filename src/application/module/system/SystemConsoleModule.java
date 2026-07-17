package application.module.system;

import application.api.Module;
import application.api.ModuleContext;
import application.module.appearance.AppearanceModule;
import application.module.node.Signum;
import application.module.node.gui.ConsoleFilterHeader;
import application.module.node.gui.SystemConsoleSubscriber;
import application.utils.logging.SystemLogger;
import application.utils.logging.event.LogFilter;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

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
 * Uses {@link SystemConsoleSubscriber} (not a raw JUL Handler) for proper terminal-format
 * log output, profile-based color coding, batch rendering, and filter support.
 * </p>
 */
public class SystemConsoleModule implements Module {
    private JComponent mainPanel;
    private JTextPane textPane;
    private JScrollPane scrollPane;
    private SystemConsoleSubscriber subscriber;
    private ConsoleFilterHeader filterHeader;
    private Runnable appearanceListener;

    private static final Logger logger = Logger.getLogger(SystemConsoleModule.class.getName());

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
        this.mainPanel = createUI();

        // Create and register the SystemConsoleSubscriber with SystemLogger.
        // SystemLogger receives ALL log events forwarded from ProfileLogger instances,
        // providing a unified view equivalent to terminal output.
        // Flow: SLF4J/Logback → JUL Handler → ProfileLogRouter → ProfileLogger → SystemLogger → this UI
        StyledDocument doc = (StyledDocument) textPane.getDocument();
        subscriber = new SystemConsoleSubscriber(doc);
        // Enable smart auto-scroll: only scroll when user is near the bottom
        subscriber.setScrollPane(scrollPane);
        SystemLogger.getInstance().addSubscriber(subscriber);

        // Wire filter header → subscriber filter chain
        if (filterHeader != null) {
            filterHeader.setProfiles("(all)"); // Will be updated dynamically as profiles start
            filterHeader.rebuildFilter(); // Initialize with default filter state
        }

        // Flush bootstrap logs that occurred before GUI initialized
        flushBootstrapLogs();

        // Subscribe to appearance (LAF) changes
        appearanceListener = () -> {
            if (mainPanel != null) {
                SwingUtilities.updateComponentTreeUI(mainPanel);
                textPane.setFont(AppearanceModule.getActiveConsoleFont());
            }
        };
        AppearanceModule.registerAppearanceListener(appearanceListener);
    }

    /**
     * Flushes bootstrap logs that occurred before GUI initialized.
     */
    private void flushBootstrapLogs() {
        // Signum.BOOTSTRAP_LOGS is a synchronized ArrayList - copy to avoid holding lock during EDT dispatch
        ArrayList<String> snapshot;
        synchronized (Signum.BOOTSTRAP_LOGS) {
            snapshot = new ArrayList<>(Signum.BOOTSTRAP_LOGS);
        }
        for (String log : snapshot) {
            appendBootstrapLine(log);
        }
        appendBootstrapLine("--- System Console initialized ---");
    }

    /**
     * Appends a plain bootstrap line directly to the document (bypasses subscriber batching).
     */
    private void appendBootstrapLine(final String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    StyledDocument doc = (StyledDocument) textPane.getDocument();
                    int len = doc.getLength();
                    SimpleAttributeSet attrs = new SimpleAttributeSet();
                    Color fg = UIManager.getColor("TextArea.foreground");
                    if (fg != null) {
                        StyleConstants.setForeground(attrs, fg);
                    }
                    doc.insertString(len, line + "\n", attrs);
                    textPane.setCaretPosition(doc.getLength());
                } catch (BadLocationException e) {
                    logger.warning("BadLocationException appending bootstrap log: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Sets the active filter on this console module.
     * Called externally when profile list changes or filter state needs updating.
     */
    public void setActiveFilter(LogFilter filter) {
        if (subscriber != null) {
            subscriber.setFilter(filter);
        }
    }

    /**
     * Updates the profile dropdown in the filter header with current profiles.
     */
    public void updateProfileList(java.util.List<String> profiles) {
        if (filterHeader != null) {
            SwingUtilities.invokeLater(() -> filterHeader.setProfiles(profiles));
        }
    }

    private JComponent createUI() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Filter toolbar (top) — callback is invoked AFTER init() creates subscriber,
        // so we guard against null. The filterHeader.rebuildFilter() in init() will
        // push the default filter through this same callback path.
        filterHeader = new ConsoleFilterHeader(combinedFilter -> {
            if (subscriber != null) {
                subscriber.setFilter(combinedFilter);
            }
        });
        panel.add(filterHeader, BorderLayout.NORTH);

        // Use JTextPane (supports StyledDocument) instead of JTextArea.
        // SystemConsoleSubscriber writes to StyledDocument for color-coded output.
        textPane = new JTextPane() {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(UIManager.getColor("TextArea.background"));
                setForeground(UIManager.getColor("TextArea.foreground"));
                setFont(AppearanceModule.getActiveConsoleFont());
            }
        };
        textPane.setEditable(false);
        textPane.setBackground(UIManager.getColor("TextArea.background"));
        textPane.setForeground(UIManager.getColor("TextArea.foreground"));
        textPane.setFont(AppearanceModule.getActiveConsoleFont());

        this.scrollPane = new JScrollPane(textPane);
        this.scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        panel.add(this.scrollPane, BorderLayout.CENTER);

        // Command input area
        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        JTextField inputField = new JTextField();
        inputField.setToolTipText("Enter node command (e.g. .help, .pause, .resume)");

        ActionListener sendAction = e -> {
            String cmd = inputField.getText().trim();
            if (!cmd.isEmpty()) {
                logger.info("Executing command: " + cmd);
                new Thread(() -> Signum.processCommand(cmd)).start();
                inputField.setText("");
            }
        };

        inputField.addActionListener(sendAction);

        JButton sendButton = new JButton("Send");
        sendButton.addActionListener(sendAction);

        inputPanel.add(new JLabel(">"), BorderLayout.WEST);
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        panel.add(inputPanel, BorderLayout.SOUTH);

        return panel;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        // Unregister from SystemLogger
        if (subscriber != null) {
            SystemLogger.getInstance().removeSubscriber(subscriber);
            subscriber = null;
        }
        if (appearanceListener != null) {
            AppearanceModule.removeAppearanceListener(appearanceListener);
            appearanceListener = null;
        }
    }

    @Override
    public JComponent getUI() {
        return mainPanel;
    }

    /**
     * Returns the active SystemConsoleSubscriber (for external access if needed).
     */
    public SystemConsoleSubscriber getSubscriber() {
        return subscriber;
    }
}