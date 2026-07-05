package application.utils.gui;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility to auto-close a popup when the user clicks outside it, the window
 * loses focus or is resized/iconified. Designed to be reusable across the
 * application.
 */
public final class PopupAutoCloser {
    private static final Logger LOG = LoggerFactory.getLogger(PopupAutoCloser.class);

    private final Window owner;
    private final Supplier<Boolean> isOpen;
    private final Runnable closeAction;
    private final Supplier<Rectangle> popupBoundsSupplier;
    private final Component triggerToExclude; // optional

    private final AtomicBoolean started = new AtomicBoolean(false);

    private MouseAdapter mouseListener;

    private ComponentAdapter componentListener;

    private WindowAdapter windowListener;

    private PropertyChangeListener activeWindowListener;

    private AWTEventListener globalMouseWindowListener;

    public PopupAutoCloser(Window owner,
            Supplier<Boolean> isOpen,
            Runnable closeAction,
            Supplier<Rectangle> popupBoundsSupplier,
            Component triggerToExclude) {
        this.owner = Objects.requireNonNull(owner);
        this.isOpen = Objects.requireNonNull(isOpen);
        this.closeAction = Objects.requireNonNull(closeAction);
        this.popupBoundsSupplier = Objects.requireNonNull(popupBoundsSupplier);
        this.triggerToExclude = triggerToExclude;

        // Initialize listeners after final fields have been assigned
        this.mouseListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                try {
                    if (!PopupAutoCloser.this.isOpen.get()) return;
                    Point screenPt;
                    try {
                        screenPt = e.getLocationOnScreen();
                    } catch (Exception ex) {
                        return;
                    }
                    Rectangle popupRect = PopupAutoCloser.this.popupBoundsSupplier.get();
                    if (popupRect != null && popupRect.contains(screenPt)) {
                        return; // click inside popup
                    }
                    if (PopupAutoCloser.this.triggerToExclude != null) {
                        try {
                            Point tp = PopupAutoCloser.this.triggerToExclude.getLocationOnScreen();
                            Rectangle tr = new Rectangle(tp.x, tp.y, PopupAutoCloser.this.triggerToExclude.getWidth(), PopupAutoCloser.this.triggerToExclude.getHeight());
                            if (tr.contains(screenPt)) return;
                        } catch (Exception ex) {
                            // ignore
                        }
                    }
                    invokeClose();
                } catch (Exception ex) {
                    LOG.debug("PopupAutoCloser mousePressed error", ex);
                }
            }
        };

        this.componentListener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (PopupAutoCloser.this.isOpen.get()) invokeClose();
            }
        };

        this.windowListener = new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                if (PopupAutoCloser.this.isOpen.get()) invokeClose();
            }

            @Override
            public void windowIconified(WindowEvent e) {
                if (PopupAutoCloser.this.isOpen.get()) invokeClose();
            }
        };

        this.activeWindowListener = evt -> {
            try {
                if (PopupAutoCloser.this.isOpen.get()) invokeClose();
            } catch (Exception ex) {
                LOG.debug("PopupAutoCloser activeWindow listener error", ex);
            }
        };

        this.globalMouseWindowListener = evt -> {
            if (!(evt instanceof MouseEvent)) return;
            MouseEvent me = (MouseEvent) evt;
            if (me.getID() != MouseEvent.MOUSE_PRESSED) return;
            // Some mouse events originate with null component; delegate to mouseListener logic
            PopupAutoCloser.this.mouseListener.mousePressed(me);
        };
    }

    private void invokeClose() {
        if (EventQueue.isDispatchThread()) {
            try {
                closeAction.run();
            } catch (Exception ex) {
                LOG.debug("PopupAutoCloser closeAction error", ex);
            }
        } else {
            SwingUtilities.invokeLater(() -> {
                try {
                    closeAction.run();
                } catch (Exception ex) {
                    LOG.debug("PopupAutoCloser closeAction error", ex);
                }
            });
        }
    }

    /** Start listening for outside clicks and window events. Safe to call multiple times. */
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(globalMouseWindowListener,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
        } catch (SecurityException ex) {
            LOG.debug("PopupAutoCloser: could not register global AWT listener", ex);
        }

        try {
            owner.addWindowListener(windowListener);
            owner.addComponentListener(componentListener);
        } catch (Exception ex) {
            LOG.debug("PopupAutoCloser: could not add owner listeners", ex);
        }

        try {
            // Attach to layered pane and glass pane if available
            if (owner instanceof RootPaneContainer) {
                JLayeredPane lp = ((RootPaneContainer) owner).getLayeredPane();
                if (lp != null) lp.addMouseListener(mouseListener);
                Component glass = ((RootPaneContainer) owner).getRootPane().getGlassPane();
                if (glass != null) glass.addMouseListener(mouseListener);
            }
        } catch (Exception ex) {
            LOG.debug("PopupAutoCloser: could not attach to layered/glass pane", ex);
        }

        try {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .addPropertyChangeListener("activeWindow", activeWindowListener);
        } catch (Exception ex) {
            LOG.debug("PopupAutoCloser: could not register activeWindow listener", ex);
        }
    }

    /** Stop listening and release resources. Safe to call multiple times. */
    public void stop() {
        if (!started.compareAndSet(true, false)) return;
        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(globalMouseWindowListener);
        } catch (Exception ex) {
            // ignore
        }
        try {
            owner.removeWindowListener(windowListener);
        } catch (Exception ex) {
        }
        try {
            owner.removeComponentListener(componentListener);
        } catch (Exception ex) {
        }
        try {
            if (owner instanceof RootPaneContainer) {
                JLayeredPane lp = ((RootPaneContainer) owner).getLayeredPane();
                if (lp != null) lp.removeMouseListener(mouseListener);
                Component glass = ((RootPaneContainer) owner).getRootPane().getGlassPane();
                if (glass != null) glass.removeMouseListener(mouseListener);
            }
        } catch (Exception ex) {
        }
        try {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removePropertyChangeListener("activeWindow", activeWindowListener);
        } catch (Exception ex) {
        }
    }
}
