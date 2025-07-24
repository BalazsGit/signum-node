package brs;

import javax.swing.SwingUtilities;

import brs.props.Props;

public class SignumLauncher {

    public static String []args;

    /**
     * The main entry point for the Signum node application (headless mode).
     * <p>
     * This method initializes the shutdown hook, parses command-line arguments,
     * and starts the node initialization process.
     *
     * @param args Command-line arguments for the node.
     */
    
    public static void main(String[] args) {

        SignumLauncher.args = args;
        Signum signum = new Signum(args);

        // TODO: Implement args dependent CLI options later

        int startMode = 1; // 0: Headless, 1: GUI, 2: CLI

        for (String arg : args) {
            if (arg.equals("-l") || arg.equals("--headless")) {
                startMode = 0;  // Headless mode// GUI mode
            }
            else if (arg.equals("-g") || arg.equals("--gui")) {
                startMode = 1; // GUI mode
            }
            else if (arg.equals("-c") || arg.equals("--cli")) {
                startMode = 2; // CLI mode
            }
        }

        // Start Signum node in headless mode
        if (startMode == 0) {
            signum.init();
            return;
        }

        // Start Signum node with GUI
        if (startMode == 1) {
            SignumGUI signumGUI = new SignumGUI("Signum Node", Props.ICON_LOCATION.getDefaultValue(), Signum.VERSION.toString(), signum);
            new Thread(() -> signumGUI.startSignumWithGUI()).start();
            return;
        }

    }
        
}