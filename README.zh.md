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
检测几乎完全静默，开小号的人根本不知道自己为什么被封。

🔗
**开发接口**
我们提供查询与惩罚接口，可一键封禁所有小号。

## 检测维度

Alias+ 目前检测：

- `DeviceId`
- `ClientRandomId`
- `SelfSignedId`
- `NetworkID`
- `Resource Pack Cache`
- `Blob Cache`

每个维度都可以在 `config.yml` 的 `checks` 下单独开关。另外还有两项校验开关：

- `DeviceIdFormat` —— 拒绝格式非法、或与上报平台不吻合的 `DeviceId`（安卓/iOS 的大小写 hex、Windows 的 hex 或 UUID、Xbox 的 base64、PlayStation 的 UUID；没有观察数据的平台一律放行）。被拒绝的值不入库，并执行惩罚
- `ResourcePackSpoof` —— 客户端声称已持有刚分配给它的包时，断开并惩罚；仅在 `ResourcePackCache` 开启时生效

### 指纹时效

`expire.ResourcePackCache` 与 `expire.BlobCache` 控制一条指纹在检测列表里保留多久（单位：天，默认 7）。
超过有效期的指纹在构建检测列表时会被跳过。设为 `0` 表示不判过期。
每次进服都会刷新该玩家已持有指纹的时间戳。

### 缓存补全

玩家完成首次检测（`confirmed`）之后，每次登录还会额外下发他自己的指纹包：
客户端仍持有就跳过下载，缓存被清过就重新下载。

### 指纹包外观

`pack-name` 控制客户端资源包列表里显示的名称（默认 `Test`）。
在插件目录放一个 `icon.png` 就会作为包图标，没有则不带图标。

**使用：**

在 `server.properties` 文件中启用 `force-resources`，否则资源包维度会在启动时自动禁用并输出一条警告。
在 `nukkit-mot.yml` 文件中启用 `nethernet`。

```
/alias <名称/uuid>
```

**注意事项：**

本项目为 vibe coding 辅助完成的技术演示，可能存在 bug，请勿在成品服务器上使用。

## 开发者 API

### 接入

在你的插件 `plugin.yml` 中加入 `depend: [Alias+]`，然后获取实例：

```java
AltDetector alias = (AltDetector) getServer().getPluginManager().getPlugin("Alias+");
```

### 惩罚 API

```java
int count = alias.punish(uuid);
```

- `uuid`：玩家 uuid 字符串（`player.getUniqueId().toString()`）
- 返回值：加入惩罚队列的账号数量（目标本身 + 所有关联小号，已去重）
- 惩罚命令在 `config.yml` 中配置：`punish.command`（占位符 `%player%`、`%uuid%`）

### 查询 API

```java
Set<String> alts = alias.getRelatedAccounts(uuid);                  // 所有关联小号的 uuid
Map<String, Set<String>> byDim = alias.getRelatedByDimension(uuid); // 按维度分组（DeviceId/SelfSignedId/ClientRandomId/NetworkID/pack/blob）
```