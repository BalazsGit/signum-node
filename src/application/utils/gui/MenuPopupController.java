package application.utils.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Objects;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller that encapsulates a popup/menu component, animation and
 * auto-close behavior. Use `show(content, trigger)` / `hide()` / `toggle(...)`.
 */
public final class MenuPopupController {
    private static final Logger LOG = LoggerFactory.getLogger(MenuPopupController.class);

    private final Window owner;
    private JPanel wrapper;
    private PopupAutoCloser autoCloser;
    private Timer animator;
    private boolean open = false;

    public MenuPopupController(Window owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    public Window getOwner() { return owner; }

    public boolean isOpen() { return open; }

    public void toggle(JComponent content, Component trigger) {
        if (isOpen()) hide(); else show(content, trigger);
    }

    public void show(JComponent content, Component trigger) {
        if (isOpen()) return;
        if (!(owner instanceof RootPaneContainer)) return;
        try {
            JLayeredPane lp = ((RootPaneContainer) owner).getLayeredPane();
            int menuWidth = Math.max(250, content.getPreferredSize().width);

            Point p = trigger.getLocationOnScreen();
            SwingUtilities.convertPointFromScreen(p, lp);
            int x = p.x + trigger.getWidth() - menuWidth;
            int y = p.y + trigger.getHeight();

            wrapper = new JPanel(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.setBounds(x, y, menuWidth, 0);
            wrapper.add(content, BorderLayout.CENTER);
            lp.add(wrapper, JLayeredPane.POPUP_LAYER);

            // start auto-closer
            autoCloser = new PopupAutoCloser(owner,
                    () -> Boolean.valueOf(open),
                    () -> SwingUtilities.invokeLater(this::hide),
                    () -> {
                        try {
                            Point onScreen = wrapper.getLocationOnScreen();
                            Dimension d = wrapper.getSize();
                            return new Rectangle(onScreen.x, onScreen.y, d.width, d.height);
                        } catch (Exception ex) {
                            return new Rectangle(0,0,0,0);
                        }
                    },
                    trigger);
            autoCloser.start();

            final int targetHeight = content.getPreferredSize().height;
            animator = new Timer(10, null);
            final long start = System.currentTimeMillis();
            final int duration = 250;
            animator.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - start;
                    float progress = Math.min(1.0f, (float) elapsed / duration);
                    progress = 1.0f - (float) Math.pow(1.0f - progress, 3);
                    int h = (int) (targetHeight * progress);
                    wrapper.setSize(menuWidth, h);
                    wrapper.revalidate();
                    wrapper.repaint();
                    if (progress >= 1.0f) {
                        ((Timer) e.getSource()).stop();
                    }
                }
            });
            animator.start();
            open = true;
        } catch (Exception ex) {
            LOG.debug("MenuPopupController: show error", ex);
        }
    }

    public void hide() {
        if (!isOpen() || wrapper == null) return;
        try {
            final int startHeight = wrapper.getHeight();
            final int menuWidth = wrapper.getWidth();
            final long start = System.currentTimeMillis();
            final int duration = 250;
            Timer t = new Timer(10, null);
            t.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - start;
                    float progress = Math.min(1.0f, (float) elapsed / duration);
                    progress = 1.0f - (float) Math.pow(1.0f - progress, 3);
                    int h = (int) (startHeight * (1.0f - progress));
                    wrapper.setSize(menuWidth, h);
                    wrapper.revalidate();
                    wrapper.repaint();
                    if (progress >= 1.0f) {
                        ((Timer) e.getSource()).stop();
                        try {
                            wrapper.removeAll();
                            if (owner instanceof RootPaneContainer) {
                                JLayeredPane lp = ((RootPaneContainer) owner).getLayeredPane();
                                lp.remove(wrapper);
                                lp.repaint();
                            }
                        } catch (Exception ex) {
                            LOG.debug("MenuPopupController: hide cleanup error", ex);
                        } finally {
                            if (autoCloser != null) {
                                try { autoCloser.stop(); } catch (Exception ignore) {}
                                autoCloser = null;
                            }
                            wrapper = null;
                            open = false;
                        }
                    }
                }
            });
            t.start();
        } catch (Exception ex) {
            LOG.debug("MenuPopupController: hide error", ex);
            // best-effort cleanup
            try {
                if (autoCloser != null) autoCloser.stop();
            } catch (Exception ignore) {}
            try {
                if (owner instanceof RootPaneContainer && wrapper != null) {
                    JLayeredPane lp = ((RootPaneContainer) owner).getLayeredPane();
                    lp.remove(wrapper);
                    lp.repaint();
                }
            } catch (Exception ignore) {}
            wrapper = null;
            open = false;
        }
    }
}
