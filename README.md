# Alias+

**English** | [中文](./README.zh.md)

Alternate account detection plugin for Minecraft Bedrock Edition, built on the Nukkit-MOT core.

**Advantages:**

🔍
**Distinctive detection**
We cover more detection vectors than comparable plugins — common spoofers are no longer an unsolvable case.

🎯
**High accuracy**
Detection does not rely on non-unique evidence such as IP addresses, so results can be used for banning directly.

🤫
**Silent detection**
Detection is almost entirely silent — alternate account users never find out why they were banned.

🔗
**Developer API**
We provide lookup and punishment APIs, so all alternate accounts can be banned in one call.

## Detection vectors

Alias+ currently detects:

- `DeviceId`
- `ClientRandomId`
- `SelfSignedId`
- `NetworkID`
- `Resource Pack Cache`
- `Blob Cache`

Every vector can be toggled individually under `checks` in `config.yml`. Two additional validation switches live there as well:

- `IdFormat` — reject identifiers with a malformed format
- `ResourcePackSpoof` — only effective while `ResourcePackCache` is enabled

### Fingerprint expiry

`expire.ResourcePackCache` and `expire.BlobCache` control how long a fingerprint stays in the detection list (in days, default 7).
Fingerprints past their expiry are skipped when the detection list is built. Set to `0` to disable expiry.
Each join refreshes the timestamps of the fingerprints the player already holds.

**Usage:**

Enable `force-resources` in `server.properties`, otherwise the resource pack vector is disabled automatically at startup with a warning.
Enable `nethernet` in `nukkit-mot.yml`.

```
/alias <name/uuid>      list every account related to that player
/alias -stats           ban statistics grouped by detection vector
/alias -reload          reload config.yml without restarting
```

`-stats` and `-reload` require the `altdetector.admin` permission (op only by default).

**Notes:**

This project was built with AI assistance and is currently a beta release — bugs are possible.

## Developer API

### Integration

Add `depend: [Alias]` to your plugin's `plugin.yml`, then grab the instance.

Note that the plugin id is `Alias`, not `Alias+`: Nukkit strips the plus sign when registering the plugin, so writing `Alias+` results in `Unknown dependency`. Also use `depend` rather than `softdepend` — the latter does not share classes, and the cast below throws `ClassCastException`.

```java
AltDetector alias = (AltDetector) getServer().getPluginManager().getPlugin("Alias");
```

### Punishment API

```java
int count = alias.punish(uuid);
```

- `uuid`: the player uuid string (`player.getUniqueId().toString()`)
- Returns: the number of accounts queued for punishment (the target itself plus every related alternate, de-duplicated)
- The punishment command is configured in `config.yml`: `punish.command` (placeholders `%player%`, `%uuid%`)

### Query API

```java
Set<String> alts = alias.getRelatedAccounts(uuid);                  // uuids of all related alternates
Map<String, Set<String>> byDim = alias.getRelatedByDimension(uuid); // grouped by vector (DeviceId/SelfSignedId/ClientRandomId/NetworkID/pack/blob)
```
