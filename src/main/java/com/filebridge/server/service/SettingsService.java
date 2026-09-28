package com.filebridge.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * User preferences persisted to {@code ~/.filebridge/settings.properties}.
 * Note: port is NOT stored here — it's fixed via {@code application.properties}.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final Path settingsFile = Path.of(
            System.getProperty("user.home"), ".filebridge", "settings.properties");

    public void saveServerPort(int port) {
        Properties props = load();
        props.setProperty("server.port", String.valueOf(port));
        save(props);
    }

    public Integer loadServerPort() {
        String v = load().getProperty("server.port");
        try { return v == null ? null : Integer.parseInt(v); }
        catch (NumberFormatException e) { return null; }
    }

    public void savePreferredIp(String ip) {
        Properties props = load();
        if (ip == null || ip.isEmpty()) props.remove("filebridge.preferred-ip");
        else props.setProperty("filebridge.preferred-ip", ip);
        save(props);
    }

    public String loadPreferredIp() { return load().getProperty("filebridge.preferred-ip"); }

    public void saveTheme(String theme) {
        Properties props = load();
        props.setProperty("filebridge.theme", theme);
        save(props);
    }

    public String loadTheme() { return load().getProperty("filebridge.theme", "LIGHT"); }

    public void saveLanguage(String lang) {
        Properties props = load();
        props.setProperty("filebridge.language", lang);
        save(props);
    }

    public String loadLanguage() { return load().getProperty("filebridge.language", "en"); }

    public Path getSettingsFile() { return settingsFile; }

    private Properties load() {
        Properties props = new Properties();
        if (Files.exists(settingsFile)) {
            try (var in = Files.newInputStream(settingsFile)) {
                props.load(in);
            } catch (IOException e) {
                log.warn("Could not read settings: {}", e.getMessage());
            }
        }
        return props;
    }

    private void save(Properties props) {
        try {
            Files.createDirectories(settingsFile.getParent());
            try (OutputStream out = Files.newOutputStream(settingsFile)) {
                props.store(out, "FileBridge user settings");
            }
        } catch (IOException e) {
            log.error("Could not write settings: {}", e.getMessage());
        }
    }
}
