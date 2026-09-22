package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import application.module.node.NodeModule;
import application.module.node.gui.NodePanel;
import application.module.node.gui.NodeProfilePanel;
import application.module.node.profile.NodeProfileRepository;

import java.lang.reflect.Field;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Repro test for the reported "Delete Profile button does nothing" bug in
 * {@link NodeConfigurationPanel}: drives the REAL tab flow (a
 * {@link NodePanel} with a lazily loaded profile tab) and asserts that
 * clicking the Delete toolbar button (1) opens the modal "Delete Profile"
 * dialog, and (2) after confirmation the profile file is deleted and its tab
 * is removed from the {@link NodePanel}.
 */
@DisplayName("NodeConfigurationPanel delete button repro tests")
@SuppressWarnings("unchecked")
class NodeConfigurationPanelDeleteButtonTest {

    private static final String TEST_PROFILE = "delete-repro-test";
    private static final long INIT_TIMEOUT_MS = 120_000;

    @Test
    @DisplayName("clicking Delete Profile after confirmation removes the profile and its tab")
    void deleteProfile_removesProfileAndTab() throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Object[] panelRef = new Object[1];   // NodePanel
        final Object[] ownerRef = new Object[1];   // JFrame
        final Object[] configPanelRef = new Object[1];

        // Start from a clean slate: a leftover file from a crashed previous
        // run would make createProfile throw "already exists".
        java.nio.file.Files.deleteIfExists(profileFile());

        // Create the test profile file in the shared conf tree (file only;
        // the delete chain + cleanup restore the metadata below).
        NodeProfileRepository.createProfile(TEST_PROFILE, new Properties());

        // The delete chain rewrites the profile metadata (profiles.json); keep
        // the original bytes so the test leaves the shared conf tree untouched.
        java.nio.file.Path metaFile = java.nio.file.Path.of("conf", "node", "profiles.json");
        byte[] originalMeta = java.nio.file.Files.exists(metaFile)
                ? java.nio.file.Files.readAllBytes(metaFile)
                : null;

        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    jiconfont.swing.IconFontSwing.register(
                            jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                    JFrame owner = new JFrame("delete-repro-test-owner");
                    // The SAME NodePanel the production app uses:
                    // NodeModule.getUI() is the single UI instance the delete
                    // flow resolves the tab removal against.
                    NodePanel gui = (NodePanel) NodeModule.getInstance().getUI();
                    owner.add(gui);
                    owner.setSize(1000, 700);
                    owner.setVisible(true);
                    panelRef[0] = gui;
                    ownerRef[0] = owner;
                } catch (Throwable t) {
                    error.set(t);
                }
            });
            assertNotNull(panelRef[0], "the NodePanel must be constructable: " + error.get());

            // The async profile loader creates ONE placeholder tab per discovered
            // profile; wait until the test profile's tab exists, then select it so
            // the lazy load starts. (Do NOT call addProfileTab: it would create a
            // DUPLICATE tab for the same profile, confusing the name->index map.)
            final Object[] placeholderSeen = new Object[1];
            long phDeadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < phDeadline && placeholderSeen[0] == null) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        NodePanel gui = (NodePanel) panelRef[0];
                        java.lang.reflect.Field mapField = NodePanel.class
                                .getDeclaredField("profileNameToTabIndex");
                        mapField.setAccessible(true);
                        Object map = mapField.get(gui);
                        if (((java.util.Map<String, Integer>) map).containsKey(TEST_PROFILE)) {
                            placeholderSeen[0] = Boolean.TRUE;
                        }
                    } catch (Exception ignore) {
                        // keep polling
                    }
                });
                Thread.sleep(200);
            }
            assertNotNull(placeholderSeen[0],
                    "the async loader must create a placeholder tab for " + TEST_PROFILE);

            // Select the test profile's tab to trigger the lazy load.
            SwingUtilities.invokeAndWait(() -> {
                NodePanel gui = (NodePanel) panelRef[0];
                try {
                    java.lang.reflect.Field tbField = NodePanel.class
                            .getDeclaredField("profileTabbedPane");
                    tbField.setAccessible(true);
                    javax.swing.JTabbedPane tb = (javax.swing.JTabbedPane) tbField.get(gui);
                    for (int i = 0; i < tb.getTabCount(); i++) {
                        if (TEST_PROFILE.equals(tb.getTitleAt(i))) {
                            tb.setSelectedIndex(i);
                            break;
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Wait until the test profile's tab has been lazily replaced by a
            // loaded NodeProfilePanel whose configuration panel finished its
            // async init (allPropertyRows non-empty).
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && configPanelRef[0] == null) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        NodeProfilePanel profilePanel = ((NodePanel) panelRef[0]).getProfilePanel(TEST_PROFILE);
                        if (profilePanel != null) {
                            Object configPanel = readDeclaredField(profilePanel, "configurationPanel");
                            if (configPanel instanceof NodeConfigurationPanel npc) {
                                Object rows = readDeclaredField(npc, "allPropertyRows");
                                if (rows instanceof java.util.List<?> list && !list.isEmpty()) {
                                    configPanelRef[0] = npc;
                                }
                            }
                        }
                    } catch (Exception ignore) {
                        // still loading
                    }
                });
                Thread.sleep(200);
            }
            if (configPanelRef[0] == null) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        NodePanel gui = (NodePanel) panelRef[0];
                        java.lang.reflect.Field tbField = NodePanel.class
                                .getDeclaredField("profileTabbedPane");
                        tbField.setAccessible(true);
                        javax.swing.JTabbedPane tb = (javax.swing.JTabbedPane) tbField.get(gui);
                        System.out.println("[DELETEREPRO] tabCount=" + tb.getTabCount());
                        for (int i = 0; i < tb.getTabCount(); i++) {
                            System.out.println("[DELETEREPRO]   tab " + i + ": " + tb.getTitleAt(i)
                                    + " selected=" + (i == tb.getSelectedIndex())
                                    + " component=" + tb.getComponentAt(i).getClass().getSimpleName());
                        }
                    } catch (Exception ignore) {
                        // diagnostics only
                    }
                });
            }
            assertNotNull(configPanelRef[0],
                    "the profile tab must load its configuration panel for " + TEST_PROFILE);

            // Resolve the icon-only Delete toolbar button on the config panel.
            final Object[] btnRef = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    btnRef[0] = readDeclaredField(configPanelRef[0], "deleteProfileBtn");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(btnRef[0] instanceof JButton, "deleteProfileBtn must be a JButton");
            System.out.println("[DELETEREPRO] deleteProfileBtn.isEnabled() = "
                    + ((JButton) btnRef[0]).isEnabled());

            // Click the toolbar button. A watcher thread must catch the modal
            // "Delete Profile" dialog (it blocks the EDT) and confirm the
            // deletion, then dismiss the success/error message box.
            final AtomicReference<JDialog> dialogSeen = new AtomicReference<>();
            Thread watcher = new Thread(() -> {
                JDialog d = waitForWindow("Delete Profile", 30_000);
                if (d == null) {
                    return;
                }
                dialogSeen.set(d);
                JButton deleteBtn = findButton(d, "Delete");
                if (deleteBtn != null) {
                    clickOnEdt(deleteBtn); // confirm so the delete chain runs
                } else {
                    d.dispose();
                }
                // After the background chain finishes, the success message box
                // appears; dismiss it so the EDT runnable can complete.
                JDialog success = waitForWindow("Success", 30_000);
                if (success != null) {
                    JButton ok = findButton(success, "OK");
                    if (ok != null) {
                        clickOnEdt(ok);
                    } else {
                        SwingUtilities.invokeLater(success::dispose);
                    }
                }
                // If the delete chain failed, an error box is shown instead —
                // capture its text for the assertion and dismiss it.
                JDialog errBox = waitForWindow("Error", 10_000);
                if (errBox != null) {
                    error.set(new IllegalStateException("delete chain showed an error dialog: "
                            + dialogTextOf(errBox)));
                    JButton ok = findButton(errBox, "OK");
                    if (ok != null) {
                        clickOnEdt(ok);
                    } else {
                        SwingUtilities.invokeLater(errBox::dispose);
                    }
                }
            });
            watcher.start();
            // Post the click to the EDT (NOT invokeAndWait: the delete action
            // shows a MODAL dialog that blocks the EDT, and runProfileAction
            // also marshals onto the EDT — an invokeAndWait here would deadlock
            // the EDT on itself). Wait for the click to be actually dispatched.
            final java.util.concurrent.CountDownLatch clicked =
                    new java.util.concurrent.CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                // NOTE: doClick() blocks until the modal dialog closes, so the
                // latch is released BEFORE the click (it only signals that the
                // click event has been posted to the EDT queue).
                clicked.countDown();
                ((JButton) btnRef[0]).doClick();
            });
            if (!clicked.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("the delete click was not dispatched to the EDT");
            }
            watcher.join(90_000);
            System.out.println("[DELETEREPRO] dialogSeen = " + dialogSeen.get());
            assertTrue(dialogSeen.get() != null,
                    "clicking Delete Profile must open the Delete Profile dialog");
            assertFalse(error.get() != null, "the delete chain must not report an error: "
                    + (error.get() != null ? error.get().getMessage() : ""));

            // The confirmed delete must have run: the profile file is gone and
            // the tab is removed from the NodePanel.
            assertFalse(java.nio.file.Files.exists(profileFile()),
                    "the profile properties file must be deleted: " + profileFile());
            final Object[] tabRemoved = new Object[1];
            long tabDeadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < tabDeadline && tabRemoved[0] == null) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        NodePanel gui = (NodePanel) panelRef[0];
                        if (gui.getProfilePanel(TEST_PROFILE) == null) {
                            tabRemoved[0] = Boolean.TRUE;
                        }
                    } catch (Exception ignore) {
                        // keep polling
                    }
                });
                Thread.sleep(200);
            }
            assertTrue(Boolean.TRUE.equals(tabRemoved[0]),
                    "the profile tab must be removed from the NodePanel after deletion");
        } finally {
            cleanupWindows(ownerRef);
            // Clean up test artifacts: restore the profile metadata and remove
            // the profile file if the test failed before the chain deleted it.
            if (originalMeta != null) {
                java.nio.file.Files.write(metaFile, originalMeta);
            }
            java.nio.file.Files.deleteIfExists(profileFile());
        }
    }

    private static java.nio.file.Path profileFile() {
        return java.nio.file.Path.of("conf", "node", "profiles", TEST_PROFILE + ".properties");
    }

    /** Disposes all leftover dialogs + the owner frame on the EDT. */
    private static void cleanupWindows(Object[] ownerRef) {
        SwingUtilities.invokeLater(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof JDialog jd) {
                    jd.dispose();
                }
            }
            if (ownerRef[0] instanceof JFrame f) {
                f.dispose();
            }
        });
        try {
            // Give the EDT a moment to process the disposals before finishing.
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Posts a button click to the EDT (safe while a modal dialog pumps events). */
    private static void clickOnEdt(JButton b) {
        SwingUtilities.invokeLater(b::doClick);
    }

    /** Polls (off-EDT) for a dialog with the given title; returns null on timeout. */
    private static JDialog waitForWindow(String title, long timeoutMs) {
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until) {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof JDialog jd && title.equals(jd.getTitle())) {
                    return jd;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    private static String dialogTextOf(JDialog d) {
        StringBuilder sb = new StringBuilder();
        collectText(d, sb);
        return sb.toString();
    }

    private static void collectText(java.awt.Container c, StringBuilder sb) {
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof javax.swing.JLabel label && label.getText() != null
                    && !label.getText().isBlank()) {
                sb.append(label.getText()).append(' ');
            }
            if (comp instanceof java.awt.Container cc) {
                collectText(cc, sb);
            }
        }
    }

    private static JButton findButton(java.awt.Container c, String text) {
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof JButton b && text.equals(b.getText())) {
                return b;
            }
            if (comp instanceof java.awt.Container cc) {
                JButton r = findButton(cc, text);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static Object readDeclaredField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new AssertionError("field not found: " + name);
    }
}

