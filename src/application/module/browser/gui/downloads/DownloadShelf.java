package application.module.browser.gui.downloads;

import application.module.browser.engine.handler.ActiveDownloadRegistry;
import application.module.browser.model.download.DownloadItem;
import application.module.browser.model.download.DownloadManager;
import application.utils.i18n.I18n;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The download shelf (plan F5, D3 + A9): the bottom bar that slides in when
 * a download starts and lists the recent items — progress while active
 * (D2), open / show-in-folder when done, cancel / retry while not. Ctrl+J
 * (Appendix B) toggles it; the × hides it.
 * <p>
 * Pure view: intents go through the {@link DownloadManager} (state +
 * persistence), the {@link ActiveDownloadRegistry} (CEF cancel hooks, D2)
 * and the {@code retry} consumer (re-opening the URL as a tab, which
 * re-triggers the download through the engine's handler). Rebuilt wholesale
 * on every publish — the item count is tiny (capped at 50).
 * Must be constructed on the EDT.
 */
public final class DownloadShelf extends JPanel {

    private static final Logger logger = LoggerFactory.getLogger(DownloadShelf.class);

    private static final String GLYPH_ACTIVE = "\u2B07";   // ⬇
    private static final String GLYPH_DONE = "\u2713";     // ✓
    private static final String GLYPH_CANCELED = "\u2715"; // ✕
    private static final String GLYPH_FAILED = "\u26A0";   // ⚠
    private static final int SLIDE_DURATION_MS = 180;
    private static final int NAME_MAX_CHARS = 48;

    private final DownloadManager manager;
    private final ActiveDownloadRegistry activeDownloads;
    private final Consumer<String> retry;
    private final JPanel header;
    private final JPanel rows;
    private float slideProgress = 1f;
    private Timer slideTimer;

    public DownloadShelf(DownloadManager manager, ActiveDownloadRegistry activeDownloads,
                         Consumer<String> retry) {
        super(new BorderLayout());
        this.manager = manager;
        this.activeDownloads = activeDownloads;
        this.retry = retry;

        this.header = new JPanel(new BorderLayout());
        JLabel title = new JLabel(I18n.get("browser.downloads.title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setBorder(BorderFactory.createEmptyBorder(6, 10, 0, 0));
        header.add(title, BorderLayout.WEST);
        JButton close = new JButton("\u00D7");
        close.setFocusable(false);
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.setMargin(new Insets(0, 8, 0, 8));
        close.setToolTipText(I18n.get("browser.downloads.close"));
        close.addActionListener(e -> setVisible(false));
        header.add(close, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        this.rows = new JPanel();
        this.rows.setLayout(new GridBagLayout());
        add(rows, BorderLayout.CENTER);

        // The manager's mutations arrive on the EDT (the CEF handlers pump,
        // plan §4.2) — the rebuild below may touch Swing directly.
        manager.addListener(this::onChanged);
        // Restored recent items do not pop the shelf at startup (A9): it
        // appears on the first active download or with Ctrl+J.
        setVisible(false);
    }

    // ------------------------------------------------------------------
    // Manager events
    // ------------------------------------------------------------------

    private void onChanged(List<DownloadItem> items) {
        if (items.isEmpty()) {
            stopSlide();
            setVisible(false);
            rebuild();
            return;
        }
        boolean anyActive = items.stream().anyMatch(i -> !i.getState().isTerminal());
        if (anyActive) {
            showWithSlide();
        }
        rebuild();
    }

    /** Ctrl+J (Appendix B): shows the recent list, hides it again. */
    public void toggle() {
        if (manager.items().isEmpty()) {
            return;
        }
        if (isVisible()) {
            stopSlide();
            setVisible(false);
        } else {
            showWithSlide();
        }
    }

    // ------------------------------------------------------------------
    // Slide-in (A9)
    // ------------------------------------------------------------------

    private void showWithSlide() {
        setVisible(true);
        slideProgress = 0f;
        if (slideTimer == null) {
            slideTimer = new Timer(20, e -> {
                slideProgress = Math.min(1f, slideProgress + 20f / SLIDE_DURATION_MS);
                repaint();
                if (slideProgress >= 1f) {
                    stopSlide();
                }
            });
        }
        slideTimer.setRepeats(true);
        slideTimer.start();
    }

    private void stopSlide() {
        if (slideTimer != null) {
            slideTimer.stop();
        }
        slideProgress = 1f;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int width = getWidth();
        int height = getHeight();
        g.setColor(new Color(0, 0, 0, 40)); // top separator (L&F-neutral)
        g.drawLine(0, 0, width, 1);
        if (slideProgress < 1f) {
            // The panel is opaque: cover the not-yet-revealed bottom so the
            // rows appear to slide up into view.
            int visibleHeight = (int) (height * slideProgress);
            g.setColor(getBackground() == null ? Color.WHITE : getBackground());
            g.fillRect(0, visibleHeight, width, height - visibleHeight);
        }
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    private void rebuild() {
        rows.removeAll();
        GridBagConstraints grid = new GridBagConstraints();
        grid.gridx = 0;
        grid.weightx = 1.0;
        grid.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        for (DownloadItem item : manager.items()) {
            grid.gridy = row++;
            rows.add(row(item), grid);
        }
        rows.revalidate();
        rows.repaint();
    }

    private JPanel row(DownloadItem item) {
        JPanel rowPanel = new JPanel(new BorderLayout(8, 0));
        rowPanel.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));

        JLabel glyph = new JLabel(glyphFor(item.getState()));
        glyph.setToolTipText(item.getUrl());
        rowPanel.add(glyph, BorderLayout.WEST);

        JPanel mid = new JPanel();
        mid.setLayout(new GridBagLayout());
        GridBagConstraints grid = new GridBagConstraints();
        grid.gridx = 0;
        grid.weightx = 1.0;
        grid.fill = GridBagConstraints.HORIZONTAL;
        grid.insets = new Insets(0, 0, 0, 6);
        JLabel name = new JLabel(abbreviate(item.getSuggestedName(), NAME_MAX_CHARS));
        name.setToolTipText(item.getTargetPath());
        grid.gridy = 0;
        mid.add(name, grid);
        JLabel status = new JLabel(statusText(item));
        status.setForeground(status.getForeground().darker());
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));
        grid.gridy = 1;
        mid.add(status, grid);
        rowPanel.add(mid, BorderLayout.CENTER);

        JPanel east = new JPanel(new BorderLayout(6, 0));
        boolean active = !item.getState().isTerminal();
        if (active) {
            JProgressBar bar = new JProgressBar(0, 100);
            bar.setPreferredSize(new java.awt.Dimension(120, 14));
            if (item.getTotalBytes() > 0) {
                bar.setValue(item.getPercentComplete());
            } else {
                bar.setIndeterminate(true);
            }
            east.add(bar, BorderLayout.WEST);
        }
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        switch (item.getState()) {
            case PENDING, IN_PROGRESS -> buttons.add(button(I18n.get("browser.downloads.cancel"),
                    () -> activeDownloads.cancel(item.getId())));
            case CANCELED, FAILED -> buttons.add(button(I18n.get("browser.downloads.retry"),
                    () -> retry.accept(item.getUrl())));
            case COMPLETE -> {
                if (Path.of(item.getTargetPath()).toFile().exists()) {
                    buttons.add(button(I18n.get("browser.downloads.open"),
                            () -> openFile(item)));
                    buttons.add(button(I18n.get("browser.downloads.showInFolder"),
                            () -> revealInFolder(item)));
                }
            }
            default -> {
                // no state-specific action
            }
        }
        JButton remove = button("\u00D7", () -> manager.remove(item.getId()));
        remove.setToolTipText(I18n.get("browser.downloads.remove"));
        buttons.add(remove);
        east.add(buttons, BorderLayout.EAST);
        rowPanel.add(east, BorderLayout.EAST);
        return rowPanel;
    }

    private static String statusText(DownloadItem item) {
        return switch (item.getState()) {
            case PENDING -> I18n.get("browser.downloads.state.pending");
            case IN_PROGRESS -> I18n.get("browser.downloads.progress",
                    item.getPercentComplete(), formatSize(item.getSpeedBps()) + "/s");
            case COMPLETE -> I18n.get("browser.downloads.state.done") + " · "
                    + formatSize(sizeOf(item));
            case CANCELED -> I18n.get("browser.downloads.state.canceled");
            case FAILED -> I18n.get("browser.downloads.state.failed");
        };
    }

    // ------------------------------------------------------------------
    // Actions (D3)
    // ------------------------------------------------------------------

    private static void openFile(DownloadItem item) {
        try {
            File file = Path.of(item.getTargetPath()).toFile();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file);
            } else {
                JOptionPane.showMessageDialog(null, item.getTargetPath(),
                        I18n.get("browser.downloads.title"), JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not open the downloaded file: {}", item.getTargetPath(), e);
        }
    }

    /** "Show in folder" (D3): selects the file in the OS file browser. */
    private static void revealInFolder(DownloadItem item) {
        Path file = Path.of(item.getTargetPath()).toAbsolutePath();
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select," + file).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", file.toString()).start();
            } else if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file.getParent().toFile());
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not reveal the downloaded file: {}", file, e);
        }
    }

    private static long sizeOf(DownloadItem item) {
        try {
            return java.nio.file.Files.size(Path.of(item.getTargetPath()));
        } catch (IOException e) {
            return 0;
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.0f KB", kb);
        }
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    private JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    private static String glyphFor(DownloadItem.State state) {
        return switch (state) {
            case COMPLETE -> GLYPH_DONE;
            case CANCELED -> GLYPH_CANCELED;
            case FAILED -> GLYPH_FAILED;
            default -> GLYPH_ACTIVE;
        };
    }

    private static String abbreviate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "\u2026";
    }
}