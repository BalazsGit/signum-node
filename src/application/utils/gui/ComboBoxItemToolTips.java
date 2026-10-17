package application.utils.gui;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.function.Function;

import javax.swing.JList;

/**
 * Supplies renderer-specific tooltips for combo-box popup items. Swing asks
 * the popup list, rather than its renderer component, for tooltip text.
 */
public final class ComboBoxItemToolTips {

    private static final String INSTALLED_PROPERTY = ComboBoxItemToolTips.class.getName() + ".installed";

    private ComboBoxItemToolTips() {
    }

    public static void install(JList<?> list, Function<Object, String> tooltipForItem) {
        if (list == null || Boolean.TRUE.equals(list.getClientProperty(INSTALLED_PROPERTY))) {
            return;
        }
        list.putClientProperty(INSTALLED_PROPERTY, Boolean.TRUE);
        list.setToolTipText("");
        list.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                Rectangle bounds = index < 0 ? null : list.getCellBounds(index, index);
                if (bounds == null || !bounds.contains(event.getPoint())) {
                    list.setToolTipText(null);
                    return;
                }
                list.setToolTipText(tooltipForItem.apply(list.getModel().getElementAt(index)));
            }
        });
    }
}
