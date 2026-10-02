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

## 日志分级与降噪

方块扩散（`BlockSpreadManager`）和实体进化（`EvolutionManager`）都是**按方块 / 按实体**触发的高频事件，
感染爆发时每秒可能发生成百上千次。如果逐条打 DEBUG，一次会话就能把 `logs/debug.log` 刷到 MB 级
（实测其中约 74% 都是扩散明细），所以框架里统一用 `core/util/ThrottledLogger` 处理这两类日志：

| 级别 | 内容 | 何时可见 |
| --- | --- | --- |
| TRACE | 逐条明细：`'minecraft:stone' spread to 'hall:hall_stone' at ...` | 默认；需要 log4j 把 `org.bytechen.infcore` 开到 TRACE |
| DEBUG | 逐条明细（与 TRACE 同一内容） | 启动参数加 `-Dinfcore.verbose=true` |
| DEBUG | 聚合摘要：`BlockSpreadManager: 512 event(s) since the last summary (30s window)` | 每 30 秒最多一条；关服时由 `ThrottledLogger.flushAll()` 补一条收尾 |

可用的启动参数：

| 参数 | 作用 |
| --- | --- |
| `-Dinfcore.verbose=true` | 把逐条明细提升到 DEBUG，直接在控制台看每一次扩散 / 进化 |
| `-Dinfcore.log.interval=<毫秒>` | 聚合窗口长度，默认 30000；设为 `0` 则完全不输出聚合摘要（想彻底零噪音时用） |

约定：

- **新增高频日志**一律走 `ThrottledLogger`，不要直接写 `LOGGER.debug`。
- **启动期扫描日志**（`scanning mod ...` / `loading ...`）数量与模组数、数据文件数成正比，是一次性输出，
  保持 DEBUG；但**逐条目**的 `parsed [type] src -> N targets` 降为 TRACE。
- 错误与异常仍然用 `LOGGER.error` / `LOGGER.warn`，不受节流影响。

