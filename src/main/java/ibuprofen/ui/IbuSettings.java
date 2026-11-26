package ibuprofen.ui;

import ibuprofen.IbuprofenMod;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Runtime & persisted settings for Ibuprobridging visuals, HUD position, and toggles.
 * Saved automatically to {@code config/ibuprobridging.properties} whenever changed in the UI.
 */
public final class IbuSettings {

    public boolean showPath = true;
    public boolean showGoalBox = true;
    public boolean showBedMarkers = true;
    public boolean showShopMarkers = false;
    public boolean showHud = true;

    public int hudX = 10;
    public int hudY = 10;

    private Path configFile;

    public void load(Path gameDir) {
        if (gameDir == null) {
            return;
        }
        this.configFile = gameDir.resolve("config").resolve("ibuprobridging.properties");
        if (!Files.exists(configFile)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            p.load(in);
            showPath = parseBool(p, "showPath", showPath);
            showGoalBox = parseBool(p, "showGoalBox", showGoalBox);
            showBedMarkers = parseBool(p, "showBedMarkers", showBedMarkers);
            showShopMarkers = parseBool(p, "showShopMarkers", showShopMarkers);
            showHud = parseBool(p, "showHud", showHud);
            hudX = parseInt(p, "hudX", hudX);
            hudY = parseInt(p, "hudY", hudY);
        } catch (Exception e) {
            IbuprofenMod.LOGGER.warn("Failed to load ibuprofen settings: {}", e.getMessage());
        }
    }

    public void save() {
        if (configFile == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("showPath", Boolean.toString(showPath));
        p.setProperty("showGoalBox", Boolean.toString(showGoalBox));
        p.setProperty("showBedMarkers", Boolean.toString(showBedMarkers));
        p.setProperty("showShopMarkers", Boolean.toString(showShopMarkers));
        p.setProperty("showHud", Boolean.toString(showHud));
        p.setProperty("hudX", Integer.toString(hudX));
        p.setProperty("hudY", Integer.toString(hudY));
        try {
            Files.createDirectories(configFile.getParent());
            try (OutputStream out = Files.newOutputStream(configFile)) {
                p.store(out, "Ibuprobridging UI & Visual Settings");
            }
        } catch (Exception e) {
            IbuprofenMod.LOGGER.warn("Failed to save ibuprofen settings: {}", e.getMessage());
        }
    }

    private static boolean parseBool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v != null ? Boolean.parseBoolean(v.trim()) : def;
    }

    private static int parseInt(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
