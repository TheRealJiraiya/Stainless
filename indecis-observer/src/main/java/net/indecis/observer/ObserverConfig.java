package net.indecis.observer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ObserverConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("indecis-observer.json");

    public boolean enabled = true;
    public String endpointUrl = "";
    public String apiToken = "";
    public String observerId = "mobile-01";
    public List<String> allowedServerSuffixes = new ArrayList<>(List.of("6b6t.org"));

    public boolean includeExactCoordinates = true;
    public boolean includePlayerHealth = true;
    public boolean includeExplosionContext = true;

    public long heartbeatIntervalMs = 10_000L;
    public long sightingScanIntervalMs = 1_000L;
    public long sightingRefreshMs = 5_000L;
    public long crystalScanIntervalMs = 250L;
    public long sendIntervalMs = 1_000L;
    public int sendBatchSize = 50;
    public int maxSpoolEvents = 10_000;

    public static ObserverConfig loadOrCreate() {
        try {
            Files.createDirectories(FILE.getParent());

            if (!Files.exists(FILE)) {
                ObserverConfig config = new ObserverConfig();
                config.save();
                return config;
            }

            String json = Files.readString(FILE, StandardCharsets.UTF_8);
            ObserverConfig config = GSON.fromJson(json, ObserverConfig.class);
            if (config == null) config = new ObserverConfig();
            config.normalize();
            return config;
        } catch (Exception e) {
            IndecisObserverClient.LOGGER.error("Failed to load observer config; using safe defaults", e);
            return new ObserverConfig();
        }
    }

    public void save() throws IOException {
        normalize();
        Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
    }

    public Path file() {
        return FILE;
    }

    private void normalize() {
        if (endpointUrl == null) endpointUrl = "";
        if (apiToken == null) apiToken = "";
        if (observerId == null || observerId.isBlank()) observerId = "mobile-01";
        if (allowedServerSuffixes == null || allowedServerSuffixes.isEmpty()) {
            allowedServerSuffixes = new ArrayList<>(List.of("6b6t.org"));
        }

        heartbeatIntervalMs = clamp(heartbeatIntervalMs, 2_000L, 120_000L);
        sightingScanIntervalMs = clamp(sightingScanIntervalMs, 250L, 30_000L);
        sightingRefreshMs = clamp(sightingRefreshMs, 1_000L, 120_000L);
        crystalScanIntervalMs = clamp(crystalScanIntervalMs, 100L, 10_000L);
        sendIntervalMs = clamp(sendIntervalMs, 250L, 30_000L);
        sendBatchSize = (int) clamp(sendBatchSize, 1, 100);
        maxSpoolEvents = (int) clamp(maxSpoolEvents, 100, 50_000);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
