package brs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class DisplayConfigService {

    private static final Logger logger = LoggerFactory.getLogger(DisplayConfigService.class);
    private static final String CONFIG_DIR = "conf";
    private static final String CONFIG_FILE = "display-config.json";
    private final File configFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public DisplayConfigService() {
        File confDir = new File(CONFIG_DIR);
        if (!confDir.exists()) {
            confDir.mkdirs();
        }
        this.configFile = new File(confDir, CONFIG_FILE);
    }

    public DisplayConfig load() {
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                DisplayConfig config = gson.fromJson(reader, DisplayConfig.class);
                if (config != null) {
                    return config;
                }
            } catch (IOException | com.google.gson.JsonSyntaxException e) {
                logger.error("Failed to load display config, using defaults.", e);
            }
        }
        // Return default config if file doesn't exist or is invalid
        return new DisplayConfig();
    }

    public void save(DisplayConfig config) {
        try (FileWriter writer = new FileWriter(configFile)) {
            gson.toJson(config, writer);
        } catch (IOException e) {
            logger.error("Failed to save display config.", e);
        }
    }
}