package com.filebridge.shared.model;

import java.time.Duration;

/**
 * Preset durations a user can choose when adding a share.
 */
public enum ShareDuration {
    MIN_15("15 minutes", Duration.ofMinutes(15)),
    HOUR_1("1 hour", Duration.ofHours(1)),
    HOUR_4("4 hours", Duration.ofHours(4)),
    DAY_1("1 day", Duration.ofDays(1)),
    FOREVER("Never expires", null);

    private final String label;
    private final Duration duration;

    ShareDuration(String label, Duration duration) {
        this.label = label;
        this.duration = duration;
    }

    public String getLabel() { return label; }
    public Duration getDuration() { return duration; }
    public boolean isForever() { return duration == null; }

    @Override public String toString() { return label; }
}
