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

- `IdFormat` —— 拒绝格式非法的标识符
- `ResourcePackSpoof` —— 仅在 `ResourcePackCache` 开启时生效

### 指纹时效

`expire.ResourcePackCache` 与 `expire.BlobCache` 控制一条指纹在检测列表里保留多久（单位：天，默认 7）。
超过有效期的指纹在构建检测列表时会被跳过。设为 `0` 表示不判过期。
每次进服都会刷新该玩家已持有指纹的时间戳。

**使用：**

在 `server.properties` 文件中启用 `force-resources`，否则资源包维度会在启动时自动禁用并输出一条警告。
在 `nukkit-mot.yml` 文件中启用 `nethernet`。

```
/alias <名称/uuid>      列出与该玩家关联的全部账号
/alias -stats            按检测维度统计封禁情况
/alias -reload           重载 config.yml，不用重启
```

`-stats` 和 `-reload` 需要 `altdetector.admin` 权限（默认仅 op）。

**注意事项：**

本项目由AI辅助完成，目前为测试版，可能存在bug。

## 开发者 API

### 接入

在你的插件 `plugin.yml` 中加入 `depend: [Alias]`，然后获取实例。

注意插件 id 是 `Alias` 而不是 `Alias+`：Nukkit 注册插件时会把加号去掉，写成 `Alias+` 会报 `Unknown dependency`。另外要用 `depend` 而不是 `softdepend`——后者不共享类，下面的强转会抛 `ClassCastException`。

```java
AltDetector alias = (AltDetector) getServer().getPluginManager().getPlugin("Alias");
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