# 进化系统

进化系统统一管理实体从一种类型变为另一种类型的规则。不再区分"感染/进化/夺取"三个独立 JSON，而是用 `type` 字段标识。

## 核心类

| 类 | 位置 | 职责 |
|---|---|---|
| `EvolutionManager` | `core.evolution` | 运行时引擎：扫描、查找、执行进化 |
| `EvolutionDataProvider` | `core.datagen` | 数据生成：在构建时生成 JSON 规则文件 |
| `EvolutionData` | `core.evolution` | JSON 顶层结构 |
| `EvolutionEntry` | `core.evolution` | 单条规则（源 → 目标列表） |
| `EvolutionTarget` | `core.evolution` | 目标 + 权重 |

## JSON 格式

```json
{
  "replace": false,
  "entries": [
    {
      "type": "mymod:infection",
      "source": "minecraft:cow",
      "keepEquipment": false,
      "keepNbt": true,
      "results": [
        {"target": "mymod:inf_cow", "weight": 100},
        {"target": "mymod:inf_cow_variant", "weight": 25}
      ]
    }
  ]
}
```

### 字段说明

| 字段 | 类型 | 说明 |
|---|---|---|
| `replace` | boolean | `true` 时先清除同名 source 的已有规则再写入 |
| `type` | ResourceLocation | 进化类型标识，**用你的 modId 命名空间**避免冲突 |
| `source` | ResourceLocation | 源实体注册名 |
| `keepEquipment` | boolean | 进化后是否保留源实体的装备栏物品 |
| `keepNbt` | boolean | 进化后是否完整保留 NBT（包括持久化数据） |
| `results` | list | 目标列表 |
| `results[].target` | ResourceLocation | 目标实体注册名 |
| `results[].weight` | int | 权重，值越大被选中的概率越高 |

## 数据加载

`EvolutionManager` 在首次调用时自动扫描**所有已加载模组**的 `data/<namespace>/infcore_evolution/*.json`：

```
# 模组 A
assets/mymod_a/
data/mymod_a/infcore_evolution/default.json
data/mymod_a/infcore_evolution/boss.json

# 模组 B
data/mymod_b/infcore_evolution/my_rules.json
```

多条规则并存，互不覆盖（除非设置了 `"replace": true`）。

## API

### applyEvolution

```java
boolean success = EvolutionManager.applyEvolution(
    serverLevel, entity,
    ResourceLocation.fromNamespaceAndPath("mymod", "infection")
);
```

- 在**服务端**调用
- 返回 `true` 表示进化成功（找到匹配规则、目标实体创建成功）
- 成功后自动触发 `EntityEvolveEvent`

### hasEvolveRule

```java
// 检查实体是否有任何进化规则
EvolutionManager.hasEvolveRule(entity);

// 检查指定类型 + 实体类型
EvolutionManager.hasEvolveRule(entityType,
    ResourceLocation.fromNamespaceAndPath("mymod", "infection"));
```

### 配置覆盖

```java
// 运行时从配置文件追加规则（无需修改 JSON）
EvolutionManager.mergeOverrides(
    ResourceLocation.fromNamespaceAndPath("mymod", "infection"),
    List.of("minecraft:sheep -> mymod:inf_sheep : 100")
);

// 强制重新扫描所有模组
EvolutionManager.forceReload();
```

## 数据生成

### 实例方法（推荐）

`EvolutionDataProvider` 的实例方法自动用 `modId` 作命名空间前缀：

```java
new EvolutionDataProvider(output, "mymod", "my_rules") {
    @Override
    public List<EvolutionEntry> buildEntries() {
        return List.of(
            // type("infection") → "mymod:infection"
            entry("infection", EntityType.COW, false, true, List.of(
                target(MyEntities.INF_COW.get(), 100),
                target(EntityType.MOOSHROOM, 50)   // 权重 50，概率较低
            )),
            entry("evolution", MyEntities.INF_COW.get(), false, false, List.of(
                target(MyEntities.EVO_COW.get(), 100)
            ))
        );
    }
}
```

### 静态方法

在 `buildEntries()` 外部使用时，需要完整的 type：

```java
EvolutionDataProvider.entryFull("mymod:infection", EntityType.COW, false, true,
    List.of(EvolutionDataProvider.target(EntityType.ZOMBIE, 100)));
```

## EntityEvolveEvent

```java
@SubscribeEvent
public static void onEvolve(EntityEvolveEvent event) {
    ServerLevel level = event.getLevel();
    LivingEntity original = event.getOriginal();   // 进化前的实体
    LivingEntity result = event.getResultEntity();  // 进化后的实体
}
```

- 在 Forge 事件总线上触发
- 进化成功后调用，不可取消

## 多个模组的规则隔离

不同模组只要用各自的 `modId` 作为 type 前缀即可完全隔离：

```
模组A: type="mod_a:infection" → 只能通过 "mod_a:infection" 调用
模组B: type="mod_b:infection" → 只能通过 "mod_b:infection" 调用
```
