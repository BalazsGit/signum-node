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
        // Module initialization, e.g., loading resources, registering listeners
        this.context = context;
    }

    @Override
    public void start() {
        // Module startup, e.g., starting background processes
        // A modul indítása, pl. háttérfolyamatok elindítása
    }

    @Override
    public void stop() {
        // Module shutdown, e.g., releasing resources
        // A modul leállítása, pl. erőforrások felszabadítása
    }

    @Override
    public JComponent getUI() {
        // If the GUI has not been created yet, instantiate SignumGUI.
        // SignumGUI is now a JPanel, so it can be directly embedded.
        if (gui == null) {
            // Retrieve the parentFrame from the container for safe dialog handling
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