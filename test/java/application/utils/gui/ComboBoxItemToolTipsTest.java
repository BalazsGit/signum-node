package application.utils.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;

import javax.swing.JList;

import org.junit.jupiter.api.Test;

class ComboBoxItemToolTipsTest {

    @Test
    void install_showsTheHoveredItemsTooltip() {
        JList<String> list = new JList<>(new String[] {"saved", "unsaved"});
        list.setFixedCellHeight(24);
        list.setFixedCellWidth(100);
        list.setSize(100, 48);
        ComboBoxItemToolTips.install(list, item -> "Status: " + item);

        MouseEvent hoverSecondItem = new MouseEvent(list, MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, 10, 30, 0, false);
        for (MouseMotionListener listener : list.getMouseMotionListeners()) {
            listener.mouseMoved(hoverSecondItem);
        }

        assertEquals("Status: unsaved", list.getToolTipText(hoverSecondItem));
    }
}
