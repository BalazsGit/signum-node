package application.module.node.gui;

import net.miginfocom.swing.MigLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the layout of the DB-check result dialog action rows.
 * Each action row must be: [action component] [help icon] on the <b>same row</b>.
 * Bug guard: old global MigLayout "insets 24, fillx, wrap" placed every
 * component on its own row, causing the help icon to appear below the button.
 *
 * <p>Note: uses plain {@link JButton} with a fixed size (20x20) to simulate
 * a {@link application.utils.gui.HelpButton} without requiring the FontAwesome font.
 */
@DisplayName("NodeProfilePanel DB check dialog – help button position")
class NodeProfilePanelDbCheckDialogTest {

    private static final int SAME_ROW_TOLERANCE_PX = 8;

    /** Creates a stand-in for HelpButton with a fixed 20x20 size (no FontAwesome required). */
    private static JButton helpButtonStub() {
        JButton b = new JButton();
        b.setMargin(new java.awt.Insets(0, 0, 0, 0));
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setPreferredSize(new java.awt.Dimension(20, 20));
        b.setMaximumSize(new java.awt.Dimension(20, 20));
        b.setMinimumSize(new java.awt.Dimension(20, 20));
        return b;
    }

    private static Rectangle[] buildFixedRowLayout() {
        JPanel panel = new JPanel(new MigLayout("insets 24, fillx"));
        JButton actionBtn = new JButton("Run Database Check");
        JButton helpBtn = helpButtonStub();
        panel.add(actionBtn, "gaptop 8");
        panel.add(helpBtn, "gapleft 4, wrap");
        panel.setSize(400, 200);
        panel.doLayout();
        return new Rectangle[]{actionBtn.getBounds(), helpBtn.getBounds()};
    }

    private static Rectangle[] buildBrokenRowLayout() {
        JPanel panel = new JPanel(new MigLayout("insets 24, fillx, wrap"));
        JButton actionBtn = new JButton("Run Database Check");
        JButton helpBtn = helpButtonStub();
        panel.add(actionBtn, "gaptop 8");
        panel.add(helpBtn, "gapleft 4");
        panel.setSize(400, 200);
        panel.doLayout();
        return new Rectangle[]{actionBtn.getBounds(), helpBtn.getBounds()};
    }

    @Test
    @DisplayName("fixed layout: help button is on the same row as the action button")
    void fixedLayout_helpButtonSameRowAsActionButton() {
        Rectangle btn  = buildFixedRowLayout()[0];
        Rectangle help = buildFixedRowLayout()[1];
        int yDelta = Math.abs(help.y - btn.y);
        assertTrue(yDelta <= SAME_ROW_TOLERANCE_PX,
                "help button must be on same row (Y delta=" + yDelta + "px, tolerance=" + SAME_ROW_TOLERANCE_PX + "px)");
    }

    @Test
    @DisplayName("fixed layout: help button is to the right of the action button")
    void fixedLayout_helpButtonToTheRight() {
        Rectangle btn  = buildFixedRowLayout()[0];
        Rectangle help = buildFixedRowLayout()[1];
        assertTrue(help.x > btn.x,
                "help button must be to the right (help.x=" + help.x + ", btn.x=" + btn.x + ")");
    }

    @Test
    @DisplayName("broken layout (global wrap): help button ends up on the next row – regression doc")
    void brokenLayout_helpButtonOnNextRow() {
        Rectangle btn  = buildBrokenRowLayout()[0];
        Rectangle help = buildBrokenRowLayout()[1];
        int yDelta = Math.abs(help.y - btn.y);
        assertTrue(yDelta > SAME_ROW_TOLERANCE_PX,
                "broken layout should place help button on a different row (Y delta=" + yDelta + "px)");
    }

    @Test
    @DisplayName("fixed layout: all three action rows have help buttons beside their action")
    void fixedLayout_allThreeActionRowsHelpBesideAction() {
        JPanel panel = new JPanel(new MigLayout("insets 24, fillx"));
        JButton recheckBtn  = new JButton("Run Database Check");
        JButton recheckHelp = helpButtonStub();
        JButton resolveBtn  = new JButton("Start Auto Resolve");
        JButton resolveHelp = helpButtonStub();
        JCheckBox skipCb    = new JCheckBox("Skip DB Check on Manual Pop-off");
        JButton skipHelp    = helpButtonStub();
        panel.add(recheckBtn,  "gaptop 8");
        panel.add(recheckHelp, "gapleft 4, wrap");
        panel.add(resolveBtn,  "gaptop 8");
        panel.add(resolveHelp, "gapleft 4, wrap");
        panel.add(skipCb,      "gaptop 8");
        panel.add(skipHelp,    "gapleft 4, wrap");
        panel.setSize(400, 300);
        panel.doLayout();
        int tol = SAME_ROW_TOLERANCE_PX;
        assertTrue(Math.abs(recheckHelp.getY() - recheckBtn.getY()) <= tol,
                "recheck help same row (delta=" + Math.abs(recheckHelp.getY() - recheckBtn.getY()) + "px)");
        assertTrue(Math.abs(resolveHelp.getY() - resolveBtn.getY()) <= tol,
                "resolve help same row (delta=" + Math.abs(resolveHelp.getY() - resolveBtn.getY()) + "px)");
        assertTrue(Math.abs(skipHelp.getY() - skipCb.getY()) <= tol,
                "skip help same row (delta=" + Math.abs(skipHelp.getY() - skipCb.getY()) + "px)");
    }
}