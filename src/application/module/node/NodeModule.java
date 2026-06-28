package application.module.node;

import application.api.Module;
import application.api.ModuleContext;
import application.module.node.gui.NodePanel;
import application.module.node.logging.NodeLoggingProvider;
import javax.swing.JFrame;
import javax.swing.JComponent;

/**
 * A Signum Node modul implementációja az alkalmazás keretrendszeréhez.
 */
public class NodeModule implements Module {

    public static final String ID = "node";
    public static final String DISPLAY_NAME = "Node";

    private ModuleContext context;
    private NodePanel gui;
    private NodeLoggingProvider loggingProvider;

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
        // Register logging provider so the composite logging infrastructure
        // knows about the Node module's built-in defaults & presets.
        if (loggingProvider == null) {
            loggingProvider = new NodeLoggingProvider();
        }
        loggingProvider.register();
    }

    @Override
    public void stop() {
        // Unregister logging provider on shutdown
        if (loggingProvider != null) {
            loggingProvider.unregister();
        }
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

            gui = new NodePanel(parentFrame);
        }
        return gui;
    }
}