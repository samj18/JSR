package com.filebridge.client.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

@Service
public class PeerClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public record RemoteShare(String id, String name, boolean writable) {}
    public record RemoteEntry(String name, boolean directory, long size, long lastModified) {}

    private String base(String host, int port) { return "http://" + host + ":" + port; }

    public List<RemoteShare> listShares(String host, int port, String pin) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base(host, port) + "/api/shares"))
                .header("X-Pin", pin).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
        List<RemoteShare> out = new ArrayList<>();
        for (JsonNode n : mapper.readTree(resp.body())) {
            out.add(new RemoteShare(
                    n.get("id").asText(),
                    n.get("name").asText(),
                    n.has("writable") && n.get("writable").asBoolean()));
        }
        return out;
    }

    public List<RemoteEntry> listFolder(String host, int port, String pin,
                                        String shareId, String path) throws IOException, InterruptedException {
        String url = base(host, port) + "/api/shares/" + shareId + "/list?path="
                + URLEncoder.encode(path, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url)).header("X-Pin", pin).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
        List<RemoteEntry> out = new ArrayList<>();
        for (JsonNode n : mapper.readTree(resp.body())) {
            out.add(new RemoteEntry(
                    n.get("name").asText(),
                    n.get("directory").asBoolean(),
                    n.get("size").asLong(),
                    n.get("lastModified").asLong()));
        }
        return out;
    }

    public void downloadFile(String host, int port, String pin,
                             String shareId, String remotePath,
                             Path localTarget, LongConsumer progress) throws IOException, InterruptedException {
        String url = base(host, port) + "/api/shares/" + shareId + "/file?path="
                + URLEncoder.encode(remotePath, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url)).header("X-Pin", pin).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200 && resp.statusCode() != 206)
            throw new IOException("HTTP " + resp.statusCode());

        if (localTarget.getParent() != null) Files.createDirectories(localTarget.getParent());
        try (InputStream in = resp.body();
             ReadableByteChannel src = Channels.newChannel(in);
             FileChannel out = FileChannel.open(localTarget,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
            long total = 0;
            int read;
            while ((read = src.read(buf)) != -1) {
                if (read == 0) continue;
                buf.flip();
                while (buf.hasRemaining()) out.write(buf);
                buf.clear();
                total += read;
                progress.accept(total);
            }
        }
    }

    public void uploadFile(String host, int port, String pin,
                           String shareId, String remoteSubFolder,
                           Path localFile, LongConsumer progress) throws IOException, InterruptedException {
        String url = base(host, port) + "/api/shares/" + shareId + "/upload?path="
                + URLEncoder.encode(remoteSubFolder, StandardCharsets.UTF_8);

        String filenameEncoded = URLEncoder.encode(localFile.getFileName().toString(), StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofInputStream(() -> {
            try { return new ProgressInputStream(Files.newInputStream(localFile, StandardOpenOption.READ), progress); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Pin", pin)
                .header("X-Filename", filenameEncoded)
                .header("Content-Type", "application/octet-stream")
                .POST(body).build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new IOException("Upload failed (HTTP " + resp.statusCode() + "): " + resp.body());
    }

    public void uploadFolder(String host, int port, String pin,
                             String shareId, String remoteSubFolder,
                             Path localRoot, FolderUploadProgress progress) throws IOException, InterruptedException {
        long[] totals = {0, 0};
        try (var s = Files.walk(localRoot)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                totals[0]++;
                try { totals[1] += Files.size(p); } catch (IOException ignored) {}
            });
        }
        progress.onStart(totals[0], totals[1]);
        long[] sentBytes = {0};
        int[] sentFiles = {0};

        try (var s = Files.walk(localRoot)) {
            List<Path> files = s.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                Path relative = localRoot.relativize(file);
                String relativeFolder = relative.getParent() == null ? "" : relative.getParent().toString().replace("\\", "/");
                String remoteSub = remoteSubFolder.isEmpty()
                        ? localRoot.getFileName().toString() + (relativeFolder.isEmpty() ? "" : "/" + relativeFolder)
                        : remoteSubFolder + "/" + localRoot.getFileName() + (relativeFolder.isEmpty() ? "" : "/" + relativeFolder);
                long fileSize = Files.size(file);
                long fileBaseline = sentBytes[0];

                uploadFile(host, port, pin, shareId, remoteSub, file, bytesInThisFile -> {
                    long newTotal = fileBaseline + bytesInThisFile;
                    sentBytes[0] = newTotal;
                    progress.onProgress(sentFiles[0], totals[0], newTotal, totals[1], file.getFileName().toString());
                });
                sentFiles[0]++;
                sentBytes[0] = fileBaseline + fileSize;
                progress.onProgress(sentFiles[0], totals[0], sentBytes[0], totals[1], file.getFileName().toString());
            }
        }
    }

    public void deleteEntry(String host, int port, String pin,
                            String shareId, String remotePath) throws IOException, InterruptedException {
        String url = base(host, port) + "/api/shares/" + shareId + "/file?path="
                + URLEncoder.encode(remotePath, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url)).header("X-Pin", pin).DELETE().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new IOException("Delete failed (HTTP " + resp.statusCode() + "): " + resp.body());
    }

    public interface FolderUploadProgress {
        void onStart(long totalFiles, long totalBytes);
        void onProgress(long doneFiles, long totalFiles, long doneBytes, long totalBytes, String currentFile);
    }

    private static class ProgressInputStream extends InputStream {
        private final InputStream delegate;
        private final LongConsumer progress;
        private long counted = 0;
        ProgressInputStream(InputStream d, LongConsumer p) { this.delegate = d; this.progress = p; }
        @Override public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) progress.accept(++counted);
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) { counted += n; progress.accept(counted); }
            return n;
        }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
