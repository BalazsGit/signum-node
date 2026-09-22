package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Repro test for the reported "Save & Apply button does nothing" bug in
 * {@link NodeConfigurationPanel}: drives the real button click and asserts
 * that (1) the button becomes enabled after an edit and (2) clicking it opens
 * the modal "Save Configuration" dialog.
 */
@DisplayName("NodeConfigurationPanel save button repro tests")
@SuppressWarnings("unchecked")
class NodeConfigurationPanelSaveButtonTest {

    private static final long INIT_TIMEOUT_MS = 60_000;

    @Test
    @DisplayName("clicking Save & Apply after an edit opens the save dialog")
    void saveAndApply_opensDialog() throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Object[] panelRef = new Object[1];
        final Object[] ownerRef = new Object[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("save-button-test-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null,
                        null, "save-button-repro-test");
                owner.add(panel);
                owner.setSize(900, 700);
                owner.setVisible(true);
                panelRef[0] = panel;
                ownerRef[0] = owner;
            } catch (Throwable t) {
                error.set(t);
            }
        });
        assertNotNull(panelRef[0], "the configuration panel must be constructable: " + error.get());

        // The apply step rewrites the profile metadata (profiles.json); keep the
        // original bytes so the test leaves the shared conf tree untouched.
        java.nio.file.Path metaFile = java.nio.file.Path.of("conf", "node", "profiles.json");
        byte[] originalMeta = java.nio.file.Files.exists(metaFile)
                ? java.nio.file.Files.readAllBytes(metaFile)
                : null;

        try {
            Object[] ready = new Object[1];
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && ready[0] == null) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        Object rows = readDeclaredField(panelRef[0], "allPropertyRows");
                        if (rows instanceof List<?> list && !list.isEmpty()) {
                            ready[0] = true;
                        }
                    } catch (Exception ignore) {
                    }
                });
                Thread.sleep(100);
            }
            assertNotNull(ready[0], "the panel UI must finish initializing");

            // Pick a plain text-field row and edit it.
            final Object[] editInfo = new Object[2]; // [0]=row, [1]=JTextField
            final AtomicReference<String> newValue = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    List<Object> rows = (List<Object>) readDeclaredField(panelRef[0], "allPropertyRows");
                    for (Object row : rows) {
                        Object input = readField(row, "input");
                        if (input instanceof JTextField tf) {
                            editInfo[0] = row;
                            editInfo[1] = tf;
                            tf.requestFocusInWindow();
                            // Always produce a non-empty, non-default value so the
                            // change is picked up by getPropertiesFromUI() (which
                            // stores only non-default values). A timestamp keeps
                            // the value different on every run (the previous run
                            // persists it to the profile file on disk).
                            newValue.set("repro-value" + System.nanoTime() + " repro-suffix");
                            tf.setText(newValue.get());
                            break;
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertNotNull(editInfo[1], "a text field row must exist to edit");

            // Let the document listeners (invokeLater) run, then check the button state.
            Thread.sleep(500);
            final Object[] btnState = new Object[2]; // [0]=JButton, [1]=enabled
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Object btn = readDeclaredField(panelRef[0], "saveApplyBtn");
                    btnState[0] = btn;
                    btnState[1] = ((JButton) btn).isEnabled();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertNotNull(btnState[1], "saveApplyBtn must exist");
            System.out.println("[SAVEREPRO] saveApplyBtn.isEnabled() after edit = " + btnState[1]);
            assertTrue((Boolean) btnState[1], "Save & Apply must be enabled after a dirty edit");

            // Click the toolbar button. A watcher thread must catch the modal
            // "Save Configuration" dialog (it blocks the EDT), confirm the
            // save, and afterwards answer the "Apply Changes" restart question
            // with No so the flow terminates cleanly.
            final AtomicReference<JDialog> dialogSeen = new AtomicReference<>();
            Thread watcher = new Thread(() -> {
                JDialog d = waitForWindow("Save Configuration", 20_000);
                if (d == null) {
                    return;
                }
                dialogSeen.set(d);
                JButton saveBtn = findButton(d, "Save");
                if (saveBtn != null) {
                    clickOnEdt(saveBtn); // confirm so the flow continues
                } else {
                    d.dispose();
                }
                // The save-success + apply-confirmation dialogs follow on the
                // EDT; answer "No" to the restart question (restartAction is
                // null in this test) and dismiss any remaining message boxes.
                JDialog confirm = waitForWindow("Apply Changes", 20_000);
                if (confirm != null) {
                    JButton noBtn = findButton(confirm, "No");
                    if (noBtn != null) {
                        clickOnEdt(noBtn);
                    } else {
                        confirm.dispose();
                    }
                }
                // Robustly dismiss the success (and any leftover) message boxes:
                // keep disposing "Success"/"Error" dialogs until none remain, so
                // the EDT is guaranteed to be released even if a box lingers.
                for (int i = 0; i < 50; i++) {
                    JDialog success = waitForWindow("Success", 2_000);
                    if (success != null) {
                        SwingUtilities.invokeLater(success::dispose);
                        continue;
                    }
                    JDialog err = waitForWindow("Error", 2_000);
                    if (err != null) {
                        JButton ok = findButton(err, "OK");
                        if (ok != null) {
                            clickOnEdt(ok);
                        } else {
                            SwingUtilities.invokeLater(err::dispose);
                        }
                        continue;
                    }
                    break;
                }
            });
            watcher.start();
            // Post the click to the EDT (NOT invokeAndWait: the save action shows
            // MODAL dialogs that block the EDT — an invokeAndWait here would block
            // the main test thread until those dialogs close, which the watcher
            // drives, risking a deadlock). The latch only confirms the click was
            // posted (it is released before doClick, which blocks until the modal
            // flow completes).
            final java.util.concurrent.CountDownLatch clicked =
                    new java.util.concurrent.CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                clicked.countDown();
                ((JButton) btnState[0]).doClick();
            });
            if (!clicked.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("the save click was not dispatched to the EDT");
            }
            watcher.join(25_000);
            System.out.println("[SAVEREPRO] dialogSeen = " + dialogSeen.get());
            assertTrue(dialogSeen.get() != null,
                    "clicking Save & Apply must open the Save Configuration dialog");

            // The confirmed save must have reached disk: the edited value must
            // be present in the profile's .properties file.
            String propKey = (String) propName(editInfo[0]);
            java.nio.file.Path savedFile = java.nio.file.Path.of("conf", "node", "profiles",
                    "save-button-repro-test.properties");
            assertTrue(java.nio.file.Files.exists(savedFile),
                    "the profile properties file must exist after saving: " + savedFile);
            boolean found = java.nio.file.Files.readAllLines(savedFile).stream()
                    .anyMatch(line -> line.startsWith(propKey + "=")
                            && line.endsWith(newValue.get()));
            assertTrue(found, "the saved file must contain the edited value of " + propKey
                    + " (expected suffix: " + newValue.get() + ")");
        } finally {
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
            // Give the EDT a moment to process the disposals before finishing.
            Thread.sleep(300);

            // Clean up test artifacts: restore the profile metadata and remove
            // the profile file created by this test.
            if (originalMeta != null) {
                java.nio.file.Files.write(metaFile, originalMeta);
            }
            java.nio.file.Files.deleteIfExists(
                    java.nio.file.Path.of("conf", "node", "profiles", "save-button-repro-test.properties"));
        }
    }

    /** Returns the property key object (Prop#getName() gives the .properties key). */
    private static Object propName(Object row) throws Exception {
        Object prop = readField(row, "prop");
        java.lang.reflect.Method m = prop.getClass().getDeclaredMethod("getName");
        m.setAccessible(true);
        return m.invoke(prop);
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
                if (w instanceof javax.swing.JDialog jd && title.equals(jd.getTitle())) {
                    return (JDialog) w;
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

    private static Object readField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
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
