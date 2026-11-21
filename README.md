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

- `DeviceIdFormat` — reject `DeviceId` values whose format is malformed **or** does not match the reported device OS (Android/iOS upper or lower hex, Windows hex or UUID, Xbox base64, PlayStation UUID; platforms without observation data are passed through). Rejected values are not stored and the account is punished
- `ResourcePackSpoof` — disconnect and punish clients that claim to already hold the pack just assigned to them; only effective while `ResourcePackCache` is enabled

### Fingerprint lifetime

`expire.ResourcePackCache` and `expire.BlobCache` control how long a fingerprint stays in rotation (in days, default 7).
Expired fingerprints are skipped when the detection list is built. Setting a value to `0` disables expiry.
Every login refreshes the timestamp of any fingerprint that player already holds.

### Cache refill

Once a player has completed the first check (`confirmed`), every later login also re-sends that player's own
fingerprint pack. A client that still has it skips the download; a client that cleared its cache downloads it again.

### Fingerprint pack appearance

`pack-name` sets the name shown in the client's resource pack list (default `Test`).
Put an `icon.png` next to `config.yml` in the plugin folder to use it as the pack icon; without it the pack has no icon.

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