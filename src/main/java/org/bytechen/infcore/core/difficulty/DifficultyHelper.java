package org.bytechen.infcore.core.difficulty;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.bytechen.infcore.api.difficulty.DifficultyType;
import org.bytechen.infcore.core.Infcore;

/**
 * Static helper for querying and mutating the global difficulty.
 * <p>
 * This is the primary API for both framework-internal code and downstream mods.
 * All server-side operations require a {@link ServerLevel} to locate the
 * overworld's {@link GlobalDifficultyData}.
 * <p>
 * Client-side, use {@link #getClientDifficulty()} for the last synced value.
 *
 * <h3>Usage (server)</h3>
 * <pre>{@code
 * ResourceLocation current = DifficultyHelper.getDifficulty(serverLevel);
 * if (current.equals(DifficultyType.HARD.getId())) {
 *     // apply hard-mode logic
 * }
 *
 * // Switch difficulty (fires events, syncs to clients)
 * DifficultyHelper.setDifficulty(serverLevel, DifficultyType.HARD.getId());
 * }</pre>
 *
 * <h3>Usage (client)</h3>
 * <pre>{@code
 * ResourceLocation current = DifficultyHelper.getClientDifficulty();
 * }</pre>
 */
public final class DifficultyHelper {

    private static final String DATA_NAME = "infcore_global_difficulty";
    private static ResourceLocation clientDifficulty = DifficultyType.NORMAL.getId();

    private DifficultyHelper() {}

    // ==================== Server-side ====================

    /**
     * Get the global difficulty.
     * <p>
     * The difficulty is stored as SavedData on the overworld. Any ServerLevel
     * can be passed — the overworld is resolved automatically.
     *
     * @param level any ServerLevel (used to reach the overworld's data storage)
     * @return the current difficulty ResourceLocation, never null
     */
    public static ResourceLocation getDifficulty(ServerLevel level) {
        GlobalDifficultyData data = getOrCreate(level);
        return data.getCurrentDifficulty();
    }

    /**
     * Set the global difficulty. Fires {@code DifficultyChangeEvent.Pre}
     * (cancelable) and {@code DifficultyChangeEvent.Post}, persists to disk,
     * and syncs to all clients.
     *
     * @param level      any ServerLevel (used to reach the overworld)
     * @param difficulty the new difficulty ResourceLocation
     * @return true if the change was applied, false if canceled or already active
     */
    public static boolean setDifficulty(ServerLevel level, ResourceLocation difficulty) {
        GlobalDifficultyData data = getOrCreate(level);
        ServerLevel overworld = level.getServer().overworld();
        return data.setDifficulty(overworld, difficulty);
    }

    // ==================== Client-side ====================

    /**
     * Get the last synced difficulty on the client.
     * Defaults to {@link DifficultyType#NORMAL} before the first sync.
     */
    public static ResourceLocation getClientDifficulty() {
        return clientDifficulty;
    }

    /**
     * Internal: update the client-side cached difficulty from a sync packet.
     * Called by {@link org.bytechen.infcore.core.client.ClientPacketHandlers}.
     */
    public static void setClientDifficulty(ResourceLocation difficulty) {
        clientDifficulty = difficulty;
        Infcore.LOGGER.debug("Client difficulty synced: {}", difficulty);
    }

    // ==================== Internal ====================

    private static GlobalDifficultyData getOrCreate(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                GlobalDifficultyData::load, GlobalDifficultyData::new, DATA_NAME);
    }
}
