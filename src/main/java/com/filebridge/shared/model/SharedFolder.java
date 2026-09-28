package com.filebridge.shared.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * A folder being shared on the LAN.
 *
 * <p>{@code writable} controls whether peers can upload INTO it.
 * <p>{@code expiresAt} is when this share stops being accessible; {@code null} means never.
 */
public class SharedFolder {

    private final String id;
    private final String displayName;
    private final Path rootPath;
    private boolean writable;
    private final Instant expiresAt;   // null = never expires

    public SharedFolder(String displayName, Path rootPath, boolean writable, Instant expiresAt) {
        this(UUID.randomUUID().toString(), displayName, rootPath, writable, expiresAt);
    }

    public SharedFolder(String id, String displayName, Path rootPath, boolean writable, Instant expiresAt) {
        this.id = id;
        this.displayName = displayName;
        this.rootPath = rootPath.toAbsolutePath().normalize();
        this.writable = writable;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public Path getRootPath() { return rootPath; }
    public boolean isWritable() { return writable; }
    public void setWritable(boolean writable) { this.writable = writable; }
    public Instant getExpiresAt() { return expiresAt; }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Human-readable remaining time (e.g. "2h 15m left", "expired", "no expiry").
     */
    public String remainingTimeLabel() {
        if (expiresAt == null) return "no expiry";
        long secs = java.time.Duration.between(Instant.now(), expiresAt).getSeconds();
        if (secs <= 0) return "expired";
        long d = secs / 86_400;
        long h = (secs % 86_400) / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0 || (d == 0 && h == 0)) sb.append(m).append("m ");
        if (d == 0 && h == 0 && m == 0) sb.append(s).append("s ");
        return sb.toString().trim() + " left";
    }

    @Override
    public String toString() {
        String icon = writable ? "✏ " : "🔒 ";
        String status = isExpired() ? "  ⏰ EXPIRED" : "  ⏱ " + remainingTimeLabel();
        return icon + displayName + status + "   (" + rootPath + ")";
    }
}
