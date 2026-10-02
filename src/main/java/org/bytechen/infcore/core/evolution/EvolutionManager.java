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
import org.bytechen.infcore.core.util.ThrottledLogger;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.DoublePredicate;
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
 *   <li>{@link #registerFallback(ResourceLocation, EntityType, int, DoublePredicate)} —— 注册感染兜底档位</li>
 *   <li>{@link #hasEvolveRule(LivingEntity)} —— 检查实体是否存在任何进化规则</li>
 *   <li>{@link #hasEvolveRule(EntityType, ResourceLocation)} —— 检查指定类型和实体类型是否有规则</li>
 * </ul>
 * <p>
 * 感染兜底形态（{@link InfectionFallback}）：当感染作用于一个没有对应感染形态的生物时，
 * 按注册顺序取第一个满足碰撞体积（宽 × 高）条件的档位，把生物替换成若干目标实体。
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

    /**
     * type -> 感染兜底形态（程序注册的多档位，按注册顺序判定）
     */
    private static final Map<ResourceLocation, List<InfectionFallback>> FALLBACK_MAP = new LinkedHashMap<>();

    /**
     * 进化明细日志节流器：逐实体明细默认进 TRACE，每 30 秒最多汇总一条 DEBUG。
     * 大规模感染时进化会成片触发，逐条打印会刷爆 debug.log。
     */
    private static final ThrottledLogger EVOLUTION_LOG =
            new ThrottledLogger(Infcore.LOGGER, "EvolutionManager");

    /**
     * 兜底转化的生命上限门槛：<b>生命上限</b>低于该值的弱小生物不感染，按正常死亡处理。
     * <p>
     * 只作用于「没有对应感染形态」的兜底路径 —— 在进化表里有专属形态的生物（玩家、骷髅……）
     * 不受此门槛影响。
     * <p>
     * 注意：判定发生在击杀事件里，此时生物的当前生命值已经是 0，所以这里比的是生命上限。
     */
    public static final float FALLBACK_MIN_MAX_HEALTH = 5.0F;

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

    // ==================== 感染兜底形态 ====================

    /**
     * 注册某种感染的一个兜底档位。
     * <p>
     * 感染流程中，如果生物<b>没有对应的感染形态</b>（进化表里没有它的规则），
     * 就按注册顺序检查该感染的所有兜底档位，<b>第一个</b>碰撞体积
     * （{@code 宽 × 高}）满足条件的档位生效：把生物替换成 {@code count} 个 {@code target}。
     * 所有档位都不满足时生物保持原样。
     * <p>
     * 兜底档位是程序注册的，不参与 JSON 数据扫描，也不会被 {@link #forceReload()} 清除。
     *
     * @param type            感染类型，与 {@link #applyEvolution} 使用的类型一致
     * @param target          兜底转化的目标实体类型
     * @param count           一次转化生成的个数（小于 1 时按 1 处理）
     * @param volumeCondition 碰撞体积（{@code 宽 × 高}）需要满足的条件，{@code null} 表示不限制
     * @see InfectionFallback
     */
    public static void registerFallback(ResourceLocation type, EntityType<?> target, int count,
                                        DoublePredicate volumeCondition) {
        if (type == null || target == null) return;
        InfectionFallback tier = new InfectionFallback(target, count, volumeCondition);
        FALLBACK_MAP.computeIfAbsent(type, k -> new ArrayList<>()).add(tier);
        Infcore.LOGGER.debug("EvolutionManager: registered fallback tier for '{}' -> '{}' x{}",
                type, ForgeRegistries.ENTITY_TYPES.getKey(target), tier.count());
    }

    /**
     * 移除某种感染的所有兜底档位。
     */
    public static void clearFallback(ResourceLocation type) {
        if (type == null) return;
        FALLBACK_MAP.remove(type);
    }

    /**
     * 移除所有已注册的兜底档位。
     */
    public static void clearFallbacks() {
        FALLBACK_MAP.clear();
    }

    /**
     * 获取某种感染已注册的兜底档位（按判定顺序排列），未注册时返回空列表。
     */
    public static List<InfectionFallback> getFallbacks(ResourceLocation type) {
        if (type == null) return List.of();
        return List.copyOf(FALLBACK_MAP.getOrDefault(type, List.of()));
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

        ResourceLocation sourceKey = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());

        Map<ResourceLocation, EvolutionEntry> typeMap = EVOLUTION_MAP.get(evolutionType);
        EvolutionEntry entry = typeMap == null ? null : typeMap.get(sourceKey);

        // 没有对应感染形态 → 交给感染兜底形态判定
        if (entry == null || entry.results() == null || entry.results().isEmpty()) {
            return doFallbackTransform(level, entity, evolutionType);
        }

        EvolutionTarget chosen = pickWeighted(entry.results(), level.random);
        if (chosen == null) return doFallbackTransform(level, entity, evolutionType);

        ResourceLocation targetKey = ResourceLocation.tryParse(chosen.target());
        if (targetKey == null) return doFallbackTransform(level, entity, evolutionType);

        EntityType<?> targetType = ForgeRegistries.ENTITY_TYPES.getValue(targetKey);
        if (targetType == null) {
            Infcore.LOGGER.warn("EvolutionManager: unknown target entity type '{}'", chosen.target());
            return doFallbackTransform(level, entity, evolutionType);
        }

        if (!replaceEntity(level, entity, targetType, 1, entry.keepNbt(), entry.keepEquipment())) {
            return false;
        }

        EVOLUTION_LOG.record("'{}' evolved into '{}' (type={})",
                sourceKey, chosen.target(), evolutionType);
        return true;
    }

    /**
     * 感染兜底形态判定。
     * <p>
     * 该生物<b>没有对应的感染形态</b>（进化表里查不到它的规则）时，按注册顺序检查
     * 该感染的所有兜底档位，取第一个满足碰撞体积（{@code 宽 × 高}）条件的档位，
     * 把生物替换成 {@code count} 个目标实体。
     * <p>
     * 已经是该感染任一兜底形态的生物不会被再次兜底转化；
     * 生命上限低于 {@link #FALLBACK_MIN_MAX_HEALTH} 的弱小生物也不转化，让它正常死亡。
     *
     * @return 是否成功转化
     */
    private static boolean doFallbackTransform(ServerLevel level, LivingEntity entity, ResourceLocation evolutionType) {
        List<InfectionFallback> tiers = FALLBACK_MAP.get(evolutionType);
        if (tiers == null || tiers.isEmpty()) return false;

        // 已经是兜底形态本身 → 不再兜底转化
        for (InfectionFallback tier : tiers) {
            if (tier.target() != null && entity.getType() == tier.target()) return false;
        }

        ResourceLocation sourceKey = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());

        // 生命上限太低的弱小生物（小鸡、兔子、鱼这类）不感染，直接正常死亡
        if (entity.getMaxHealth() < FALLBACK_MIN_MAX_HEALTH) {
            EVOLUTION_LOG.record("'{}' has no '{}' form but max health {} < {}, left to die normally",
                    sourceKey, evolutionType, entity.getMaxHealth(), FALLBACK_MIN_MAX_HEALTH);
            return false;
        }

        double volume = entity.getBbWidth() * entity.getBbHeight();

        for (InfectionFallback tier : tiers) {
            if (!tier.matches(volume)) continue;

            EntityType<?> targetType = tier.target();
            if (targetType == null) continue;

            if (!replaceEntity(level, entity, targetType, tier.count(), false, false)) {
                return false;
            }

            EVOLUTION_LOG.record("'{}' has no '{}' form, fell back to '{}' x{} (volume={})",
                    sourceKey, evolutionType, ForgeRegistries.ENTITY_TYPES.getKey(targetType), tier.count(), volume);
            return true;
        }

        EVOLUTION_LOG.record("'{}' has no '{}' form and volume {} matches no fallback tier",
                sourceKey, evolutionType, volume);
        return false;
    }

    /**
     * 用目标类型的实体替换源实体（复制位置、朝向、运动状态与自定义名称），并触发
     * {@link EntityEvolveEvent}。
     * <p>
     * {@code count > 1} 时，第一个目标出现在源实体的原位，其余目标围绕源位置分散开，
     * 自定义名称、完整 NBT 与装备只保留给第一个目标。
     *
     * @param count         生成个数（小于 1 时按 1 处理）
     * @param keepNbt       是否保留完整 NBT
     * @param keepEquipment 是否保留装备
     * @return 是否成功替换
     */
    private static boolean replaceEntity(ServerLevel level, LivingEntity entity, EntityType<?> targetType, int count,
                                         boolean keepNbt, boolean keepEquipment) {
        if (count < 1) count = 1;
        double splitRadius = Math.max(0.75D, targetType.getDimensions().width * 0.8D);

        List<Entity> spawned = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Entity newEntity = targetType.create(level);
            if (newEntity == null) break;

            // 复制位置和运动状态：第一个保持原位，其余围绕源位置分散
            if (i == 0) {
                newEntity.moveTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
            } else {
                double angle = (Math.PI * 2.0D / count) * i;
                double offsetX = Math.cos(angle) * splitRadius;
                double offsetZ = Math.sin(angle) * splitRadius;
                newEntity.moveTo(entity.getX() + offsetX, entity.getY(), entity.getZ() + offsetZ,
                        entity.getYRot(), entity.getXRot());
                // 分散点被方块占住时退回源位置（避免卡在墙里窒息）
                if (level.getBlockCollisions(newEntity, newEntity.getBoundingBox()).iterator().hasNext()) {
                    newEntity.moveTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
                }
            }
            newEntity.setDeltaMovement(entity.getDeltaMovement());
            newEntity.setYHeadRot(entity.getYHeadRot());
            newEntity.setYBodyRot(entity.yBodyRot);

            // 复制自定义名称（只给第一个，避免同名分身）
            if (i == 0 && entity.hasCustomName()) {
                newEntity.setCustomName(entity.getCustomName());
                newEntity.setCustomNameVisible(entity.isCustomNameVisible());
            }

            // 根据配置决定是否保留 NBT / 装备（只对第一个生效）
            if (keepNbt && i == 0) {
                copyFullNbt(entity, newEntity);
            } else {
                copyBasicNbt(entity, newEntity);
            }

            if (keepEquipment && i == 0 && entity instanceof Mob srcMob && newEntity instanceof Mob dstMob) {
                copyEquipment(srcMob, dstMob);
            }

            spawned.add(newEntity);
        }

        if (spawned.isEmpty()) return false;

        // 移除源实体
        if (!(entity instanceof Player)) {
            entity.discard();
        }

        for (Entity newEntity : spawned) {
            level.addFreshEntity(newEntity);

            // 粒子效果
            level.sendParticles(ParticleTypes.EXPLOSION,
                    newEntity.getX(), newEntity.getY() + newEntity.getBbHeight() / 2.0, newEntity.getZ(),
                    1, 0, 0, 0, 0.05);

            // 触发进化事件
            if (newEntity instanceof LivingEntity livingResult) {
                MinecraftForge.EVENT_BUS.post(new EntityEvolveEvent(level, entity, livingResult));
            }
        }
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
                Infcore.LOGGER.trace("EvolutionManager: parsed [{}] {} -> {} targets",
                        entry.type(), srcKey, entry.results().size());
            }
        } catch (Exception e) {
            Infcore.LOGGER.error("EvolutionManager: failed to parse evolution data", e);
        }
    }
}
