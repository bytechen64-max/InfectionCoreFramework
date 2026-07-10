package org.bytechen.infcore.core.evolution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.registries.ForgeRegistries;
import org.bytechen.infcore.api.event.EntityEvolveEvent;
import org.bytechen.infcore.core.Infcore;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 进化/感染管理器。
 * <p>
 * 统一管理所有感染类型的进化规则，不再将进化、感染、夺取分成多套 JSON 配置。
 * 每条规则通过 {@code type} 字段区分感染类型。
 * <p>
 * 数据文件路径：{@code data/<namespace>/infcore_evolution/*.json}
 * <p>
 * 主要 API：
 * <ul>
 *   <li>{@link #applyEvolution(ServerLevel, LivingEntity, ResourceLocation)} —— 对实体应用指定类型的进化</li>
 *   <li>{@link #hasEvolveRule(LivingEntity)} —— 检查实体是否存在任何进化规则</li>
 *   <li>{@link #hasEvolveRule(EntityType, ResourceLocation)} —— 检查指定类型和实体类型是否有规则</li>
 * </ul>
 */
public final class EvolutionManager {

    public static final Gson GSON = new GsonBuilder().create();
    private static final String EVOLUTION_DATA_PATH = "infcore_evolution";
    private static final Pattern CONFIG_PATTERN =
            Pattern.compile("^(.+)\\s*->\\s*(.+)\\s*:\\s*(\\d+)\\s*$");

    /**
     * type -> source -> EvolutionEntry
     */
    private static final Map<ResourceLocation, Map<ResourceLocation, EvolutionEntry>> EVOLUTION_MAP = new LinkedHashMap<>();
    private static boolean loaded;

    private EvolutionManager() {}

    // ==================== 公共 API ====================

    /**
     * 对实体应用指定类型的进化。
     *
     * @param level  服务端世界
     * @param entity 要进化的实体
     * @param type   进化类型的 ResourceLocation（如 {@code infcore:infection}）
     * @return 是否成功进化
     */
    public static boolean applyEvolution(ServerLevel level, LivingEntity entity, ResourceLocation type) {
        if (level == null || type == null) return false;
        ensureLoaded();
        return doTransform(level, entity, type);
    }

    /**
     * 检查实体是否存在任何进化规则（不限类型）。
     *
     * @param entity 要检查的实体
     * @return 是否存在进化规则
     */
    public static boolean hasEvolveRule(LivingEntity entity) {
        return hasEvolveRule(entity.getType());
    }

    /**
     * 检查指定实体类型是否存在任何进化规则。
     */
    public static boolean hasEvolveRule(EntityType<?> type) {
        ensureLoaded();
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
        for (Map<ResourceLocation, EvolutionEntry> typeMap : EVOLUTION_MAP.values()) {
            if (typeMap.containsKey(key)) return true;
        }
        return false;
    }

    /**
     * 检查指定类型和实体类型是否存在进化规则。
     */
    public static boolean hasEvolveRule(EntityType<?> entityType, ResourceLocation evolutionType) {
        ensureLoaded();
        Map<ResourceLocation, EvolutionEntry> typeMap = EVOLUTION_MAP.get(evolutionType);
        if (typeMap == null) return false;
        return typeMap.containsKey(ForgeRegistries.ENTITY_TYPES.getKey(entityType));
    }

    /**
     * 获取指定实体类型和进化类型的可能目标列表。
     */
    @Nullable
    public static List<EvolutionTarget> getEvolutionTargets(EntityType<?> entityType, ResourceLocation evolutionType) {
        ensureLoaded();
        Map<ResourceLocation, EvolutionEntry> typeMap = EVOLUTION_MAP.get(evolutionType);
        if (typeMap == null) return null;
        EvolutionEntry entry = typeMap.get(ForgeRegistries.ENTITY_TYPES.getKey(entityType));
        return entry != null ? entry.results() : null;
    }

    /**
     * 获取已加载的进化条目总数（用于调试）。
     */
    public static int getEntryCount() {
        ensureLoaded();
        int count = 0;
        for (Map<ResourceLocation, EvolutionEntry> typeMap : EVOLUTION_MAP.values()) {
            count += typeMap.size();
        }
        return count;
    }

    // ==================== 配置重载 ====================

    /**
     * 强制重新扫描所有模组的进化数据。
     */
    public static void forceReload() {
        EVOLUTION_MAP.clear();
        loaded = false;
        ensureLoaded();
    }

    /**
     * 从配置字符串合并进化规则。
     * 格式：{@code sourceRegistryName -> targetRegistryName : weight}
     */
    public static void mergeOverrides(ResourceLocation type, List<String> overrides) {
        if (overrides == null || type == null) return;
        ensureLoaded();
        Map<ResourceLocation, EvolutionEntry> typeMap =
                EVOLUTION_MAP.computeIfAbsent(type, k -> new LinkedHashMap<>());

        for (String line : overrides) {
            Matcher m = CONFIG_PATTERN.matcher(line.trim());
            if (!m.matches()) {
                Infcore.LOGGER.warn("EvolutionManager: invalid override '{}'", line);
                continue;
            }
            ResourceLocation src = ResourceLocation.tryParse(m.group(1).trim());
            ResourceLocation target = ResourceLocation.tryParse(m.group(2).trim());
            int weight;
            try {
                weight = Integer.parseInt(m.group(3).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (src == null || target == null || weight <= 0) continue;

            List<EvolutionTarget> results = new ArrayList<>();
            results.add(new EvolutionTarget(target.toString(), weight));
            EvolutionEntry entry = new EvolutionEntry(type.toString(), src.toString(), false, false, results);
            typeMap.put(src, entry);
        }
    }

    // ==================== 内部核心 ====================

    private static boolean doTransform(ServerLevel level, LivingEntity entity, ResourceLocation evolutionType) {
        if (!entity.isAlive()) return false;

        ResourceLocation sourceKey = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        Infcore.LOGGER.debug("EvolutionManager: applyEvolution type='{}' for '{}'", evolutionType, sourceKey);

        Map<ResourceLocation, EvolutionEntry> typeMap = EVOLUTION_MAP.get(evolutionType);
        if (typeMap == null) {
            Infcore.LOGGER.debug("EvolutionManager: no entries for type '{}'", evolutionType);
            return false;
        }

        EvolutionEntry entry = typeMap.get(sourceKey);
        if (entry == null || entry.results() == null || entry.results().isEmpty()) {
            Infcore.LOGGER.debug("EvolutionManager: no targets for '{}' under type '{}'", sourceKey, evolutionType);
            return false;
        }

        EvolutionTarget chosen = pickWeighted(entry.results(), level.random);
        if (chosen == null) return false;

        ResourceLocation targetKey = ResourceLocation.tryParse(chosen.target());
        if (targetKey == null) return false;

        EntityType<?> targetType = ForgeRegistries.ENTITY_TYPES.getValue(targetKey);
        if (targetType == null) {
            Infcore.LOGGER.warn("EvolutionManager: unknown target entity type '{}'", chosen.target());
            return false;
        }

        Entity newEntity = targetType.create(level);
        if (newEntity == null) return false;

        // 复制位置和运动状态
        newEntity.moveTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
        newEntity.setDeltaMovement(entity.getDeltaMovement());
        newEntity.setYHeadRot(entity.getYHeadRot());
        newEntity.setYBodyRot(entity.yBodyRot);

        // 复制自定义名称
        if (entity.hasCustomName()) {
            newEntity.setCustomName(entity.getCustomName());
            newEntity.setCustomNameVisible(entity.isCustomNameVisible());
        }

        // 根据配置决定是否保留 NBT
        if (entry.keepNbt()) {
            copyFullNbt(entity, newEntity);
        } else {
            copyBasicNbt(entity, newEntity);
        }

        // 根据配置决定是否保留装备
        if (entry.keepEquipment() && entity instanceof Mob srcMob && newEntity instanceof Mob dstMob) {
            copyEquipment(srcMob, dstMob);
        }

        // 移除源实体
        if (!(entity instanceof Player)) {
            entity.discard();
        }
        level.addFreshEntity(newEntity);

        // 粒子效果
        level.sendParticles(ParticleTypes.EXPLOSION,
                entity.getX(), entity.getY() + entity.getBbHeight() / 2.0, entity.getZ(),
                1, 0, 0, 0, 0.05);

        // 触发进化事件
        if (newEntity instanceof LivingEntity livingResult) {
            MinecraftForge.EVENT_BUS.post(new EntityEvolveEvent(level, entity, livingResult));
        }

        Infcore.LOGGER.debug("EvolutionManager: '{}' evolved into '{}' (type={})",
                sourceKey, chosen.target(), evolutionType);
        return true;
    }

    // ==================== NBT 复制 ====================

    private static void copyBasicNbt(LivingEntity from, Entity to) {
        if (from instanceof Mob fromMob && to instanceof Mob toMob) {
            if (fromMob.isPersistenceRequired()) {
                toMob.setPersistenceRequired();
            }
            toMob.setLeftHanded(fromMob.isLeftHanded());
        }
        to.invulnerableTime = from.invulnerableTime;
    }

    private static void copyFullNbt(LivingEntity from, Entity to) {
        copyBasicNbt(from, to);
        CompoundTag tag = new CompoundTag();
        from.saveWithoutId(tag);
        to.load(tag);
        // 恢复实体类型相关信息（saveWithoutId 不会保存这个）
        to.setPos(from.getX(), from.getY(), from.getZ());
    }

    private static void copyEquipment(Mob from, Mob to) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = from.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                to.setItemSlot(slot, stack.copy());
            }
        }
    }

    // ==================== 权重选择 ====================

    @Nullable
    private static EvolutionTarget pickWeighted(List<EvolutionTarget> targets, RandomSource random) {
        int totalWeight = 0;
        for (EvolutionTarget t : targets) {
            totalWeight += t.weight();
        }
        if (totalWeight <= 0) return null;

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (EvolutionTarget t : targets) {
            cumulative += t.weight();
            if (roll < cumulative) return t;
        }
        return targets.get(targets.size() - 1);
    }

    // ==================== classpath 扫描 ====================

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Infcore.LOGGER.info("EvolutionManager: scanning classpath for evolution data...");
        scanClasspath();
        int count = getEntryCount();
        Infcore.LOGGER.info("EvolutionManager: scan complete, {} evolution entries loaded across {} types",
                count, EVOLUTION_MAP.size());
    }

    private static void scanClasspath() {
        try {
            for (IModInfo modInfo : ModList.get().getMods()) {
                Path modPath = modInfo.getOwningFile().getFile().getFilePath();
                Infcore.LOGGER.debug("EvolutionManager: scanning mod '{}' at {}", modInfo.getModId(), modPath);

                if (Files.isDirectory(modPath)) {
                    scanModDirectory(modPath, modInfo.getModId());
                } else if (modPath.toString().endsWith(".jar")) {
                    scanModJar(modPath, modInfo.getModId());
                }
            }
        } catch (Exception e) {
            Infcore.LOGGER.error("EvolutionManager: failed to scan mods", e);
        }
    }

    private static void scanModDirectory(Path modPath, String modId) {
        Path dataDir = modPath.resolve("data");
        if (!Files.isDirectory(dataDir)) {
            Infcore.LOGGER.debug("EvolutionManager: mod '{}' has no data/ directory", modId);
            return;
        }

        try (Stream<Path> nsDirs = Files.list(dataDir)) {
            for (Path nsDir : nsDirs.toList()) {
                if (!Files.isDirectory(nsDir)) continue;
                Path evoDir = nsDir.resolve(EVOLUTION_DATA_PATH);
                if (Files.isDirectory(evoDir)) {
                    loadJsonFromDir(evoDir);
                }
            }
        } catch (Exception e) {
            Infcore.LOGGER.error("EvolutionManager: failed to scan mod directory {}", modPath, e);
        }
    }

    private static void scanModJar(Path jarPath, String modId) {
        Infcore.LOGGER.debug("EvolutionManager: scanning jar {}", jarPath);

        try (FileSystem fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            Path dataDir = fs.getPath("data");
            if (!Files.isDirectory(dataDir)) {
                Infcore.LOGGER.debug("EvolutionManager: jar '{}' has no data/ directory", modId);
                return;
            }

            try (Stream<Path> nsDirs = Files.list(dataDir)) {
                for (Path nsDir : nsDirs.toList()) {
                    if (!Files.isDirectory(nsDir)) continue;
                    Path evoDir = nsDir.resolve(EVOLUTION_DATA_PATH);
                    if (Files.isDirectory(evoDir)) {
                        loadJsonFromDir(evoDir);
                    }
                }
            }
        } catch (Exception e) {
            Infcore.LOGGER.error("EvolutionManager: failed to scan jar {}", jarPath, e);
        }
    }

    private static void loadJsonFromDir(Path dir) {
        Infcore.LOGGER.debug("EvolutionManager: found dir: {}", dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json")) continue;
                Infcore.LOGGER.debug("EvolutionManager: loading {}", name);
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    parseAndMerge(reader);
                }
            }
        } catch (Exception e) {
            Infcore.LOGGER.error("EvolutionManager: failed to read dir {}", dir, e);
        }
    }

    private static void parseAndMerge(Reader reader) {
        try {
            EvolutionData data = GSON.fromJson(reader, EvolutionData.class);
            if (data == null || data.entries() == null) return;

            for (EvolutionEntry entry : data.entries()) {
                if (entry.source() == null || entry.results() == null || entry.type() == null) continue;

                ResourceLocation typeKey = ResourceLocation.tryParse(entry.type());
                ResourceLocation srcKey = ResourceLocation.tryParse(entry.source());

                if (typeKey == null || srcKey == null) {
                    Infcore.LOGGER.warn("EvolutionManager: invalid type='{}' or source='{}'", entry.type(), entry.source());
                    continue;
                }

                Map<ResourceLocation, EvolutionEntry> typeMap =
                        EVOLUTION_MAP.computeIfAbsent(typeKey, k -> new LinkedHashMap<>());

                if (data.replace()) {
                    typeMap.remove(srcKey);
                }

                typeMap.put(srcKey, entry);
                Infcore.LOGGER.debug("EvolutionManager: parsed [{}] {} -> {} targets",
                        entry.type(), srcKey, entry.results().size());
            }
        } catch (Exception e) {
            Infcore.LOGGER.error("EvolutionManager: failed to parse evolution data", e);
        }
    }
}
