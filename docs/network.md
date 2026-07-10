# 网络系统

框架在 Forge `SimpleChannel` 之上封装了一层轻量网络层，负责能力数据的 S2C 同步。

## 核心类

| 类 | 位置 | 职责 |
|---|---|---|
| `NetworkHelper` | `core.network` | 频道注册 + 包发送辅助方法 |
| `PacketSyncCapability` | `core.network` | S2C 能力同步数据包 |
| `ClientPacketHandlers` | `core.client` | 客户端包处理器 |

## 频道信息

- 频道名：`infcore:main`
- 协议版本：`1.0`
- 当前注册包：`PacketSyncCapability` (id=0)

## 发送 API

```java
// 发送到指定玩家
NetworkHelper.sendToPlayer(serverPlayer, packet);

// 发送到所有客户端
NetworkHelper.sendToAllClients(packet);

// 发送到追踪指定实体的客户端
NetworkHelper.sendToClient(entity, packet);

// 发送到服务端
NetworkHelper.sendToServer(packet);
```

## 自动同步

框架的能力系统（`CapabilityProvider`）在调用 `markSyncDataDirty()` 时**自动**构造 `PacketSyncCapability` 并发送：

```
KillCountCapability.markSyncDataDirty()
  → CapabilityProvider.sync()       // syncCallback
  → new PacketSyncCapability(entityId, capId, syncData)
  → NetworkHelper.sendToClient(entity, packet)
```

下游模组**无需手动调用网络 API**，只要通过 `setKillCount`/`addKillCount` 修改数据即可自动同步。

## 扩展：注册自定义包

```java
// 在 NetworkHelper.register() 之后注册新包
NetworkHelper.NETWORK.registerMessage(
    packetId++, MyCustomPacket.class,
    MyCustomPacket::encode,
    MyCustomPacket::decode,
    MyCustomPacket::handle
);
```
