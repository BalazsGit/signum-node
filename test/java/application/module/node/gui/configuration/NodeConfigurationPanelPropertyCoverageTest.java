package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import application.module.node.props.Prop;
import application.module.node.props.Props;

/**
 * Consistency guard between the property definitions and the configuration UI.
 * <p>
 * Every property defined in {@link Props} must be reachable from the node
 * configuration panel ({@code NodeConfigurationPanel}): each
 * {@code Props.XXX} constant has to be referenced in the panel source. This
 * encodes the "no property may be left out of the panel" agreement, so that
 * adding a new {@code Prop} without wiring it into the panel fails the build.
 * </p>
 */
@DisplayName("NodeConfigurationPanel Property Coverage Tests")
class NodeConfigurationPanelPropertyCoverageTest {

    private static final String PANEL_SOURCE =
            "src/application/module/node/gui/configuration/NodeConfigurationPanel.java";

    /** @return all property constants declared in {@link Props} (via reflection) */
    private static List<Field> allPropsFields() throws IllegalAccessException {
        List<Field> fields = new ArrayList<>();
        for (Field field : Props.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && Prop.class.isAssignableFrom(field.getType())) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static String readPanelSource() throws Exception {
        File file = new File(PANEL_SOURCE);
        if (!file.isFile()) {
            fail("Cannot locate " + PANEL_SOURCE
                    + " — the test must be run with the project directory as working directory");
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    void propsClass_definesProperties() throws IllegalAccessException {
        List<Field> fields = allPropsFields();
        assertFalse(fields.isEmpty(), "Props must define at least one property");
    }

    @Test
    void everyDefinedProperty_isListedInTheNodeConfigurationPanel() throws Exception {
        String source = readPanelSource();
        List<Field> fields = allPropsFields();
        List<String> missing = new ArrayList<>();
        for (Field field : fields) {
            // The panel wires a property through the Props.XXX constant.
            if (!source.contains("Props." + field.getName())) {
                Object prop = field.get(null);
                missing.add(field.getName() + " (" + ((Prop<?>) prop).getName() + ")");
            }
        }
        assertTrue(missing.isEmpty(),
                "Properties defined in Props but missing from NodeConfigurationPanel: "
                        + missing.stream().sorted().toList()
                        + " — add them to the panel (initUI) so every defined property is listed.");
    }

    @Test
    void panelReferences_onlyUseExistingPropsConstants() throws Exception {
        String source = readPanelSource();
        List<String> knownNames = new ArrayList<>();
        for (Field field : allPropsFields()) {
            knownNames.add(field.getName());
        }
        List<String> unknown = new ArrayList<>();
        for (String token : source.split("\\s+")) {
            String trimmed = token.replaceAll("^[;(,\\[]+", "").replaceAll("[);,].*$", "");
            // Case-sensitive: must be the Props CLASS reference (Props.XXX), not a
            // lowercase local variable named "props" (props.setProperty(...)).
            if (trimmed.startsWith("Props.") && trimmed.length() > "Props.".length()) {
                // Cut at the first dot: Props.XXX.getName() / Props.XXX, etc.
                String name = trimmed.substring("Props.".length()).split("\\.")[0];
                if (!knownNames.contains(name)) {
                    unknown.add(name);
                }
            }
        }
        assertFalse(!unknown.isEmpty(),
                "NodeConfigurationPanel references unknown Props constants: " + unknown.stream().distinct().toList()
                        + " — they were probably renamed or removed from Props.");
    }
}
