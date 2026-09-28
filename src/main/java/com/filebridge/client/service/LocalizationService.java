package com.filebridge.client.service;

import org.springframework.stereotype.Service;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

@Service
public class LocalizationService {

    private Locale locale = Locale.ENGLISH;
    private ResourceBundle bundle = ResourceBundle.getBundle("messages", locale);

    public Locale getLocale() { return locale; }

    public void setLocale(Locale locale) {
        this.locale = locale;
        ResourceBundle.clearCache();
        this.bundle = ResourceBundle.getBundle("messages", locale);
    }

    public String t(String key) {
        try { return bundle.getString(key); }
        catch (MissingResourceException e) { return key; }
    }

    public String t(String key, Object... args) {
        try { return MessageFormat.format(bundle.getString(key), args); }
        catch (MissingResourceException e) { return key; }
    }

    public boolean isRtl() {
        String l = locale.getLanguage();
        return "ar".equals(l) || "he".equals(l) || "fa".equals(l);
    }
}
