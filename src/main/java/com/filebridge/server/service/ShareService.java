package com.filebridge.server.service;

import com.filebridge.shared.model.SharedFolder;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Single source of truth for which folders are being shared and when they expire.
 * The {@link #sweepExpired()} method runs every 30 seconds to auto-remove expired shares.
 */
@Service
public class ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareService.class);

    private final ConcurrentMap<String, SharedFolder> shares = new ConcurrentHashMap<>();
    private final ObservableList<SharedFolder> observable = FXCollections.observableArrayList();

    public synchronized SharedFolder addShare(String displayName, Path path, boolean writable, Instant expiresAt) {
        SharedFolder folder = new SharedFolder(displayName, path, writable, expiresAt);
        shares.put(folder.getId(), folder);
        runOnFx(() -> observable.add(folder));
        return folder;
    }

    public synchronized void removeShare(String id) {
        SharedFolder removed = shares.remove(id);
        if (removed != null) runOnFx(() -> observable.remove(removed));
    }

    public synchronized void setWritable(String id, boolean writable) {
        SharedFolder f = shares.get(id);
        if (f != null) {
            f.setWritable(writable);
            runOnFx(() -> {
                int idx = observable.indexOf(f);
                if (idx >= 0) observable.set(idx, f);
            });
        }
    }

    public Optional<SharedFolder> get(String id) {
        SharedFolder f = shares.get(id);
        if (f == null || f.isExpired()) return Optional.empty();
        return Optional.of(f);
    }

    public ObservableList<SharedFolder> observableShares() {
        return observable;
    }

    public Optional<Path> resolveSafely(String shareId, String relativePath) {
        SharedFolder share = shares.get(shareId);
        if (share == null || share.isExpired()) return Optional.empty();
        Path resolved = share.getRootPath().resolve(relativePath).normalize();
        if (!resolved.startsWith(share.getRootPath())) return Optional.empty();
        return Optional.of(resolved);
    }

    /**
     * Removes expired shares. Runs automatically every 30 seconds.
     * Also refreshes the UI list so the remaining-time labels tick down.
     */
    @Scheduled(fixedRate = 30_000, initialDelay = 30_000)
    public synchronized void sweepExpired() {
        boolean any = false;
        for (SharedFolder f : shares.values()) {
            if (f.isExpired()) {
                shares.remove(f.getId());
                SharedFolder removed = f;
                runOnFx(() -> observable.remove(removed));
                log.info("Share expired and removed: {} ({})", f.getDisplayName(), f.getRootPath());
                any = true;
            }
        }
        if (!any) {
            // trigger a UI refresh so the "time left" text updates
            runOnFx(() -> {
                for (int i = 0; i < observable.size(); i++) {
                    observable.set(i, observable.get(i));
                }
            });
        }
    }

    private void runOnFx(Runnable r) {
        try {
            if (Platform.isFxApplicationThread()) r.run();
            else Platform.runLater(r);
        } catch (IllegalStateException e) {
            // FX toolkit not yet initialized (during startup) — run inline
            r.run();
        }
    }
}
