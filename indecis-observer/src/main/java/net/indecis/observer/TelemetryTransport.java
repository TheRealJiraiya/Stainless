package net.indecis.observer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class TelemetryTransport {
    private static final Gson GSON = new Gson();
    private final Object lock = new Object();
    private final ArrayDeque<JsonObject> queue = new ArrayDeque<>();
    private final Path spoolFile = FabricLoader.getInstance().getConfigDir().resolve("indecis-observer-spool.jsonl");
    private final ScheduledExecutorService executor;
    private final HttpClient http;
    private volatile boolean started;

    public TelemetryTransport() {
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IndecisObserver-Transport");
            t.setDaemon(true);
            return t;
        });

        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(executor)
            .build();

        loadSpool();
    }

    public void start() {
        if (started) return;
        started = true;

        long interval = IndecisObserverClient.config().sendIntervalMs;
        executor.scheduleWithFixedDelay(this::flushSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void enqueue(JsonObject event) {
        synchronized (lock) {
            int max = IndecisObserverClient.config().maxSpoolEvents;
            while (queue.size() >= max) queue.pollFirst();
            queue.addLast(event.deepCopy());
            appendSpool(event);
        }
    }

    public int queued() {
        synchronized (lock) {
            return queue.size();
        }
    }

    private void flushSafely() {
        try {
            flush();
        } catch (Throwable t) {
            IndecisObserverClient.LOGGER.debug("Telemetry flush failed: {}", t.toString());
        }
    }

    private void flush() throws Exception {
        ObserverConfig config = IndecisObserverClient.config();
        if (!config.enabled || config.endpointUrl.isBlank() || config.apiToken.isBlank()) return;

        List<JsonObject> batch = new ArrayList<>();
        synchronized (lock) {
            int limit = Math.min(config.sendBatchSize, queue.size());
            var iterator = queue.iterator();
            for (int i = 0; i < limit && iterator.hasNext(); i++) {
                batch.add(iterator.next().deepCopy());
            }
        }

        if (batch.isEmpty()) return;

        JsonArray events = new JsonArray();
        batch.forEach(events::add);
        JsonObject body = new JsonObject();
        body.add("events", events);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.endpointUrl))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.apiToken)
            .header("User-Agent", "Indecis-Mobile-Observer/" + IndecisObserverClient.VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }

        synchronized (lock) {
            for (int i = 0; i < batch.size(); i++) queue.pollFirst();
            rewriteSpool();
        }
    }

    private void loadSpool() {
        try {
            Files.createDirectories(spoolFile.getParent());
            if (!Files.exists(spoolFile)) return;

            int max = IndecisObserverClient.config().maxSpoolEvents;
            List<String> lines = Files.readAllLines(spoolFile, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - max);
            for (int i = start; i < lines.size(); i++) {
                try {
                    queue.addLast(JsonParser.parseString(lines.get(i)).getAsJsonObject());
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            IndecisObserverClient.LOGGER.warn("Could not load telemetry spool: {}", e.toString());
        }
    }

    private void appendSpool(JsonObject event) {
        try {
            Files.createDirectories(spoolFile.getParent());
            Files.writeString(
                spoolFile,
                GSON.toJson(event) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            IndecisObserverClient.LOGGER.warn("Could not append telemetry spool: {}", e.toString());
        }
    }

    private void rewriteSpool() {
        try {
            StringBuilder out = new StringBuilder();
            for (JsonObject event : queue) out.append(GSON.toJson(event)).append(System.lineSeparator());
            Files.writeString(
                spoolFile,
                out.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception e) {
            IndecisObserverClient.LOGGER.warn("Could not compact telemetry spool: {}", e.toString());
        }
    }
}
