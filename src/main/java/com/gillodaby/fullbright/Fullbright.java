package com.gillodaby.fullbright;

import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktype.BlockTypePacketGenerator;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

public final class Fullbright {
    public static final int MIN_MULTIPLIER = 99999999;
    public static final int MAX_MULTIPLIER = 99999999;
    private static final String TORCH_KEYWORD = "torch";
    private static final Object LOCK = new Object();
    private static final Map<String, ColorLight> baseTorchLights = new HashMap<>();
    private static int multiplier = MAX_MULTIPLIER;
    private static int lastAppliedMultiplier = -1;
    private static Field lightField;

    private Fullbright() {
    }

    public static void init() {
        registerWorldLoadListener();
        applyTorchLightMultiplier();
    }

    public static int getMultiplier() {
        synchronized (LOCK) {
            return multiplier;
        }
    }

    public static void setMultiplier(int value) {
        int clamped = clampMultiplier(value);
        boolean changed;
        synchronized (LOCK) {
            changed = multiplier != clamped;
            multiplier = clamped;
        }
        if (changed) {
            applyTorchLightMultiplier();
        }
    }

    private static void registerWorldLoadListener() {
        try {
            EventBus bus = HytaleServer.get().getEventBus();
            bus.registerGlobal(AllWorldsLoadedEvent.class, (AllWorldsLoadedEvent ev) -> {
                try {
                    applyTorchLightMultiplier();
                    refreshLighting();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            });
        } catch (Throwable t) {
            System.out.println("[Fullbright] Failed to register world-load listener: " + t.getMessage());
        }
    }

    private static void applyTorchLightMultiplier() {
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        if (map == null) {
            System.out.println("[Fullbright] BlockType asset map not available yet.");
            return;
        }

        Field field = getLightField();
        if (field == null) {
            System.out.println("[Fullbright] Failed to access BlockType.light field.");
            return;
        }

        Map<String, BlockType> changed = new HashMap<>();
        int scanned = 0;
        int targetMultiplier;
        Map<String, ColorLight> torchLights;

        synchronized (LOCK) {
            targetMultiplier = multiplier;
            if (targetMultiplier == lastAppliedMultiplier && !baseTorchLights.isEmpty()) return;

            if (baseTorchLights.isEmpty()) {
                for (Map.Entry<String, BlockType> entry : map.getAssetMap().entrySet()) {
                    String key = entry.getKey();
                    BlockType block = entry.getValue();
                    if (key == null || block == null) continue;
                    if (!key.toLowerCase(Locale.ROOT).contains(TORCH_KEYWORD)) continue;
                    ColorLight light = block.getLight();
                    if (light == null) continue;
                    baseTorchLights.put(key, new ColorLight(light));
                }
            }

            torchLights = new HashMap<>(baseTorchLights);
        }

        for (Map.Entry<String, ColorLight> entry : torchLights.entrySet()) {
            String key = entry.getKey();
            BlockType block = map.getAsset(key);
            if (block == null) continue;
            scanned++;

            ColorLight original = entry.getValue();
            ColorLight boosted = boostLight(original, targetMultiplier);
            if (!boosted.equals(block.getLight())) {
                try {
                    field.set(block, boosted);
                    changed.put(key, block);
                } catch (IllegalAccessException e) {
                    System.out.println("[Fullbright] Failed to update light for " + key + ": " + e.getMessage());
                }
            }
        }

        synchronized (LOCK) {
            lastAppliedMultiplier = targetMultiplier;
        }

        if (!changed.isEmpty()) {
            int sampleLogged = 0;
            for (Map.Entry<String, BlockType> e : changed.entrySet()) {
                if (sampleLogged >= 5) break;
                ColorLight base = torchLights.get(e.getKey());
                ColorLight newLight = e.getValue().getLight();
                System.out.println("[Fullbright] Sample change " + e.getKey() + ": " + describeLight(base) + " -> " + describeLight(newLight));
                sampleLogged++;
            }
            sendBlockTypeUpdate(changed);
            refreshLighting();
            System.out.println("[Fullbright] Torch light multiplier set to x" + targetMultiplier + " for " + changed.size() + " block types.");
        } else {
            System.out.println("[Fullbright] No torch block types found to update (scanned=" + scanned + ").");
        }
    }

    private static String describeLight(ColorLight light) {
        if (light == null) return "null";
        int radius = Byte.toUnsignedInt(light.radius);
        int red = Byte.toUnsignedInt(light.red);
        int green = Byte.toUnsignedInt(light.green);
        int blue = Byte.toUnsignedInt(light.blue);
        return "radius=" + radius + " rgb=(" + red + "," + green + "," + blue + ")";
    }

    private static Field getLightField() {
        if (lightField != null) return lightField;
        try {
            Field field = BlockType.class.getDeclaredField("light");
            field.setAccessible(true);
            lightField = field;
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ColorLight boostLight(ColorLight original, int multiplier) {
        int radius = Byte.toUnsignedInt(original.radius);
        int red = Byte.toUnsignedInt(original.red);
        int green = Byte.toUnsignedInt(original.green);
        int blue = Byte.toUnsignedInt(original.blue);

        // Some torch assets report radius=0; fall back to a visible base radius and base color
        int maxChannel = Math.max(red, Math.max(green, blue));
        if (radius == 0) {
            radius = Math.max(8, maxChannel / 3); // give a minimal radius
        }
        if (maxChannel == 0) {
            maxChannel = 16; // ensure some color intensity exists
            red = green = blue = maxChannel;
        }

        int newRadius = clampByte(radius * multiplier);
        int newRed = clampByte(red * multiplier);
        int newGreen = clampByte(green * multiplier);
        int newBlue = clampByte(blue * multiplier);

        return new ColorLight((byte) newRadius, (byte) newRed, (byte) newGreen, (byte) newBlue);
    }

    private static int clampByte(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    private static int clampMultiplier(int value) {
        if (value < MIN_MULTIPLIER) return MIN_MULTIPLIER;
        if (value > MAX_MULTIPLIER) return MAX_MULTIPLIER;
        return value;
    }

    private static void sendBlockTypeUpdate(Map<String, BlockType> changed) {
        try {
            Universe universe = Universe.get();
            if (universe == null) return;

            BlockTypePacketGenerator generator = new BlockTypePacketGenerator();
            Packet packet = generator.generateUpdatePacket(BlockType.getAssetMap(), changed, AssetUpdateQuery.DEFAULT);
            if (packet != null) {
                universe.broadcastPacket(packet);
            }
        } catch (Throwable t) {
            System.out.println("[Fullbright] Failed to broadcast block updates: " + t.getMessage());
        }
    }

    private static void refreshLighting() {
        Universe universe = Universe.get();
        if (universe == null) {
            System.out.println("[Fullbright] Universe not ready yet, lighting refresh skipped.");
            return;
        }

        int refreshed = 0;
        for (World world : universe.getWorlds().values()) {
            if (world == null) continue;
            if (world.getChunkLighting() == null) continue;
            ChunkStore chunkStore = world.getChunkStore();
            if (chunkStore == null || chunkStore.getStore() == null) continue;
            try {
                world.getChunkLighting().invalidateLoadedChunks();
                refreshed++;
            } catch (Throwable t) {
                System.out.println("[Fullbright] Lighting refresh skipped for " + world.getName() + ": " + t.getMessage());
            }
        }

        if (refreshed > 0) {
            System.out.println("[Fullbright] Lighting refresh requested for " + refreshed + " world(s).");
        }
    }
}
