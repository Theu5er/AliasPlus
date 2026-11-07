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
Detection is almost completely silent—cheaters have no idea why they got banned.

🔗
**Developer API**
We provide query and punishment APIs, allowing you to ban all alt accounts with one call.

## Detection Vectors

Alias+ currently checks:

- `DeviceId`
- `ClientRandomId`
- `SelfSignedId`
- `NetworkID`
- `Resource Pack Cache`
- `Blob Cache`

Every vector can be turned on or off in `config.yml` under `checks`. Two extra guards live there as well:

- `DeviceIdFormat` — reject malformed `DeviceId` values: they are not stored and the account is punished
- `ResourcePackSpoof` — disconnect and punish clients that claim to already hold the pack just assigned to them; only effective while `ResourcePackCache` is enabled

**Usage:**

Enable `force-resources` in the `server.properties` file — without it the resource pack vector is disabled at startup and a warning is logged.
Enable `nethernet` in the `nukkit-mot.yml` file.

```
/alias <name/uuid>
```

**Notes:**

This project is a vibe-coding-assisted technical demo. It may contain bugs—do not use it on a production server.

## Developer API

### Setup

Add `depend: [Alias+]` to your plugin's `plugin.yml`, then get the instance:

```java
AltDetector alias = (AltDetector) getServer().getPluginManager().getPlugin("Alias+");
```

### Punish API

```java
int count = alias.punish(uuid);
```

- `uuid`: player uuid string (`player.getUniqueId().toString()`)
- Returns: number of accounts added to the punishment queue (the target itself + all related alt accounts, deduplicated)
- Punishment command is configured in `config.yml`: `punish.command` (placeholders `%player%`, `%uuid%`)

### Query API

```java
Set<String> alts = alias.getRelatedAccounts(uuid);                  // all related alt uuids
Map<String, Set<String>> byDim = alias.getRelatedByDimension(uuid); // by dimension (DeviceId/SelfSignedId/ClientRandomId/NetworkID/pack/blob)
```