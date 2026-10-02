package org.bytechen.infcore.core.blockspread;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.registries.ForgeRegistries;
import org.bytechen.infcore.api.block.ISpreadBlock;
import org.bytechen.infcore.api.event.BlockSpreadEvent;
import org.bytechen.infcore.core.Infcore;
import org.bytechen.infcore.core.util.ThrottledLogger;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 方块扩散管理器。
 * <p>
 * 统一管理所有方块扩散规则，与 {@code EvolutionManager} 共用相同的 classpath 扫描模式。
 * 每条规则通过 {@code type} 字段区分扩散类型。
 * <p>
 * 数据文件路径：{@code data/<namespace>/infcore_blockspread/*.json}
 * <p>
 * 主要 API：
 * <ul>
 *   <li>{@link #applySpread(Level, BlockPos, ResourceLocation)} —— 对方块应用指定类型的扩散</li>
 *   <li>{@link #applySpread(Level, BlockPos, BlockState, ResourceLocation)} —— 对方块状态应用扩散</li>
 *   <li>{@link #applySpread(Level, BlockPos, ISpreadBlock)} —— 对实现 ISpreadBlock 的方块自动获取类型</li>
 *   <li>{@link #hasSpreadRule(BlockState, ResourceLocation)} —— 检查方块状态是否存在扩散规则</li>
 *   <li>{@link #hasSpreadRule(Block, ResourceLocation)} —— 检查方块是否存在扩散规则</li>
 * </ul>
 */
public final class BlockSpreadManager {

    public static final Gson GSON = new GsonBuilder().create();
    private static final String BLOCKSPREAD_DATA_PATH = "infcore_blockspread";

    /**
     * 扩散明细日志节流器：逐方块明细默认进 TRACE，每 30 秒最多汇总一条 DEBUG。
     * 感染爆发时扩散每秒可达上百次，逐条打印会刷爆 debug.log。
     */
    private static final ThrottledLogger SPREAD_LOG =
            new ThrottledLogger(Infcore.LOGGER, "BlockSpreadManager");

    /**
     * type -> sourceBlock -> BlockSpreadEntry
     */
    private static final Map<ResourceLocation, Map<ResourceLocation, BlockSpreadEntry>> SPREAD_MAP = new LinkedHashMap<>();
    private static boolean loaded;

    private BlockSpreadManager() {}

    // ==================== 公共 API ====================

    /**
     * 对方块应用指定类型的扩散。
     *
     * @param level 世界
     * @param pos   要扩散的方块位置
     * @param type  扩散类型的 ResourceLocation（如 {@code hall:spread}）
     * @return 是否成功扩散
     */
    public static boolean applySpread(Level level, BlockPos pos, ResourceLocation type) {
        if (level == null || pos == null || type == null) return false;
        ensureLoaded();
        return doSpread(level, pos, type);
    }

    /**
     * 对方块状态应用指定类型的扩散。避免重复获取 BlockState。
     */
    public static boolean applySpread(Level level, BlockPos pos, BlockState state, ResourceLocation type) {
        if (level == null || pos == null || state == null || type == null) return false;
        ensureLoaded();
        return doSpread(level, pos, state, type);
    }

    /**
     * 对实现 {@link ISpreadBlock} 接口的方块应用扩散。
     * 自动从方块实例获取扩散类型，无需手动指定。
     *
     * @param level 世界
     * @param pos   要扩散的方块位置
     * @param block 实现了 ISpreadBlock 的方块实例
     * @return 是否成功扩散
     */
    public static boolean applySpread(Level level, BlockPos pos, ISpreadBlock block) {
        if (level == null || pos == null || block == null) return false;
        return applySpread(level, pos, block.getSpreadType());
    }

    /**
     * 检查方块状态是否存在指定类型的扩散规则。
     */
    public static boolean hasSpreadRule(BlockState state, ResourceLocation type) {
        if (state == null || type == null) return false;
        return hasSpreadRule(state.getBlock(), type);
    }

    /**
     * 检查方块是否存在指定类型的扩散规则。
     */
    public static boolean hasSpreadRule(Block block, ResourceLocation type) {
        if (block == null || type == null) return false;
        ensureLoaded();
        Map<ResourceLocation, BlockSpreadEntry> typeMap = SPREAD_MAP.get(type);
        if (typeMap == null) return false;
        return typeMap.containsKey(ForgeRegistries.BLOCKS.getKey(block));
    }

    /**
     * 检查方块状态是否存在任意由 ISpreadBlock 定义的扩散规则。
     * 当 state 的 Block 实现了 ISpreadBlock 时，使用其自身类型查询。
     */
    public static boolean hasSpreadRule(BlockState state) {
        if (state == null) return false;
        if (state.getBlock() instanceof ISpreadBlock spreadBlock) {
            return hasSpreadRule(state, spreadBlock.getSpreadType());
        }
        return false;
    }

    /**
     * 获取指定方块和扩散类型的可能目标列表。
     */
    @Nullable
    public static List<BlockSpreadTarget> getSpreadTargets(Block block, ResourceLocation type) {
        if (block == null || type == null) return null;
        ensureLoaded();
        Map<ResourceLocation, BlockSpreadEntry> typeMap = SPREAD_MAP.get(type);
        if (typeMap == null) return null;
        BlockSpreadEntry entry = typeMap.get(ForgeRegistries.BLOCKS.getKey(block));
        return entry != null ? entry.results() : null;
    }

    /**
     * 获取已加载的扩散条目总数（用于调试）。
     */
    public static int getEntryCount() {
        ensureLoaded();
        int count = 0;
        for (Map<ResourceLocation, BlockSpreadEntry> typeMap : SPREAD_MAP.values()) {
            count += typeMap.size();
        }
        return count;
    }

    // ==================== 配置重载 ====================

    /**
     * 强制重新扫描所有模组的方块扩散数据。
     */
    public static void forceReload() {
        SPREAD_MAP.clear();
        loaded = false;
        ensureLoaded();
    }

    // ==================== 内部核心 ====================

    private static boolean doSpread(Level level, BlockPos pos, ResourceLocation type) {
        BlockState state = level.getBlockState(pos);
        return doSpread(level, pos, state, type);
    }

    private static boolean doSpread(Level level, BlockPos pos, BlockState state, ResourceLocation type) {
        if (state.isAir()) return false;

        ResourceLocation sourceKey = ForgeRegistries.BLOCKS.getKey(state.getBlock());

        Map<ResourceLocation, BlockSpreadEntry> typeMap = SPREAD_MAP.get(type);
        if (typeMap == null) {
            return false;
        }

        BlockSpreadEntry entry = typeMap.get(sourceKey);
        if (entry == null || entry.results() == null || entry.results().isEmpty()) {
            return false;
        }

        BlockSpreadTarget chosen = pickWeighted(entry.results(), level.random);
        if (chosen == null) return false;

        ResourceLocation targetKey = ResourceLocation.tryParse(chosen.target());
        if (targetKey == null) return false;

        Block targetBlock = ForgeRegistries.BLOCKS.getValue(targetKey);
        if (targetBlock == null) {
            Infcore.LOGGER.warn("BlockSpreadManager: unknown target block type '{}'", chosen.target());
            return false;
        }

        BlockState targetState = targetBlock.defaultBlockState();

        // 扩散前事件（可取消）
        BlockSpreadEvent.Pre preEvent = new BlockSpreadEvent.Pre(level, pos, state, targetState, type);
        if (MinecraftForge.EVENT_BUS.post(preEvent)) {
            SPREAD_LOG.record("spread cancelled by event at {}", pos);
            return false;
        }

        // 根据配置决定是否掉落资源
        if (entry.dropResources()) {
            Block.dropResources(state, level, pos, null, null, ItemStack.EMPTY);
        }

        level.setBlock(pos, targetState, Block.UPDATE_ALL_IMMEDIATE);

        // 扩散后事件（不可取消）
        MinecraftForge.EVENT_BUS.post(new BlockSpreadEvent.Post(level, pos, state, targetState, type));

        SPREAD_LOG.record("'{}' spread to '{}' at {} (type={})",
                sourceKey, chosen.target(), pos, type);
        return true;
    }

    // ==================== 权重选择 ====================

    @Nullable
    private static BlockSpreadTarget pickWeighted(List<BlockSpreadTarget> targets, RandomSource random) {
        int totalWeight = 0;
        for (BlockSpreadTarget t : targets) {
            totalWeight += t.weight();
        }
        if (totalWeight <= 0) return null;

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (BlockSpreadTarget t : targets) {
            cumulative += t.weight();
            if (roll < cumulative) return t;
        }
        return targets.get(targets.size() - 1);
    }

    // ==================== classpath 扫描 ====================

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Infcore.LOGGER.info("BlockSpreadManager: scanning classpath for blockspread data...");
        scanClasspath();
        int count = getEntryCount();
        Infcore.LOGGER.info("BlockSpreadManager: scan complete, {} blockspread entries loaded across {} types",
                count, SPREAD_MAP.size());
    }

    private static void scanClasspath() {
        try {
            for (IModInfo modInfo : ModList.get().getMods()) {
                Path modPath = modInfo.getOwningFile().getFile().getFilePath();
                Infcore.LOGGER.debug("BlockSpreadManager: scanning mod '{}' at {}", modInfo.getModId(), modPath);

                if (Files.isDirectory(modPath)) {
                    scanModDirectory(modPath, modInfo.getModId());
                } else if (modPath.toString().endsWith(".jar")) {
                    scanModJar(modPath, modInfo.getModId());
                }
            }
        } catch (Exception e) {
            Infcore.LOGGER.error("BlockSpreadManager: failed to scan mods", e);
        }
    }

    private static void scanModDirectory(Path modPath, String modId) {
        Path dataDir = modPath.resolve("data");
        if (!Files.isDirectory(dataDir)) {
            Infcore.LOGGER.debug("BlockSpreadManager: mod '{}' has no data/ directory", modId);
            return;
        }

        try (Stream<Path> nsDirs = Files.list(dataDir)) {
            for (Path nsDir : nsDirs.toList()) {
                if (!Files.isDirectory(nsDir)) continue;
                Path spreadDir = nsDir.resolve(BLOCKSPREAD_DATA_PATH);
                if (Files.isDirectory(spreadDir)) {
                    loadJsonFromDir(spreadDir);
                }
            }
        } catch (Exception e) {
            Infcore.LOGGER.error("BlockSpreadManager: failed to scan mod directory {}", modPath, e);
        }
    }

    private static void scanModJar(Path jarPath, String modId) {
        Infcore.LOGGER.debug("BlockSpreadManager: scanning jar {}", jarPath);

        try (FileSystem fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            Path dataDir = fs.getPath("data");
            if (!Files.isDirectory(dataDir)) {
                Infcore.LOGGER.debug("BlockSpreadManager: jar '{}' has no data/ directory", modId);
                return;
            }

            try (Stream<Path> nsDirs = Files.list(dataDir)) {
                for (Path nsDir : nsDirs.toList()) {
                    if (!Files.isDirectory(nsDir)) continue;
                    Path spreadDir = nsDir.resolve(BLOCKSPREAD_DATA_PATH);
                    if (Files.isDirectory(spreadDir)) {
                        loadJsonFromDir(spreadDir);
                    }
                }
            }
        } catch (Exception e) {
            Infcore.LOGGER.error("BlockSpreadManager: failed to scan jar {}", jarPath, e);
        }
    }

    private static void loadJsonFromDir(Path dir) {
        Infcore.LOGGER.debug("BlockSpreadManager: found dir: {}", dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json")) continue;
                Infcore.LOGGER.debug("BlockSpreadManager: loading {}", name);
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    parseAndMerge(reader);
                }
            }
        } catch (Exception e) {
            Infcore.LOGGER.error("BlockSpreadManager: failed to read dir {}", dir, e);
        }
    }

    private static void parseAndMerge(Reader reader) {
        try {
            BlockSpreadData data = GSON.fromJson(reader, BlockSpreadData.class);
            if (data == null || data.entries() == null) return;

            for (BlockSpreadEntry entry : data.entries()) {
                if (entry.source() == null || entry.results() == null || entry.type() == null) continue;

                ResourceLocation typeKey = ResourceLocation.tryParse(entry.type());
                ResourceLocation srcKey = ResourceLocation.tryParse(entry.source());

                if (typeKey == null || srcKey == null) {
                    Infcore.LOGGER.warn("BlockSpreadManager: invalid type='{}' or source='{}'", entry.type(), entry.source());
                    continue;
                }

                Map<ResourceLocation, BlockSpreadEntry> typeMap =
                        SPREAD_MAP.computeIfAbsent(typeKey, k -> new LinkedHashMap<>());

                if (data.replace()) {
                    typeMap.remove(srcKey);
                }

                typeMap.put(srcKey, entry);
                Infcore.LOGGER.trace("BlockSpreadManager: parsed [{}] {} -> {} targets",
                        entry.type(), srcKey, entry.results().size());
            }
        } catch (Exception e) {
            Infcore.LOGGER.error("BlockSpreadManager: failed to parse blockspread data", e);
        }
    }
}
