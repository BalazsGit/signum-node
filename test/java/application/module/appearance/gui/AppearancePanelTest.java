package application.module.appearance.gui;

import application.module.appearance.gui.laf.FlatLafPrefs;
import application.module.logging.gui.ModuleLoggingProfilePanel;
import application.utils.gui.ConfigurationUtils;
import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import com.formdev.flatlaf.FlatLightLaf;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AppearancePanel} â€” the profile selector row geometry.
 * <p>
 * Verifies that the profile selector combo sits at the toolbar row's RIGHT
 * end (after the last button) with the SAME gap and the SAME combo sizing as
 * the module logging profile panel's profile selector â€” the two profile
 * selectors must look identical in both panels.
 * </p>
 *
 * <h3>EDT handling</h3>
 * Construction and inspection are pumped on the EDT (the same pattern as
 * {@code LoggingPanelTest}/{@code NodeLoggingPanelTest}).
 */
@DisplayName("AppearancePanel profile selector Tests")
class AppearancePanelTest {

    private static void onEdt(Runnable action) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Lays out the (detached) tree recursively: {@code doLayout()} on the
     * component and, recursively, on every child (JDK 25's {@code validate()}
     * does not lay out a detached subtree).
     */
    private static void layoutRecursively(Component c) {
        c.doLayout();
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                layoutRecursively(child);
            }
        }
    }

    /** The x position of {@code c} relative to {@code root} (walking up the parents). */
    private static int horizontalPosition(Container root, Component c) {
        int x = c.getX();
        Component p = c.getParent();
        while (p != null && p != root) {
            x += p.getX();
            p = p.getParent();
        }
        return x;
    }

    private static AppearancePanel newAppearancePanel() {
        final AppearancePanel[] holder = new AppearancePanel[1];
        onEdt(() -> {
            // Defensive icon-font registration (the app registers it at startup
            // in AppearanceModule#init; this covers standalone/test
            // construction â€” the HelpButton in the toolbar needs it).
            IconFontSwing.register(FontAwesome.getIconFont());
            // The FlatLaf prefs node is initialized at app startup; the
            // theme tab (FlatLafPanel) reads it in its constructor.
            FlatLafPrefs.init();
            holder[0] = new AppearancePanel(() -> { }, () -> { });
        });
        return holder[0];
    }

    private static ModuleLoggingProfilePanel newLoggingPanel() {
        final ModuleLoggingProfilePanel[] holder = new ModuleLoggingProfilePanel[1];
        onEdt(() -> holder[0] = new ModuleLoggingProfilePanel(new TestProvider()));
        return holder[0];
    }

    /**
     * The gap between the last toolbar button and the profile combo, which
     * must be the LAST element of its (toolbar) row.
     */
    private static int gapBetweenButtonsAndCombo(Container panel, JComboBox<String> combo) {
        Container row = (Container) combo.getParent();
        int last = row.getComponentCount() - 1;
        assertSame(combo, row.getComponent(last), "the profile combo must be the LAST element of the toolbar row");
        Component previous = row.getComponent(last - 1);
        assertInstanceOf(JButton.class, previous, "the element before the profile combo must be a toolbar button");
        return horizontalPosition(panel, combo) - (horizontalPosition(panel, previous) + previous.getWidth());
    }

    @Test
    @DisplayName("the profile selector sits at the toolbar row's right end, with the SAME gap and sizing as the module logging panel")
    void profileSelectorMatchesLoggingPanelGeometry() {
        AppearancePanel appearance = newAppearancePanel();
        ModuleLoggingProfilePanel logging = newLoggingPanel();
        onEdt(() -> {
            appearance.setSize(900, 600);
            layoutRecursively(appearance);
            logging.enableSearch(); // idempotent: the constructor already enabled it
            logging.setSize(900, 600);
            layoutRecursively(logging);
        });

        JComboBox<String> appearanceCombo = appearance.getProfileComboBox();
        JComboBox<String> loggingCombo = logging.getProfileCombo();
        assertNotNull(appearanceCombo.getSelectedItem(), "the appearance profile list is populated");
        assertNotNull(loggingCombo.getSelectedItem(), "the logging profile list is populated");

        // Both selectors sit at the RIGHT end of their toolbar row, after
        // the last (Help) button â€” and the gap between the buttons and the
        // combo is the SAME shared value in both panels.
        int appearanceGap = gapBetweenButtonsAndCombo(appearance, appearanceCombo);
        int loggingGap = gapBetweenButtonsAndCombo(logging, loggingCombo);
        assertEquals(ConfigurationUtils.PROFILE_TOOLBAR_GAP, appearanceGap,
                "the appearance gap between the buttons and the profile combo must be the shared row gap");
        assertEquals(loggingGap, appearanceGap,
                "the button-to-combo gap must be identical in the logging and appearance panels");

        // Same combo sizing in both panels (preferred and laid-out).
        assertEquals(loggingCombo.getPreferredSize(), appearanceCombo.getPreferredSize(),
                "the profile combos must have identical preferred sizes");
        assertEquals(loggingCombo.getWidth(), appearanceCombo.getWidth(),
                "the profile combos must have the same laid-out width");
        assertEquals(loggingCombo.getHeight(), appearanceCombo.getHeight(),
                "the profile combos must have the same laid-out height");
    }

    @Test
    @DisplayName("the toolbar's help button shares the logging toolbar's help button look (icon rendering and border)")
    void toolbarHelpButtonMatchesLoggingHelpButton() {
        AppearancePanel appearance = newAppearancePanel();
        ModuleLoggingProfilePanel logging = newLoggingPanel();
        onEdt(() -> {
            appearance.setSize(900, 600);
            layoutRecursively(appearance);
            logging.setSize(900, 600);
            layoutRecursively(logging);
        });

        JButton appearanceHelp = findToolbarHelpButton(appearance,
                "Explains appearance profiles");
        JButton loggingHelp = findToolbarHelpButton(logging,
                "Explains logging profiles");
        assertNotNull(appearanceHelp.getIcon(), "the appearance toolbar carries its help icon");
        assertNotNull(loggingHelp.getIcon(), "the logging toolbar carries its help icon");

        // Same border insets: the ? glyph sits at the SAME internal distance
        // from the button edge in both toolbars — with the shared row gap
        // (PROFILE_TOOLBAR_GAP) that makes the visible question-mark-to-
        // selector distance identical in both panels.
        java.awt.Insets appearanceInsets = borderInsets(appearanceHelp);
        java.awt.Insets loggingInsets = borderInsets(loggingHelp);
        assertEquals(loggingInsets, appearanceInsets,
                "the help buttons must have identical border insets so the ? glyph "
                        + "sits at the same distance from the profile selector");

        // The icons must render pixel-for-pixel the same (same glyph color,
        // size and anti-aliasing) — the unified look the two toolbars share.
        java.awt.image.BufferedImage a = renderIcon(appearanceHelp.getIcon());
        java.awt.image.BufferedImage b = renderIcon(loggingHelp.getIcon());
        assertEquals(b.getWidth(), a.getWidth(), "same help icon width");
        assertEquals(b.getHeight(), a.getHeight(), "same help icon height");
        int maxDelta = 0;
        boolean anyOpaque = false;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int alphaA = (pa >> 24) & 0xFF;
                if (alphaA > 0) {
                    anyOpaque = true;
                    maxDelta = Math.max(maxDelta, colorDelta(pa, pb));
                }
            }
        }
        assertTrue(anyOpaque, "the help icons are actually painted");
        assertTrue(maxDelta <= 8,
                "the help icons must render in the same color (max channel delta " + maxDelta + ")");
    }

    /** Recursively finds the profile-toolbar help button (tooltip contains the marker). */
    private static JButton findToolbarHelpButton(Container root, String tooltipMarker) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b
                    && b.getToolTipText() != null
                    && b.getToolTipText().contains(tooltipMarker)) {
                return b;
            }
            if (c instanceof Container container) {
                JButton found = findToolbarHelpButton(container, tooltipMarker);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static java.awt.Insets borderInsets(JButton button) {
        if (button.getBorder() == null) {
            return new java.awt.Insets(0, 0, 0, 0);
        }
        return button.getBorder().getBorderInsets(button);
    }

    /** Renders an icon on a transparent background for pixel comparison. */
    private static java.awt.image.BufferedImage renderIcon(javax.swing.Icon icon) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                icon.getIconWidth(), icon.getIconHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        try {
            icon.paintIcon(new JLabel(), g, 0, 0);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static int colorDelta(int argbA, int argbB) {
        int da = Math.abs(((argbA >> 16) & 0xFF) - ((argbB >> 16) & 0xFF));
        int db = Math.abs(((argbA >> 8) & 0xFF) - ((argbB >> 8) & 0xFF));
        int dc = Math.abs((argbA & 0xFF) - (argbB & 0xFF));
        return Math.max(da, Math.max(db, dc));
    }

    private static final String TARGET_PROFILE = "switch-test-target";

    private static final Path GUI_SETTINGS_PATH = Path.of("settings", "gui-settings.json");

    /** Recursively finds a button with the exact text in the given subtree. */
    private static JButton findButton(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b && text.equals(b.getText())) {
                return b;
            }
            if (c instanceof Container container) {
                JButton found = findButton(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T onEdtGet(java.util.function.Supplier<T> supplier) {
        final Object[] holder = new Object[1];
        onEdt(() -> holder[0] = supplier.get());
        return (T) holder[0];
    }

    @Test
    @DisplayName("switching to another profile with an unsaved theme change loads the target DIRECTLY (no intermediate reload of the current profile)")
    void profileSwitchWithUnsavedThemeChangeLoadsTargetDirectly() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display (modal dialog)");

        // Back up the real settings file and install a fixture: the Default
        // profile (FlatDark theme) + one custom profile with a FlatLight theme.
        byte[] originalSettings = Files.exists(GUI_SETTINGS_PATH) ? Files.readAllBytes(GUI_SETTINGS_PATH) : null;
        try {
            String fixture = """
                    {
                      "lastSelectedLafProfile": "Default",
                      "lookAndFeelProfiles": {
                        "%s": {
                          "theme": "com.formdev.flatlaf.FlatLightLaf",
                          "font": { "family": "Segoe UI", "style": 0, "size": 12 },
                          "consoleFont": { "family": "Consolas", "style": 0, "size": 12 }
                        }
                      }
                    }
                    """.formatted(TARGET_PROFILE);
            Files.writeString(GUI_SETTINGS_PATH, fixture, StandardCharsets.UTF_8);

            // Capture every profile load: performLoadProfileNow() logs
            // "Loading Look and Feel profile: '<name>'" (SLF4J -> JUL). The
            // event sequence is the exact, race-free observable of WHICH
            // profiles were (re)loaded during the switch.
            Logger jul = Logger.getLogger(AppearancePanel.class.getName());
            Level oldLevel = jul.getLevel();
            jul.setLevel(Level.INFO);
            List<String> loadEvents = Collections.synchronizedList(new ArrayList<>());
            Handler capture = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    String msg = record.getMessage();
                    if (msg != null && msg.startsWith("Loading Look and Feel profile")) {
                        loadEvents.add(msg);
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            capture.setLevel(Level.ALL);
            jul.addHandler(capture);

            final AppearancePanel[] holder = new AppearancePanel[1];
            final JFrame[] frameHolder = new JFrame[1];
            onEdt(() -> {
                IconFontSwing.register(FontAwesome.getIconFont());
                FlatLafPrefs.init();
                frameHolder[0] = new JFrame("appearance-switch-test");
                holder[0] = new AppearancePanel(() -> { }, () -> { });
                frameHolder[0].add(holder[0]);
                frameHolder[0].setSize(900, 600);
                frameHolder[0].setVisible(true);
            });
            JComboBox<String> combo = holder[0].getProfileComboBox();
            assertEquals("Default", onEdtGet(combo::getSelectedItem),
                    "the panel starts on the Default profile");
            assertEquals(TARGET_PROFILE, onEdtGet(() -> combo.getItemAt(1)),
                    "the fixture profile is in the combo");

            onEdt(() -> {
                // An UNSAVED theme change (the Default profile is FlatDark).
                try {
                    UIManager.setLookAndFeel(FlatLightLaf.class.getName());
                } catch (Exception e) {
                    throw new AssertionError("Failed to switch to the FlatLight theme", e);
                }
            });

            // Auto-answer the "Unsaved Changes" dialog with "Discard"
            // (the modal dialog pumps the EDT, so this Swing timer fires).
            CountDownLatch discarded = new CountDownLatch(1);
            Timer dialogCloser = new Timer(250, e -> {
                for (Window w : Window.getWindows()) {
                    if (w instanceof JDialog dialog && dialog.isShowing()
                            && "Unsaved Changes".equals(dialog.getTitle())) {
                        JButton discard = findButton(dialog, "Discard");
                        if (discard != null) {
                            discard.doClick();
                            discarded.countDown();
                            ((Timer) e.getSource()).stop();
                            return;
                        }
                    }
                }
            });
            dialogCloser.setRepeats(true);
            dialogCloser.start();

            try {
                // Switch to the target profile â€” blocks until the dialog
                // is answered (Discard).
                onEdt(() -> combo.setSelectedItem(TARGET_PROFILE));
                assertTrue(discarded.await(10, TimeUnit.SECONDS),
                        "the unsaved-changes dialog appeared and was answered with Discard");

                // Wait until the target profile's load has started (its
                // log event fires at the very start of the load).
                long deadline = System.currentTimeMillis() + 15_000;
                while (System.currentTimeMillis() < deadline
                        && loadEvents.stream().noneMatch(m -> m.contains("'" + TARGET_PROFILE + "'"))) {
                    Thread.sleep(100);
                }
                Thread.sleep(300); // let any (incorrect) intermediate load event settle
                List<String> events = List.copyOf(loadEvents);

                assertTrue(events.stream().anyMatch(m -> m.contains("'" + TARGET_PROFILE + "'")),
                        "the selected profile was loaded after discarding: " + events);
                assertFalse(events.stream().anyMatch(m -> m.contains("'Default'")),
                        "the current profile must NOT be reloaded before the switch "
                                + "(direct switch to the target): " + events);

                onEdt(() -> {
                    assertEquals(FlatLightLaf.class.getName(),
                            UIManager.getLookAndFeel().getClass().getName(),
                            "the target profile's theme is active");
                    assertEquals(TARGET_PROFILE, combo.getSelectedItem(),
                            "the combo shows the target profile");
                });
            } finally {
                dialogCloser.stop();
                jul.removeHandler(capture);
                jul.setLevel(oldLevel);
                onEdt(() -> frameHolder[0].dispose());
            }
        } finally {
            if (originalSettings != null) {
                Files.write(GUI_SETTINGS_PATH, originalSettings);
            } else {
                Files.deleteIfExists(GUI_SETTINGS_PATH);
            }
        }
    }

    static final class TestProvider extends ModuleLoggingProvider {
        @Override
        public ModuleLoggingProfile getProfile() {
            return new ModuleLoggingProfile() {
                @Override
                public String getModuleId() {
                    return "test-mod";
                }

                @Override
                public String getDisplayName() {
                    return "Test Module";
                }

                @Override
                public String getDescription() {
                    return "test provider";
                }

                @Override
                public Map<String, String> getDefaults() {
                    return Map.of("test.level", "INFO");
                }
            };
        }
    }
}
