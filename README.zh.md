# Alias+

[English](./README.md) | **中文**

基于 Nukkit-MOT 核心打造的 Minecraft 基岩版小号检测插件。

**优势：**

🔍
**独特检测**
我们拥有比同类插件更多的检测方式，一般的 Spoofer 不再无解。

🎯
**高准确度**
检测不依赖 IP 等非唯一证据，结果可直接用于封禁。

🤫
**静默检测**
检测几乎完全静默，并支持延迟惩罚，开小号的人根本不知道自己为什么被封。

🔗
**开发接口**
我们提供查询与惩罚接口，可一键封禁所有小号。

## 检测维度

Alias+ 目前检测：

- `DeviceId`
- `ClientRandomId`
- `SelfSignedId`（目前可能已失效）
- `NetworkID`（仅限 NetherNet 客户端）
- 资源包缓存
- Blob Cache

**使用：**

在 `server.properties` 文件中启用 `force-resources`。
在 `nukkit-mot.yml` 文件中启用 `nethernet`。

```
/alias <名称/uuid/xuid>
```

输出示例：

```
--ExampleAcc's accounts (xuid: 123456)--
DeviceId: ExampleAcc
ClientRandomId: ExampleAcc
NetworkID: ExampleAcc
ResPackCache: ExampleAcc
BlobCache: ExampleAcc
```

## 配置（`config.yml`）

所有选项在首次启动时自动生成默认值，以下均为可选项。

| 选项 | 默认值 | 说明 |
|---|---|---|
| `packcache-enabled` | `true` | 启用资源包缓存检测维度 |
| `blobcache-enabled` | `true` | 启用 BlobCache 检测维度 |
| `networkid-enabled` | `true` | 启用 NetherNet NetworkID 检测维度（仅限 NetherNet/WebRTC 客户端；RakNet 连接不携带 NetworkID，会静默跳过） |
| `strictmode` | `true` | 关联方向。`true` = 单向（只显示谁缓存/命中了目标玩家的指纹）；`false` = 双向（同时显示目标玩家缓存/命中了谁的指纹）。⚠️ 双向模式可能因伪造命中，通过惩罚链误伤无辜玩家。 |
| `punish-command` | `ban %player% Alt account detected` | 惩罚命令模板，以控制台身份执行。占位符：`%player%` = 玩家名，`%uuid%` = 玩家 uuid |
| `punish-delay-min` | `5` | 延迟封禁的最小随机延迟（秒，每个账号独立随机） |
| `punish-delay-max` | `30` | 延迟封禁的最大随机延迟（秒，每个账号独立随机） |

**注意事项：**

本项目为 vibe coding 辅助完成的技术演示，可能存在 bug，请勿在成品服务器上使用。

ResPackCache 与 BlobCache 的输出存在被伪造的可能，但必要时可以直接封禁返回的所有账号，因为它们只可能是小号或作弊者。

## 开发者 API

### 接入

在你的插件 `plugin.yml` 中加入 `depend: [Alias+]`，然后获取实例：

```java
AltDetector alias = (AltDetector) getServer().getPluginManager().getPlugin("Alias+");
```

### 惩罚 API

```java
int count = alias.punish(uuid, delayed);
```

- `uuid`：玩家 uuid 字符串（`player.getUniqueId().toString()`）
- `delayed`：`true` 为延迟封禁 / `false` 为立即封禁
- 返回值：加入惩罚队列的账号数量（目标本身 + 所有关联小号，已去重）
- 惩罚命令与延迟范围在 `config.yml` 中配置：`punish-command`（占位符 `%player%`、`%uuid%`）、`punish-delay-min`、`punish-delay-max`（秒）

### 查询 API

```java
Set<String> alts = alias.getRelatedAccounts(uuid);                  // 所有关联小号的 uuid
Map<String, Set<String>> byDim = alias.getRelatedByDimension(uuid); // 按维度分组（device/selfSigned/clientRandom/networkid/pack/blob）
```