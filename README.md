# Alias+

A Minecraft Bedrock Edition alt-account detection plugin built on the Nukkit-MOT core.

**Advantages:**

Unlike other anti-alt plugins on the market, we not only support basic checks like DeviceId, SelfSignedId(doesn't work in newer versions), and ClientRandomId, but also have developed detection based on resource pack cache and BlobCache—less common methods designed specifically to catch spoofers. We also provide a query and punishment API, with support for delayed punishments.

**Usage:**

Enable `force-resources` in the `server.properties` file.

```
/alias <name/uuid/xuid>
```

Example output:

```
--ExampleAcc's accounts (xuid: 123456)--
DeviceId: ExampleAcc
ClientRandomId: ExampleAcc
ResPackCache: ExampleAcc
BlobCache: ExampleAcc
```

## Configuration (`config.yml`)

All options are created with defaults on first launch; everything below is optional.

| Option | Default | Description |
|---|---|---|
| `packcache-enabled` | `true` | Enable the resource pack cache detection dimension |
| `blobcache-enabled` | `true` | Enable the blob cache detection dimension |
| `strictmode` | `true` | Association direction. `true` = one-way (only show who cached/hit the target's fingerprints); `false` = two-way (also show whose fingerprints the target cached/hit). Two-way mode allows spoofed hits to frame innocent players via punishment chaining. |
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
Map<String, Set<String>> byDim = alias.getRelatedByDimension(uuid); // by dimension (device/selfSigned/clientRandom/pack/blob)
```
