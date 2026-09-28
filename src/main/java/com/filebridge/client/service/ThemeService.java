package com.filebridge.client.service;

import com.filebridge.server.service.SettingsService;
import javafx.scene.Scene;
import org.springframework.stereotype.Service;

@Service
public class ThemeService {

    public enum Theme { LIGHT, DARK }

    private Theme current = Theme.LIGHT;
    private final SettingsService settingsService;

    public ThemeService(SettingsService settingsService) {
        this.settingsService = settingsService;
        if ("DARK".equals(settingsService.loadTheme())) current = Theme.DARK;
    }

    public Theme getCurrent() { return current; }

    public void apply(Scene scene) {
        if (scene == null || scene.getRoot() == null) return;
        scene.getRoot().getStyleClass().remove("dark");
        if (current == Theme.DARK) scene.getRoot().getStyleClass().add("dark");
    }

    public Theme toggle(Scene scene) {
        current = (current == Theme.LIGHT) ? Theme.DARK : Theme.LIGHT;
        settingsService.saveTheme(current.name());
        apply(scene);
        return current;
    }
}
