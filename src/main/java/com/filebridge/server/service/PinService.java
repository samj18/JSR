package com.filebridge.server.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class PinService {

    private static final Duration PIN_TTL = Duration.ofMinutes(15);

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, Instant> activePins = new ConcurrentHashMap<>();

    public String issuePin() {
        String pin = String.format("%06d", random.nextInt(1_000_000));
        activePins.clear();
        activePins.put(pin, Instant.now().plus(PIN_TTL));
        return pin;
    }

    public Optional<String> currentPin() {
        cleanupExpired();
        return activePins.keySet().stream().findFirst();
    }

    public boolean verify(String pin) {
        if (pin == null) return false;
        cleanupExpired();
        return activePins.containsKey(pin);
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        activePins.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }
}
