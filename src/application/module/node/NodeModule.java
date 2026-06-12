package application.module.node;

import application.api.Module;
import application.api.ModuleContext;
import application.module.node.gui.SignumGUI;
import application.module.node.props.Props;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * A Signum Node modul implementációja az alkalmazás keretrendszeréhez.
 */
public class NodeModule implements Module {

    public static final String ID = "node";
    public static final String DISPLAY_NAME = "Node";

    private ModuleContext context;
    private SignumGUI gui;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public void init(ModuleContext context) {
        this.context = context;
        // A modul inicializálása, pl. erőforrások betöltése, listenerek regisztrálása
    }

    @Override
    public void start() {
        // A modul indítása, pl. háttérfolyamatok elindítása
    }

    @Override
    public void stop() {
        // A modul leállítása, pl. erőforrások felszabadítása
    }

    @Override
    public JComponent getUI() {
        // Ha még nincs létrehozva a GUI, példányosítjuk a SignumGUI-t.
        // A SignumGUI most már JPanel, így közvetlenül beágyazható.
        if (gui == null) {
            // A parentFrame lekérése a konténerből a biztonságos dialóguskezeléshez
            JFrame parentFrame = null;
            for (java.awt.Frame f : java.awt.Frame.getFrames()) {
                if (f instanceof JFrame) {
                    parentFrame = (JFrame) f;
                    if (f.isVisible())
                        break;
                }
            }

            gui = new SignumGUI(
                    parentFrame,
                    "Signum Node",
                    Props.ICON_LOCATION.getDefaultValue(),
                    Signum.VERSION.toString(),
                    new String[0] // Itt adhatók át a CLI argumentumok, ha szükséges
            );
        }
        return gui;
    }
}