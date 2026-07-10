# 快速开始

InfectionCoreFramework 是为 Forge 1.20.1 感染类模组设计的前置框架。

## 添加依赖

**build.gradle:**
```groovy
repositories {
    mavenLocal()
    // 或其他包含 infcore 的仓库
}

dependencies {
    implementation fg.deobf("org.bytechen:infcore:0.0.1")
}
```

**mods.toml 添加依赖声明：**
```toml
[[dependencies.infcore]]
    modId="infcore"
    mandatory=true
    versionRange="[0.0.1,)"
    ordering="NONE"
    side="BOTH"
```

---

## 三大核心系统

| 系统 | 入口 | 用途 |
|---|---|---|
| **进化系统** | `EvolutionManager` | 实体变更控制：感染/进化/夺取 |
| **击杀计数** | `IKillCounter` | 实体击杀计数，框架自动同步 |
| **网络同步** | `NetworkHelper` | 能力数据 C→S 自动同步 |

---

## 5 分钟接入

### 1. 定义进化规则（数据生成）

```java
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class MyDataGen {
    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        PackOutput out = event.getGenerator().getPackOutput();
        event.getGenerator().addProvider(event.includeServer(),
            new EvolutionDataProvider(out, "mymod", "default") {
                @Override
                public List<EvolutionEntry> buildEntries() {
                    return List.of(
                        // type("infection") → "mymod:infection"
                        entry("infection", EntityType.COW, false, true, List.of(
                            target(MyEntities.INF_COW.get(), 100)
                        ))
                    );
                }
            });
    }
}
```

运行 `./gradlew runData`，JSON 生成在 `src/generated/resources/data/mymod/infcore_evolution/default.json`。

### 2. 实体实现 IKillCounter

```java
public class InfectedCow extends Cow implements IKillCounter {
    // 委托给能力（框架自动附加）
    @Override public int getKillCount() {
        return this.getCapability(CapabilityRegistry.KILL_COUNT)
            .map(KillCountCapability::getKillCount).orElse(0);
    }
    @Override public void setKillCount(int count) {
        this.getCapability(CapabilityRegistry.KILL_COUNT)
            .ifPresent(kc -> kc.setKillCount(this, count));
    }
    @Override public void addKillCount(int amount) {
        this.getCapability(CapabilityRegistry.KILL_COUNT)
            .ifPresent(kc -> kc.addKillCount(this, amount));
    }
}
```

只要实现 `IKillCounter`，框架自动完成：
- `KillCountCapability` 附加
- NBT 持久化
- 客户端数据同步
- 击杀时自动 `addKillCount(1)`

### 3. 触发进化

```java
// 用你的 modId 前缀的 type 调用
ResourceLocation type = ResourceLocation.fromNamespaceAndPath("mymod", "infection");
EvolutionManager.applyEvolution(serverLevel, cowEntity, type);
```

### 4. 监听事件

```java
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MyEventHandler {
    @SubscribeEvent
    public static void onEvolve(EntityEvolveEvent event) {
        // 实体进化成功
    }

    @SubscribeEvent
    public static void onKillCountChange(KillCountChangeEvent event) {
        // killCount 从 old 变为 new
    }
}
```
