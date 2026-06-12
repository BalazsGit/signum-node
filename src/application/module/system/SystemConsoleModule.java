package application.module.system;

import application.api.Module;
import application.api.ModuleContext;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.logging.Level;
import java.util.logging.Logger;
import application.module.appearance.AppearanceModule; // Import for Laf updates
import application.module.node.Signum;

public class SystemConsoleModule implements Module {
    private JComponent mainPanel;
    private JTextArea textArea; // Keep a reference to the text area
    private ConsoleHandler consoleHandler; // Custom log handler
    private Runnable appearanceListener; // Listener for Look and Feel changes

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

        // Regisztráljuk a naplókezelőt a gyökér loggerhez
        Logger rootLogger = Logger.getLogger("");
        consoleHandler = new ConsoleHandler(textArea, 2000);
        consoleHandler.setLevel(Level.INFO);
        rootLogger.addHandler(consoleHandler);

        // Feliratkozás a kinézet változásaira
        appearanceListener = () -> {
            if (mainPanel != null) {
                SwingUtilities.updateComponentTreeUI(mainPanel);
                // A konzol betűtípust explicit újra beállítjuk a biztonság kedvéért
                textArea.setFont(AppearanceModule.getActiveConsoleFont());
            }
        };
        AppearanceModule.registerAppearanceListener(appearanceListener);

        // Bootstrap logok betöltése, amik a GUI indulása előtt keletkeztek
        synchronized (Signum.BOOTSTRAP_LOGS) {
            for (String log : Signum.BOOTSTRAP_LOGS) {
                textArea.append(log + "\n");
            }
        }
        textArea.append("--- System Console initialized ---\n");
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }

    private JComponent createUI() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JLabel titleLabel = new JLabel("System Console");
        titleLabel.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 14f));
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(titleLabel, BorderLayout.NORTH);

        textArea = new JTextArea() {
            @Override
            public void updateUI() {
                super.updateUI();
                // Re-apply style on Look and Feel changes
                setBackground(UIManager.getColor("TextArea.background"));
                setForeground(UIManager.getColor("TextArea.foreground"));
                setFont(AppearanceModule.getActiveConsoleFont());
            }
        };
        textArea.setEditable(false);
        textArea.setBackground(UIManager.getColor("TextArea.background"));
        textArea.setForeground(UIManager.getColor("TextArea.foreground"));
        textArea.setFont(AppearanceModule.getActiveConsoleFont());
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Command input area
        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        JTextField inputField = new JTextField();
        inputField.setToolTipText("Enter node command (e.g. .help, .pause, .resume)");

        ActionListener sendAction = e -> {
            String cmd = inputField.getText().trim();
            if (!cmd.isEmpty()) {
                logger.info("Executing command: " + cmd);
                // A parancs feldolgozása külön szálon, hogy ne fagyassza le a UI-t
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
        // Erőforrások felszabadítása
        Logger rootLogger = Logger.getLogger("");
        if (consoleHandler != null) {
            rootLogger.removeHandler(consoleHandler);
            consoleHandler.close();
        }
        if (appearanceListener != null) {
            AppearanceModule.removeAppearanceListener(appearanceListener);
        }
    }

    @Override
    public JComponent getUI() {
        return mainPanel;
    }

    // Belső osztály a logok JTextArea-ba irányításához
    private static class ConsoleHandler extends java.util.logging.Handler {
        private final JTextArea textArea;
        private final int maxLines;

        public ConsoleHandler(JTextArea textArea, int maxLines) {
            this.textArea = textArea;
            this.maxLines = maxLines;
            setFormatter(new java.util.logging.SimpleFormatter());
        }

        @Override
        public void publish(java.util.logging.LogRecord record) {
            if (!isLoggable(record))
                return;
            String msg = getFormatter().format(record);
            SwingUtilities.invokeLater(() -> {
                if (textArea.getLineCount() > maxLines) {
                    try {
                        int endOfFirstLine = textArea.getLineEndOffset(0);
                        textArea.replaceRange("", 0, endOfFirstLine);
                    } catch (BadLocationException ignored) {
                    }
                }
                textArea.append(msg);
                textArea.setCaretPosition(textArea.getDocument().getLength());
            });
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws SecurityException {
        }
    }
}
