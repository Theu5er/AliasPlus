# Alias+

**English** | [中文](./README.zh.md)

A Minecraft Bedrock Edition alt-account detection plugin built on the Nukkit-MOT core.

**Advantages:**

🔍
**Unique Detection**
We have more detection methods than similar plugins, so the average spoofer is no longer unsolvable.

🎯
**High Accuracy**
Detection does not rely on non-unique evidence like IP; results can be used for bans directly.

🤫
**Silent Detection**
Detection is almost completely silent and supports delayed punishments—cheaters have no idea why they got banned.

🔗
**Developer API**
We provide query and punishment APIs, allowing you to ban all alt accounts with one call.

## Detection Vectors

Alias+ currently checks:

- `DeviceId`
- `ClientRandomId`
- `SelfSignedId` (maybe unavailable now)
- `NetworkID` (NetherNet clients only)
- Resource Pack Cache
- Blob Cache

**Usage:**

Enable `force-resources` in the `server.properties` file.
Enable `nethernet` in the `nukkit-mot.yml` file.

```
/alias <name/uuid/xuid>
```

Example output:

```
--ExampleAcc's accounts (xuid: 123456)--
DeviceId: ExampleAcc
ClientRandomId: ExampleAcc
NetworkID: ExampleAcc
ResPackCache: ExampleAcc
BlobCache: ExampleAcc
```

## Configuration (`config.yml`)

All options are created with defaults on first launch; everything below is optional.

| Option | Default | Description |
|---|---|---|
| `packcache-enabled` | `true` | Enable the resource pack cache detection dimension |
| `blobcache-enabled` | `true` | Enable the blob cache detection dimension |
| `networkid-enabled` | `true` | Enable the NetherNet NetworkID detection dimension (NetherNet/WebRTC clients only; RakNet connections carry no NetworkID and are skipped silently) |
| `strictmode` | `true` | Association direction. `true` = one-way (only show who cached/hit the target's fingerprints); `false` = two-way (also show whose fingerprints the target cached/hit). ⚠️ Two-way mode allows spoofed hits to frame innocent players via punishment chaining. |
| `punish-command` | `ban %player% Alt account detected` | Punishment command template, executed as console. Placeholders: `%player%` = player name, `%uuid%` = player uuid |
| `punish-delay-min` | `5` | Minimum random delay for delayed bans (seconds, rolled independently per account) |
| `punish-delay-max` | `30` | Maximum random delay for delayed bans (seconds, rolled independently per account) |

**Notes:**

This project is a vibe-coding-assisted technical demo. It may contain bugs—do not use it on a production server.

The ResPackCache and BlobCache output can potentially be spoofed, but when necessary, you can simply ban all accounts returned, since they can only be alt accounts or hackers.

## Developer API

### Setup

Add `depend: [Alias+]` to your plugin's `plugin.yml`, then get the instance:

```java
AltDetector alias = (AltDetector) getServer().getPluginManager().getPlugin("Alias+");
```

### Punish API

```java
int count = alias.punish(uuid, delayed);
```

- `uuid`: player uuid string (`player.getUniqueId().toString()`)
- `delayed`: `true` for delayed ban / `false` for immediate ban
- Returns: number of accounts queued (the target itself + all related alt accounts, deduplicated)
- Punishment command and delay range are configured in `config.yml`: `punish-command` (placeholders `%player%`, `%uuid%`), `punish-delay-min`, `punish-delay-max` (seconds)

### Query API

```java
Set<String> alts = alias.getRelatedAccounts(uuid);                  // all related alt uuids
Map<String, Set<String>> byDim = alias.getRelatedByDimension(uuid); // by dimension (device/selfSigned/clientRandom/networkid/pack/blob)
```