package application.utils.gui;

import javax.swing.Icon;
import javax.swing.JTabbedPane;
import java.awt.Component;

/**
 * L&F-agnostic utilities for {@link JTabbedPane}.
 * <p>
 * The standard API has no way to move an existing tab to another position —
 * only {@code removeTabAt()} + {@code addTab()}/{@code insertTab()}, which
 * detach and re-attach the tab's component (firing {@code removeNotify()}/
 * {@code addNotify()} on it). {@link #moveTo(JTabbedPane, int, int)} provides
 * that move as a single, reusable primitive with selection tracking.
 * </p>
 * <p>
 * <b>Note:</b> the move does fire {@code removeNotify()}/{@code addNotify()}
 * on the moved tab's component (unavoidable with the public API). Consumers
 * that own per-tab resources must survive re-attachment (e.g.
 * {@code NodeConsolePanel.addNotify()} re-attaches its log subscriber).
 * </p>
 */
public final class TabUtils {

    private TabUtils() {
        // static utility — no instances
    }

    /**
     * Moves the tab at position {@code from} to the slot {@code to}, preserving
     * title, icon, tooltip, mnemonic and the content component.
     * <p>
     * {@code to} is the target slot expressed in the CURRENT (pre-move) tab
     * order: dropping a tab onto another tab's position takes over that
     * position, shifting the tabs in between.
     * </p>
     * <p>
     * The currently selected tab stays selected — tracked by component identity,
     * so it follows the tab when the selected tab itself is the one moved.
     * </p>
     * <p>
     * Invalid arguments and {@code from == to} are no-ops.
     * </p>
     *
     * @param tabs the tabbed pane (never null)
     * @param from current index of the tab to move (0-based)
     * @param to   target slot of the moved tab in the current ordering (0-based)
     * @throws NullPointerException if {@code tabs} is null
     */
    public static void moveTo(JTabbedPane tabs, int from, int to) {
        if (tabs == null) {
            throw new NullPointerException("tabs must not be null");
        }
        int count = tabs.getTabCount();
        if (from == to || from < 0 || from >= count || to < 0 || to >= count) {
            return; // no-op: already in place or invalid range
        }

        String title = tabs.getTitleAt(from);
        Icon icon = tabs.getIconAt(from);
        String tip = tabs.getToolTipTextAt(from);
        int mnemonic = tabs.getMnemonicAt(from);
        Component comp = tabs.getComponentAt(from);
        Component selected = tabs.getSelectedComponent();

        tabs.removeTabAt(from);
        // "to" is the slot in the PRE-move ordering. Removing the tab at "from"
        // shifts everything after it left by one, so inserting at "to" (valid
        // because "to < count" before the removal) places the moved tab exactly
        // in its intended final position — no index adjustment needed.
        tabs.insertTab(title, icon, comp, tip, to);

        // Keep the previously selected tab selected (identity-based tracking,
        // so a shift of the selection index does not select the wrong tab).
        // NOTE: JTabbedPane.indexOfTabComponent() is unreliable in this JDK build
        // (returns -1 for existing components) — use getComponentAt() identity.
        if (selected != null) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (tabs.getComponentAt(i) == selected) {
                    tabs.setSelectedIndex(i);
                    break;
                }
            }
        }
    }
}