package net.indecis.observer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.time.Instant;
import java.util.UUID;

public final class TelemetryEvent {
    private TelemetryEvent() {}

    public static JsonObject base(String type) {
        MinecraftClient client = MinecraftClient.getInstance();
        JsonObject event = new JsonObject();
        event.addProperty("timestamp", Instant.now().toString());
        event.addProperty("type", type);
        event.addProperty("observer", IndecisObserverClient.config().observerId);
        event.addProperty("source", client.player != null ? client.player.getName().getString() : "unknown");
        event.addProperty("modVersion", IndecisObserverClient.VERSION);

        if (client.player != null) {
            event.addProperty("observerUuid", client.player.getUuid().toString());
        }

        if (client.world != null) {
            event.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
        }

        if (client.getCurrentServerEntry() != null) {
            event.addProperty("server", client.getCurrentServerEntry().address);
        }

        return event;
    }

    public static JsonObject vec(Vec3d pos) {
        JsonObject o = new JsonObject();
        o.addProperty("x", round(pos.x));
        o.addProperty("y", round(pos.y));
        o.addProperty("z", round(pos.z));
        return o;
    }

    public static void addObserverPosition(JsonObject event, MinecraftClient client) {
        if (!IndecisObserverClient.config().includeExactCoordinates || client.player == null) return;
        event.add("observerPosition", vec(client.player.getPos()));
    }

    public static JsonObject entityIdentity(Entity entity) {
        JsonObject o = new JsonObject();
        o.addProperty("entityId", entity.getId());
        UUID uuid = entity.getUuid();
        if (uuid != null) o.addProperty("uuid", uuid.toString());
        o.addProperty("name", entity.getName().getString());
        if (IndecisObserverClient.config().includeExactCoordinates) {
            o.add("position", vec(entity.getPos()));
        }
        return o;
    }

    public static JsonArray nearbyPlayers(MinecraftClient client, double maxDistance, int limit) {
        JsonArray array = new JsonArray();
        if (client.world == null || client.player == null) return array;

        client.world.getPlayers().stream()
            .filter(p -> p != client.player)
            .map(p -> new PlayerDistance(p, client.player.distanceTo(p)))
            .filter(pd -> pd.distance <= maxDistance)
            .sorted((a, b) -> Double.compare(a.distance, b.distance))
            .limit(limit)
            .forEach(pd -> {
                JsonObject row = entityIdentity(pd.player);
                row.addProperty("distance", round(pd.distance));
                if (IndecisObserverClient.config().includePlayerHealth) {
                    row.addProperty("health", round(pd.player.getHealth()));
                }
                array.add(row);
            });

        return array;
    }

    private record PlayerDistance(net.minecraft.client.network.AbstractClientPlayerEntity player, double distance) {}

    public static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
