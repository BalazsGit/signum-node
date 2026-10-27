package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the Clone Configuration toolbar action of
 * {@link NodeConfigurationPanel}: clicking the clone button must open the
 * modal "Clone Configuration" dialog (a reported bug was that no dialog
 * appeared at all).
 * <p>
 * The panel builds its UI asynchronously after the constructor returns, so
 * the test polls for the (private) clone button on the EDT. A repeating
 * Swing timer auto-cancels the modal dialog as soon as it is showing — the
 * modal event pump keeps firing Swing timers, so {@code show()} returns
 * without any user input.
 * </p>
 */
@DisplayName("NodeConfigurationPanel clone action tests")
class NodeConfigurationPanelCloneActionTest {

    private static final long INIT_TIMEOUT_MS = 60_000;

    @Test
    @DisplayName("clicking the clone button opens the 'Clone Configuration' dialog")
    void cloneButton_opensCloneDialog() throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicReference<String> dialogTitle = new AtomicReference<>();
        final CountDownLatch dialogAppeared = new CountDownLatch(1);
        final Object[] panelRef = new Object[1];
        final List<Throwable> loggedErrors = new ArrayList<>();
        // The panel swallows async-init exceptions into its SLF4J logger; capture
        // them through a Log4j2 appender on the ROOT of the context that the
        // SLF4J binding (log4j-slf4j2-impl) resolved to in this JVM — the single
        // provider context every SLF4J logger in this process logs through,
        // independent of any ContextFactory swap a test may have performed — so
        // a failed initUI surfaces as a diagnostic, not a timeout.
        final LoggerContext ctx = slf4jBindingContext();
        final LoggerConfig rootConfig = ctx.getConfiguration().getRootLogger();
        final AbstractAppender captureHandler = new AbstractAppender("clone-action-capture", null,
                PatternLayout.createDefaultLayout(), false) {
            @Override
            public void append(LogEvent event) {
                if (NodeConfigurationPanel.class.getName().equals(event.getLoggerName())
                        && event.getThrown() != null) {
                    loggedErrors.add(event.getThrown());
                }
            }
        };
        captureHandler.start();
        rootConfig.addAppender(captureHandler, Level.ERROR, null);
        ctx.updateLoggers();
        try {
            clickAndExpectCloneDialog(error, dialogTitle, dialogAppeared, panelRef, loggedErrors);
        } finally {
            rootConfig.removeAppender("clone-action-capture");
            captureHandler.stop();
        }
    }

    private void clickAndExpectCloneDialog(AtomicReference<Throwable> error,
            AtomicReference<String> dialogTitle, CountDownLatch dialogAppeared, Object[] panelRef,
            List<Throwable> loggedErrors) throws Exception {
        // The application registers the icon font at startup (AppearanceModule#init)
        // and defensively in the console/toolbar panels; do the same here so
        // IconFontSwing.buildIcon(FontAwesome...) resolves in the test.
        IconFontSwing.register(FontAwesome.getIconFont());

        SwingUtilities.invokeAndWait(() -> {
            try {
                JFrame owner = new JFrame("clone-test-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null,
                        null, "adoption-test-0");
                owner.add(panel);
                owner.setSize(900, 700);
                owner.setVisible(true);
                panelRef[0] = panel;
                // Auto-cancel the modal dialog as soon as any JDialog appears.
                Timer closer = new Timer(400, e -> {
                    Timer timer = (Timer) e.getSource();
                    for (Window w : Window.getWindows()) {
                        if (w instanceof JDialog dialog && dialog.isShowing()) {
                            String title = dialog.getTitle();
                            // Surface a JOptionPane message (e.g. an error
                            // dialog) in the captured title for diagnostics.
                            for (Component child : dialog.getContentPane().getComponents()) {
                                if (child instanceof JOptionPane opt
                                        && opt.getMessage() instanceof String text) {
                                    title = title + " | " + text;
                                    break;
                                }
                            }
                            dialogTitle.set(title);
                            dialog.dispose();
                            timer.stop();
                            dialogAppeared.countDown();
                            return;
                        }
                    }
                });
                closer.setRepeats(true);
                closer.start();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        assertNotNull(panelRef[0], "the configuration panel must be constructable");

        // Wait for the async UI construction (initUI on the EDT) to finish.
        long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
        final Object[] cloneBtnRef = new Object[1];
        while (System.currentTimeMillis() < deadline && cloneBtnRef[0] == null) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Object btn = readDeclaredField(panelRef[0], "cloneProfileBtn");
                    Object rows = readDeclaredField(panelRef[0], "allPropertyRows");
                    if (btn instanceof JButton button
                            && rows instanceof List<?> list && !list.isEmpty()) {
                        cloneBtnRef[0] = button;
                    }
                } catch (Exception ignore) {
                    // fields not ready yet — keep polling
                }
            });
            if (cloneBtnRef[0] == null) {
                Thread.sleep(100);
            }
        }
        JButton cloneBtn = (JButton) cloneBtnRef[0];
        if (cloneBtn == null) {
            AssertionError failure = new AssertionError(
                    "the clone toolbar button must exist after the async UI init (initUI must not have failed)");
            for (Throwable t : loggedErrors) {
                failure.initCause(t);
                t.printStackTrace(System.err);
            }
            throw failure;
        }

        JButton newProfileBtn = (JButton) readDeclaredField(panelRef[0], "newProfileBtn");
        JButton copyBtn = (JButton) readDeclaredField(panelRef[0], "copyProfileDataBtn");
        JButton renameBtn = (JButton) readDeclaredField(panelRef[0], "renameProfileBtn");
        assertTrue(newProfileBtn.getParent() == cloneBtn.getParent()
                        && cloneBtn.getParent() == copyBtn.getParent()
                        && copyBtn.getParent() == renameBtn.getParent(),
                "the first four profile actions must share the toolbar row");
        assertTrue(componentIndex(newProfileBtn) < componentIndex(cloneBtn)
                        && componentIndex(cloneBtn) < componentIndex(copyBtn)
                        && componentIndex(copyBtn) < componentIndex(renameBtn),
                "the profile toolbar order must be New, Clone, Copy, Rename");
        assertTrue(newProfileBtn.getToolTipText().contains("New Profile"));
        assertTrue(cloneBtn.getToolTipText().contains("Clone Configuration to New Profile"));
        assertTrue(copyBtn.getToolTipText().contains("Copy Existing Configuration to This Profile"));
        assertTrue(renameBtn.getToolTipText().contains("Rename Profile"));

        // Fire the button's action listener exactly like a user click.
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (java.awt.event.ActionListener l : cloneBtn.getActionListeners()) {
                    l.actionPerformed(new ActionEvent(cloneBtn, ActionEvent.ACTION_PERFORMED, ""));
                }
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null) {
            AssertionError failure = new AssertionError(
                    "the clone action threw before opening the dialog", error.get());
            for (Throwable t : loggedErrors) {
                failure.addSuppressed(t);
            }
            throw failure;
        }

        assertTrue(dialogAppeared.await(20, TimeUnit.SECONDS),
                "the 'Clone Configuration' dialog must open after the button click");
        assertEquals("Clone Configuration", dialogTitle.get());
    }

    /**
     * The Log4j2 context the SLF4J binding (log4j-slf4j2-impl) resolved to in
     * this JVM. 2.26.1 keeps it behind the protected
     * {@code Log4jLoggerFactory.getContext()}, so it is read through reflection
     * from the provider's logger factory (the same context every SLF4J logger
     * in this process logs through, regardless of any ContextFactory swap a
     * test may have performed).
     */
    private static LoggerContext slf4jBindingContext() {
        try {
            Object factory = org.slf4j.LoggerFactory.getILoggerFactory();
            java.lang.reflect.Method m = factory.getClass().getDeclaredMethod("getContext");
            m.setAccessible(true);
            return (LoggerContext) m.invoke(factory);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "the SLF4J provider must be log4j-slf4j2-impl (protected getContext) to capture events", e);
        }
    }

    private static Object readDeclaredField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static int componentIndex(Component component) {
        java.awt.Container parent = component.getParent();
        for (int i = 0; i < parent.getComponentCount(); i++) {
            if (parent.getComponent(i) == component) {
                return i;
            }
        }
        throw new AssertionError("component is not present in its parent");
    }
}
