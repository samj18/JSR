package com.filebridge.server.controller;

import com.filebridge.server.service.PinService;
import com.filebridge.server.service.ShareService;
import com.filebridge.shared.model.FileEntry;
import com.filebridge.shared.model.SharedFolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class ShareController {

    private static final Logger log = LoggerFactory.getLogger(ShareController.class);

    private final ShareService shareService;
    private final PinService pinService;

    public ShareController(ShareService shareService, PinService pinService) {
        this.shareService = shareService;
        this.pinService = pinService;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of("app", "FileBridge", "version", "0.3.0");
    }

    @GetMapping("/shares")
    public List<Map<String, Object>> listShares(@RequestHeader(value = "X-Pin", required = false) String pin) {
        requirePin(pin);
        return shareService.observableShares().stream()
                .filter(s -> !s.isExpired())
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(),
                        "name", s.getDisplayName(),
                        "writable", s.isWritable(),
                        "expiresAt", s.getExpiresAt() == null ? "" : s.getExpiresAt().toString()
                ))
                .toList();
    }

    @GetMapping("/shares/{id}/list")
    public List<FileEntry> listFolder(@PathVariable String id,
                                      @RequestParam(defaultValue = "") String path,
                                      @RequestHeader(value = "X-Pin", required = false) String pin) throws IOException {
        requirePin(pin);
        shareService.get(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Share not found or expired"));

        Path target = shareService.resolveSafely(id, path)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path"));

        if (!Files.isDirectory(target)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a folder");
        }

        try (Stream<Path> stream = Files.list(target)) {
            return stream
                    .map(p -> {
                        try {
                            return new FileEntry(
                                    p.getFileName().toString(),
                                    Files.isDirectory(p),
                                    Files.isDirectory(p) ? 0 : Files.size(p),
                                    Files.getLastModifiedTime(p).toMillis()
                            );
                        } catch (IOException e) {
                            return new FileEntry(p.getFileName().toString(), false, 0, 0);
                        }
                    })
                    .sorted((a, b) -> {
                        if (a.directory() != b.directory()) return a.directory() ? -1 : 1;
                        return a.name().compareToIgnoreCase(b.name());
                    })
                    .toList();
        }
    }

    @GetMapping("/shares/{id}/file")
    public void downloadFile(@PathVariable String id,
                             @RequestParam String path,
                             @RequestHeader(value = "Range", required = false) String range,
                             @RequestHeader(value = "X-Pin", required = false) String pin,
                             HttpServletResponse response) throws IOException {
        requirePin(pin);

        shareService.get(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Share not found or expired"));

        Path file = shareService.resolveSafely(id, path)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path"));
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }

        long size = Files.size(file);
        long start = 0;
        long end = size - 1;

        if (range != null && range.startsWith("bytes=")) {
            String[] parts = range.substring(6).split("-", -1);
            try {
                start = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                return;
            }
            if (start >= size || end >= size || start > end) {
                response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                response.setHeader("Content-Range", "bytes */" + size);
                return;
            }
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }

        long contentLength = end - start + 1;
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Content-Length", String.valueOf(contentLength));
        response.setHeader("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"");

        try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ);
             WritableByteChannel out = Channels.newChannel(response.getOutputStream())) {
            long position = start;
            long remaining = contentLength;
            while (remaining > 0) {
                long transferred = in.transferTo(position, remaining, out);
                if (transferred <= 0) break;
                position += transferred;
                remaining -= transferred;
            }
        } catch (IOException e) {
            log.warn("Download aborted: {}", e.getMessage());
        }
    }

    @PostMapping("/shares/{id}/upload")
    public Map<String, Object> uploadFile(@PathVariable String id,
                                          @RequestParam(defaultValue = "") String path,
                                          @RequestHeader("X-Filename") String filenameEncoded,
                                          @RequestHeader(value = "X-Pin", required = false) String pin,
                                          HttpServletRequest request) throws IOException {
        requirePin(pin);

        SharedFolder share = shareService.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share not found or expired"));
        if (!share.isWritable()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Share is read-only");
        }

        String filename = java.net.URLDecoder.decode(filenameEncoded, java.nio.charset.StandardCharsets.UTF_8);
        filename = filename.replace("\\", "/");
        if (filename.contains("/") || filename.contains("..") || filename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }

        Path targetFolder = shareService.resolveSafely(id, path)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path"));
        Files.createDirectories(targetFolder);

        Path target = targetFolder.resolve(filename).normalize();
        if (!target.startsWith(share.getRootPath())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid target");
        }

        long total = 0;
        try (ReadableByteChannel src = Channels.newChannel(request.getInputStream());
             FileChannel out = FileChannel.open(target,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(1 << 20);
            int read;
            while ((read = src.read(buf)) != -1) {
                if (read == 0) continue;
                buf.flip();
                while (buf.hasRemaining()) out.write(buf);
                buf.clear();
                total += read;
            }
        }

        log.info("Received upload: {} ({} bytes)", target, total);
        return Map.of("ok", true, "bytesWritten", total, "path", path + "/" + filename);
    }

    @DeleteMapping("/shares/{id}/file")
    public Map<String, Object> deleteEntry(@PathVariable String id,
                                           @RequestParam String path,
                                           @RequestHeader(value = "X-Pin", required = false) String pin) throws IOException {
        requirePin(pin);

        SharedFolder share = shareService.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share not found or expired"));
        if (!share.isWritable()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Share is read-only");
        }

        Path target = shareService.resolveSafely(id, path)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path"));

        if (target.equals(share.getRootPath())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete share root");
        }
        if (!Files.exists(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }

        if (Files.isDirectory(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); }
                            catch (IOException e) { log.warn("Delete failed: {}", e.getMessage()); }
                        });
            }
        } else {
            Files.delete(target);
        }
        log.info("Deleted: {}", target);
        return Map.of("ok", true, "deleted", path);
    }

    private void requirePin(String pin) {
        if (!pinService.verify(pin)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid PIN required");
        }
    }
}
