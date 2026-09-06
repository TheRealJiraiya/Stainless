package net.indecis.observer;

import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class IndecisObserverClient implements ClientModInitializer {
    public static final String MOD_ID = "indecisobserver";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ObserverConfig config;
    private static TelemetryTransport transport;
    private static boolean sessionActive;
    private static boolean warnedNoEndpoint;

    private static long lastHeartbeat;
    private static long lastSightingScan;
    private static long lastCrystalScan;

    private static final Map<UUID, Long> lastPlayerSighting = new HashMap<>();
    private static final Map<UUID, SeenPlayer> seenPlayers = new HashMap<>();
    private static final Set<Integer> seenCrystalIds = new HashSet<>();

    @Override
    public void onInitializeClient() {
        config = ObserverConfig.loadOrCreate();
        transport = new TelemetryTransport();
        transport.start();

        LOGGER.info("Indecis Mobile Observer {} loaded. Config: {}", VERSION, config.file());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            sessionActive = false;
            warnedNoEndpoint = false;
            lastHeartbeat = 0;
            lastSightingScan = 0;
            lastCrystalScan = 0;
            lastPlayerSighting.clear();
            seenPlayers.clear();
            seenCrystalIds.clear();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (sessionActive) {
                JsonObject event = TelemetryEvent.base("mobile_session_end");
                event.addProperty("queuedEvents", transport.queued());
                transport.enqueue(event);
            }
            sessionActive = false;
            lastPlayerSighting.clear();
            seenPlayers.clear();
            seenCrystalIds.clear();
        });

        ClientTickEvents.END_CLIENT_TICK.register(IndecisObserverClient::tick);
    }

    public static ObserverConfig config() {
        if (config == null) config = ObserverConfig.loadOrCreate();
        return config;
    }

    public static void onExplosion(ExplosionS2CPacket packet) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!shouldCapture(client)) return;

        JsonObject event = TelemetryEvent.base("mobile_explosion");
        event.add("center", TelemetryEvent.vec(packet.center()));
        event.addProperty("radius", TelemetryEvent.round(packet.radius()));
        event.addProperty("blockCount", packet.blockCount());
        TelemetryEvent.addObserverPosition(event, client);

        if (client.player != null) {
            event.addProperty("distanceFromObserver", TelemetryEvent.round(new net.minecraft.util.math.Vec3d(client.player.getX(), client.player.getY(), client.player.getZ()).distanceTo(packet.center())));
        }

        if (config().includeExplosionContext) {
            event.add("nearbyPlayers", TelemetryEvent.nearbyPlayers(client, 64.0D, 16));
            addNearestCrystalContext(event, client, packet.center());
        }

        transport.enqueue(event);
    }

    private static void tick(MinecraftClient client) {
        if (!shouldCapture(client)) return;

        if (!sessionActive) {
            sessionActive = true;
            JsonObject event = TelemetryEvent.base("mobile_session_start");
            TelemetryEvent.addObserverPosition(event, client);
            event.addProperty("privacy", "exact-location-private-backend-only");
            transport.enqueue(event);

            client.inGameHud.getChatHud().addMessage(Text.literal(
                "§5[Indecis Observer] §aMobile telemetry active. Exact location is private backend telemetry only."
            ));
        }

        long now = System.currentTimeMillis();

        if (now - lastHeartbeat >= config().heartbeatIntervalMs) {
            lastHeartbeat = now;
            captureHeartbeat(client);
        }

        if (now - lastSightingScan >= config().sightingScanIntervalMs) {
            lastSightingScan = now;
            capturePlayerSightings(client, now);
        }

        if (now - lastCrystalScan >= config().crystalScanIntervalMs) {
            lastCrystalScan = now;
            captureCrystals(client);
        }
    }

    private static boolean shouldCapture(MinecraftClient client) {
        ObserverConfig c = config();
        if (!c.enabled || client == null || client.player == null || client.world == null) return false;
        if (!isAllowedServer(client)) return false;

        if ((c.endpointUrl.isBlank() || c.apiToken.isBlank()) && !warnedNoEndpoint) {
            warnedNoEndpoint = true;
            client.inGameHud.getChatHud().addMessage(Text.literal(
                "§5[Indecis Observer] §eLogging locally. Add endpointUrl + apiToken in config/indecis-observer.json to upload to the tracker."
            ));
        }

        return true;
    }

    private static boolean isAllowedServer(MinecraftClient client) {
        if (client.getCurrentServerEntry() == null) return false;
        String address = client.getCurrentServerEntry().address.toLowerCase();
        int colon = address.indexOf(':');
        String host = colon >= 0 ? address.substring(0, colon) : address;

        for (String suffix : config().allowedServerSuffixes) {
            if (suffix == null || suffix.isBlank()) continue;
            String s = suffix.toLowerCase();
            if (host.equals(s) || host.endsWith("." + s)) return true;
        }
        return false;
    }

    private static void captureHeartbeat(MinecraftClient client) {
        JsonObject event = TelemetryEvent.base("mobile_heartbeat");
        TelemetryEvent.addObserverPosition(event, client);
        event.addProperty("health", TelemetryEvent.round(client.player.getHealth()));
        event.addProperty("food", client.player.getHungerManager().getFoodLevel());
        event.addProperty("nearbyPlayerCount", Math.max(0, client.world.getPlayers().size() - 1));
        event.addProperty("queuedEvents", transport.queued());
        transport.enqueue(event);
    }

    private static void capturePlayerSightings(MinecraftClient client, long now) {
        Set<UUID> visibleNow = new HashSet<>();

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;

            UUID uuid = player.getUuid();
            visibleNow.add(uuid);
            double distance = client.player.distanceTo(player);
            long last = lastPlayerSighting.getOrDefault(uuid, 0L);

            if (now - last >= config().sightingRefreshMs) {
                lastPlayerSighting.put(uuid, now);

                JsonObject event = TelemetryEvent.base("mobile_player_sighting");
                event.addProperty("player", player.getName().getString());
                event.addProperty("playerUuid", uuid.toString());
                event.addProperty("distance", TelemetryEvent.round(distance));
                if (config().includePlayerHealth) {
                    event.addProperty("health", TelemetryEvent.round(player.getHealth()));
                }
                if (config().includeExactCoordinates) {
                    event.add("playerPosition", TelemetryEvent.vec(new net.minecraft.util.math.Vec3d(player.getX(), player.getY(), player.getZ())));
                }
                TelemetryEvent.addObserverPosition(event, client);
                transport.enqueue(event);
            }

            seenPlayers.put(uuid, new SeenPlayer(player.getName().getString(), distance, now));
        }

        Set<UUID> disappeared = new HashSet<>(seenPlayers.keySet());
        disappeared.removeAll(visibleNow);

        for (UUID uuid : disappeared) {
            SeenPlayer prior = seenPlayers.remove(uuid);
            lastPlayerSighting.remove(uuid);
            if (prior == null) continue;

            JsonObject event = TelemetryEvent.base("mobile_player_left_visual_range");
            event.addProperty("player", prior.name());
            event.addProperty("playerUuid", uuid.toString());
            event.addProperty("lastDistance", TelemetryEvent.round(prior.distance()));
            event.addProperty("lastSeenMsAgo", Math.max(0L, now - prior.at()));
            TelemetryEvent.addObserverPosition(event, client);
            transport.enqueue(event);
        }
    }

    private static void captureCrystals(MinecraftClient client) {
        Set<Integer> current = new HashSet<>();

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            current.add(crystal.getId());

            if (!seenCrystalIds.contains(crystal.getId())) {
                JsonObject event = TelemetryEvent.base("mobile_crystal_spawn");
                event.addProperty("entityId", crystal.getId());
                event.addProperty("crystalUuid", crystal.getUuid().toString());
                if (config().includeExactCoordinates) {
                    event.add("crystalPosition", TelemetryEvent.vec(new net.minecraft.util.math.Vec3d(crystal.getX(), crystal.getY(), crystal.getZ())));
                }
                event.addProperty("distance", TelemetryEvent.round(client.player.distanceTo(crystal)));
                TelemetryEvent.addObserverPosition(event, client);
                event.add("nearbyPlayers", TelemetryEvent.nearbyPlayers(client, 32.0D, 12));
                transport.enqueue(event);
            }
        }

        for (Integer oldId : new HashSet<>(seenCrystalIds)) {
            if (current.contains(oldId)) continue;
            JsonObject event = TelemetryEvent.base("mobile_crystal_gone");
            event.addProperty("entityId", oldId);
            TelemetryEvent.addObserverPosition(event, client);
            event.add("nearbyPlayers", TelemetryEvent.nearbyPlayers(client, 32.0D, 12));
            transport.enqueue(event);
        }

        seenCrystalIds.clear();
        seenCrystalIds.addAll(current);
    }

    private static void addNearestCrystalContext(JsonObject event, MinecraftClient client, net.minecraft.util.math.Vec3d center) {
        EndCrystalEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            double d = new net.minecraft.util.math.Vec3d(crystal.getX(), crystal.getY(), crystal.getZ()).distanceTo(center);
            if (d < nearestDistance) {
                nearestDistance = d;
                nearest = crystal;
            }
        }

        if (nearest != null && nearestDistance <= 16.0D) {
            event.addProperty("nearestCrystalEntityId", nearest.getId());
            event.addProperty("nearestCrystalDistance", TelemetryEvent.round(nearestDistance));
            if (config().includeExactCoordinates) {
                event.add("nearestCrystalPosition", TelemetryEvent.vec(new net.minecraft.util.math.Vec3d(nearest.getX(), nearest.getY(), nearest.getZ())));
            }
        }
    }

    private record SeenPlayer(String name, double distance, long at) {}
}
