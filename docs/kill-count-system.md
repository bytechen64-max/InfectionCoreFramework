# 击杀计数系统

每实体击杀计数，框架自动处理持久化和客户端同步。

## 核心类

| 类 | 位置 | 职责 |
|---|---|---|
| `IKillCounter` | `api` | 接口，实体实现后自动获得计数能力 |
| `KillCountCapability` | `core.capability` | 能力实现：存储、同步、事件触发 |
| `CapabilityRegistry` | `core.capability` | 能力注册中心 |

## 接入

实体实现 `IKillCounter` 即可：

```java
public class InfectedZombie extends Zombie implements IKillCounter {

    // 将方法委托给自动附加的 KillCountCapability
    @Override
    public int getKillCount() {
        return this.getCapability(CapabilityRegistry.KILL_COUNT)
                .map(KillCountCapability::getKillCount).orElse(0);
    }

    @Override
    public void setKillCount(int count) {
        this.getCapability(CapabilityRegistry.KILL_COUNT)
                .ifPresent(kc -> kc.setKillCount(this, count));
    }

    @Override
    public void addKillCount(int amount) {
        this.getCapability(CapabilityRegistry.KILL_COUNT)
                .ifPresent(kc -> kc.addKillCount(this, amount));
    }
}
```

框架自动完成：

| 功能 | 说明 |
|---|---|
| 能力附加 | `AttachCapabilitiesEvent` 检测 `IKillCounter`，自动附加 `KillCountCapability` |
| NBT 持久化 | `serializeNBT`/`deserializeNBT`，世界重载后数据不丢 |
| 客户端同步 | `CapabilityProvider.sync()` 自动通过 `PacketSyncCapability` 发送到追踪的客户端 |
| 自动计数 | 实体死亡时，杀手为 `IKillCounter` 则自动调用 `onKilledEntity()`，默认 `addKillCount(1)` |

## IKillCounter 接口

```java
public interface IKillCounter {
    default boolean killCounterEnabled() { return true; }
    int getKillCount();
    void setKillCount(int count);
    void addKillCount(int amount);

    default void onKilledEntity(Entity entity) {
        addKillCount(1);  // 默认击杀 +1
    }
}
```

`onKilledEntity` 可覆盖以自定义击杀逻辑：

```java
@Override
public void onKilledEntity(Entity victim) {
    if (victim instanceof Player) {
        addKillCount(10);  // 击杀玩家奖励 10 点
    } else {
        super.onKilledEntity(victim);
    }
}
```

## 同步数据流

```
服务端: addKillCount(1)
  → markSyncDataDirty()
  → writeSyncData({"killCount": 新值})
  → CapabilityProvider 构造 PacketSyncCapability
  → NetworkHelper.sendToClient(entity, packet)
  → 所有追踪该实体的客户端收到包
  → ClientPacketHandlers.handleSyncCapability()
  → AbstractCapability.readSyncData()
  → 客户端实体能力数据更新
```

## 事件

### KillCountAddEvent

计数**即将**增加时触发，可取消或修改：

```java
@SubscribeEvent
public static void onKillCountAdd(KillCountAddEvent event) {
    LivingEntity entity = event.getEntity();
    int amount = event.getAmount();

    if (entity.hasEffect(MobEffects.DAMAGE_BOOST)) {
        event.setAmount(amount * 2);  // 力量效果下双倍
    }
}
```

### KillCountChangeEvent

计数**已变更**后触发（不可取消）：

```java
@SubscribeEvent
public static void onKillCountChange(KillCountChangeEvent event) {
    int delta = event.getNewCount() - event.getOldCount();
    // 记录日志、更新 UI 等
}
```

## 从能力中读取（客户端）

```java
entity.getCapability(CapabilityRegistry.KILL_COUNT).ifPresent(kc -> {
    int kills = kc.getKillCount();
    // 渲染击杀计数 HUD
});
```
