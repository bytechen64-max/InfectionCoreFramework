# 架构概览

## 包结构

```
org.bytechen.infcore/
├── api/                         # 暴露层 —— 下游模组仅依赖此层
│   ├── IKillCounter.java
│   └── event/
│       ├── EntityEvolveEvent.java
│       ├── KillCountAddEvent.java
│       └── KillCountChangeEvent.java
│
└── core/                        # 逻辑层 —— 框架内部实现
    ├── Infcore.java             # 主类，注册网络 + Forge 事件
    ├── capability/              # 能力系统
    │   ├── IModCapability.java
    │   ├── AbstractCapability.java
    │   ├── CapabilityProvider.java
    │   ├── KillCountCapability.java
    │   └── CapabilityRegistry.java
    ├── client/                  # 客户端专有代码（@OnlyIn(Dist.CLIENT)）
    │   └── ClientPacketHandlers.java
    ├── datagen/                 # 数据生成
    │   ├── DataGenEvent.java
    │   └── EvolutionDataProvider.java
    ├── event/                   # Forge 事件订阅 + 处理逻辑
    │   ├── InfCoreEventHandler.java
    │   └── InfCoreEventHelpers.java
    ├── evolution/               # 进化引擎
    │   ├── EvolutionData.java
    │   ├── EvolutionEntry.java
    │   ├── EvolutionTarget.java
    │   └── EvolutionManager.java
    └── network/                 # 网络层
        ├── NetworkHelper.java
        └── PacketSyncCapability.java
```

## 数据流全景

```
┌─────────────────────┐     ┌──────────────────────┐
│  EvolutionManager   │     │  Capability 系统     │
│  (进化引擎)          │     │  (每实体数据)         │
│                     │     │                      │
│  applyEvolution()   │     │  KillCountCapability │
│  hasEvolveRule()    │     │  markSyncDataDirty() │
│  classpath 扫描     │     │  writeSyncData()     │
│       │              │     │       │               │
│       ▼              │     │       ▼               │
│  EntityEvolveEvent   │     │  PacketSyncCapability │
└─────────────────────┘     │       │               │
                             │       ▼               │
                             │  NetworkHelper        │
                             │  sendToClient()       │
                             │       │               │
                             │       ▼               │
                             │  ClientPacketHandlers │
                             │  handleSyncCapability()│
                             └──────────────────────┘

┌─────────────────────────────────────────────────────┐
│  Forge 事件流                                       │
│                                                     │
│  LivingDeathEvent                                   │
│    → InfCoreEventHandler                            │
│    → 检查 killer instanceof IKillCounter            │
│    → killCounter.onKilledEntity(victim)             │
│    → addKillCount(1) → KillCountAddEvent            │
│                      → KillCountChangeEvent         │
│                                                     │
│  AttachCapabilitiesEvent<Entity>                    │
│    → 检测 IKillCounter → 附加 KillCountCapability   │
└─────────────────────────────────────────────────────┘
```

## 依赖方向

```
api  ←──  core  ←── 下游模组
 ↑                      │
 └──────────────────────┘
  下游模组只 import api 包中的接口和事件
  框架通过 Forge 能力 + 事件总线松耦合
```
