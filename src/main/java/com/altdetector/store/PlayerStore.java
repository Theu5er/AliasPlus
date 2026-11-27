package com.altdetector.store;

import com.altdetector.util.Uuids;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家数据存储：H2 内嵌数据库。
 *
 * <p>对外暴露的记录形态与旧版 YAML 完全一致（嵌套 Map），所以调用方无需改动；
 * 内部按关系表存储，关联关系拆到独立表里，查询走索引。
 */
public class PlayerStore {

    /** 8 个检测维度，顺序与 config.yml 的 checks 段一致。 */
    private static final String[] BAN_DIMENSIONS =
            {"DeviceId", "IdFormat", "SelfSignedId", "ClientRandomId", "NetworkID",
                    "ResourcePackCache", "ResourcePackSpoof", "BlobCache"};
    /** 存放在 identifiers 里、可在小号身上统计覆盖的四个身份维度。 */
    private static final String[] ALT_IDENTIFIER_DIMENSIONS =
            {"DeviceId", "SelfSignedId", "ClientRandomId", "NetworkID"};
    /** 会主动封人的三个检测项。 */
    private static final String[] ALT_BAN_SOURCES = {"IdFormat", "ResourcePackSpoof", "BlobCache"};

    /** 开发者手动调用封禁的来源标识。 */
    private static final String SOURCE_MANUAL = "Manual";

    /** 标识符列宽，与 SqlSchema 保持一致；超长值直接丢弃，不让一条脏数据拖垮整次登录写入。 */
    private static final int IDENTIFIER_KIND_MAX_LENGTH = 32;
    private static final int IDENTIFIER_VALUE_MAX_LENGTH = 128;

    /** 允许拼接进 SQL 的表名，只接受这些字面量，杜绝把外部输入带进语句。 */
    private static final Set<String> SINGLETON_TABLES = Set.of("packs", "blobs");
    private static final Set<String> CLAIM_TABLES = Set.of("pack_claims", "blob_claims");

    /** 按 uuid 直接归属玩家的表，老键改名时整行迁走。 */
    private static final String[] LEGACY_OWNED_TABLES =
            {"aliases", "identifiers", "packs", "blobs", "flags", "players"};

    private static final long TOUCH_INTERVAL_MS = 3600_000L;

    private final Connection connection;

    public PlayerStore(File databaseFile) {
        try {
            // 插件 jar 可能没有 META-INF/services，SPI 自动注册会失效，这里显式加载
            Class.forName("org.h2.Driver");
            File parent = databaseFile.getParentFile();
            if (parent != null) parent.mkdirs();
            String url = "jdbc:h2:" + databaseFile.getAbsolutePath();
            this.connection = DriverManager.getConnection(url, "sa", "");
            SqlSchema.create(this.connection);
        } catch (SQLException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to open database: " + databaseFile, e);
        }
    }

    public synchronized void close() {
        try {
            // 必须用 SHUTDOWN：H2 的 Connection.close() 在 2.x 里不保证同步关掉 MVStore，
            // 残留的文件锁会让 /reload 后重新打开报 OverlappingFileLockException
            try (java.sql.Statement statement = this.connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
            this.connection.close();
        } catch (SQLException e) {
            // 关服阶段没有补救手段，但必须留痕：否则下次启动只会表现为「数据库被锁」
            Logger.getLogger("Alias").warning("Failed to close database cleanly: " + e.getMessage());
        }
    }

    /* ================================================================== */
    /*  读取                                                              */
    /* ================================================================== */

    /** 全部玩家档案（嵌套 Map 形态，与旧版一致）。 */
    public synchronized Map<String, Map<String, Object>> getPlayers() {
        Map<String, Map<String, Object>> records = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : queryAll("SELECT uuid, name, created_at FROM players")) {
                records.put(String.valueOf(row.get("uuid")), newRecord(String.valueOf(row.get("uuid")), row));
            }
            fillAliases(records, null);
            fillIdentifiers(records, null);
            fillPacks(records, null);
            fillBlobs(records, null);
            fillPunished(records, null);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load players", e);
        }
        return records;
    }

    /** 单条档案副本；不存在返回 null。 */
    public synchronized Map<String, Object> getPlayer(String key) {
        String uuid = Uuids.normalize(key);
        try {
            List<Map<String, Object>> rows = query("SELECT uuid, name, created_at FROM players WHERE uuid = ?", uuid);
            if (rows.isEmpty()) return null;
            Map<String, Map<String, Object>> records = new LinkedHashMap<>();
            records.put(uuid, newRecord(uuid, rows.get(0)));
            fillAliases(records, uuid);
            fillIdentifiers(records, uuid);
            fillPacks(records, uuid);
            fillBlobs(records, uuid);
            fillPunished(records, uuid);
            return records.get(uuid);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load player: " + uuid, e);
        }
    }

    /** 维度视图：kind -> (标识符 -> 玩家键集合)。 */
    public synchronized Map<String, Map<String, List<String>>> getIdentifiers() {
        Map<String, Map<String, List<String>>> grouped = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : queryAll("SELECT uuid, kind, id_value FROM identifiers")) {
                String kind = String.valueOf(row.get("kind"));
                String value = String.valueOf(row.get("id_value"));
                String uuid = String.valueOf(row.get("uuid"));
                grouped.computeIfAbsent(kind, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(value, ignored -> new ArrayList<>())
                        .add(uuid);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load identifiers", e);
        }
        return grouped;
    }

    /** 包指纹归属：owner -> [claimed_by]。 */
    public synchronized Map<String, List<String>> getPackClaims() {
        Map<String, List<String>> claims = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : queryAll("SELECT owner, claimer FROM pack_claims")) {
                claims.computeIfAbsent(String.valueOf(row.get("owner")), ignored -> new ArrayList<>())
                        .add(String.valueOf(row.get("claimer")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load pack claims", e);
        }
        return claims;
    }

    /** 有效期内（ttlMs <= 0 表示不判过期）的包映射：owner -> pack uuid。 */
    public synchronized Map<String, String> getActivePacks(long ttlMs) {
        Map<String, String> active = new LinkedHashMap<>();
        String sql = "SELECT uuid, pack_uuid FROM packs"
                + (ttlMs > 0 ? " WHERE ts > 0 AND ts >= ?" : "");
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            if (ttlMs > 0) statement.setLong(1, System.currentTimeMillis() - ttlMs);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    active.put(resultSet.getString(1), resultSet.getString(2));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load active packs", e);
        }
        return active;
    }

    /** 精确查该玩家的指纹包 uuid；不带时间过滤，查不到返回 null。 */
    public synchronized String findPackUuid(String playerUuid) {
        String key = Uuids.normalize(playerUuid);
        if (key.isEmpty()) return null;
        return readString("SELECT pack_uuid FROM packs WHERE uuid = ?", key);
    }

    public synchronized boolean isPackConfirmed(String playerUuid) {
        return readFlag("SELECT confirmed FROM packs WHERE uuid = ?", Uuids.normalize(playerUuid));
    }

    public synchronized boolean isPunishedFlagged(String playerUuid) {
        return readFlag("SELECT punished FROM flags WHERE uuid = ?", Uuids.normalize(playerUuid));
    }
    public synchronized String displayName(String key) {
        Map<String, Object> record = getPlayer(key);
        Object name = record == null ? null : record.get("name");
        return name == null || String.valueOf(name).isEmpty() ? Uuids.normalize(key) : String.valueOf(name);
    }

    /** 展示用身份串：优先 xuid 之外的唯一标识，这里就是 uuid 本身。 */
    public synchronized String getDisplayIdentifier(String key) {
        return "uuid: " + Uuids.normalize(key);
    }

    public synchronized String uuidOf(String key) {
        return Uuids.normalize(key);
    }

    /** 全服同名统计（忽略大小写）。 */
    public synchronized Map<String, Integer> buildNameCollisionMap() {
        Map<String, Integer> collisions = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : queryAll("SELECT name FROM players")) {
                String name = String.valueOf(row.get("name")).toLowerCase();
                if (name.isEmpty()) continue;
                collisions.merge(name, 1, Integer::sum);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to build name collision map", e);
        }
        return collisions;
    }

    /**
     * 找到同名且尚未完成初始化的空壳记录所持有的包。
     *
     * <p>空壳 = 没有历史名字、也没有任何标识符，但有包分配。
     */
    public synchronized String findAdoptableShellPack(String playerName, String excludeUuid) {
        if (playerName == null || playerName.isEmpty()) return null;
        String excluded = Uuids.normalize(excludeUuid);
        String sql = "SELECT p.uuid, p.pack_uuid FROM packs p"
                + " JOIN players pl ON pl.uuid = p.uuid"
                + " WHERE LOWER(pl.name) = ? AND p.uuid <> ?"
                + " AND NOT EXISTS (SELECT 1 FROM aliases a WHERE a.uuid = p.uuid)"
                + " AND NOT EXISTS (SELECT 1 FROM identifiers i WHERE i.uuid = p.uuid)";
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            statement.setString(1, playerName.toLowerCase());
            statement.setString(2, excluded);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) return resultSet.getString(2);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find adoptable shell pack", e);
        }
        return null;
    }

    /* ================================================================== */
    /*  写入                                                              */
    /* ================================================================== */

    /**
     * 一次事务写入登录档案：档案、历史名字、标识符。
     *
     * @return 被并入当前键的老键（老版本遗留），无则 null
     */
    public synchronized String storeLogin(String playerUuid, String playerName, Map<String, String> identifiers) {
        String key = Uuids.normalize(playerUuid);
        if (key.isEmpty() || playerName == null) return null;
        String adopted = adoptLegacy(key);
        try {
            this.connection.setAutoCommit(false);
            upsertPlayer(key, playerName);
            insertAlias(key, playerName);
            if (identifiers != null) {
                for (Map.Entry<String, String> entry : identifiers.entrySet()) {
                    upsertIdentifier(key, entry.getKey(), entry.getValue());
                }
            }
            this.connection.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly();
            throw new IllegalStateException("Failed to store login: " + key, e);
        } finally {
            restoreAutoCommit();
        }
        return adopted;
    }

    public synchronized void storeIdentifier(String kind, String value, String playerUuid) {
        if (value == null || value.isEmpty()) return;
        String key = Uuids.normalize(playerUuid);
        if (key.isEmpty()) return;
        try {
            upsertIdentifier(key, kind, value);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store identifier: " + key, e);
        }
    }

    /** @return true = 本次新增的命中，已存在返回 false */
    public synchronized boolean addPackClaim(String claimerUuid, String ownerUuid) {
        String claimer = Uuids.normalize(claimerUuid);
        String owner = Uuids.normalize(ownerUuid);
        if (owner.isEmpty() || claimer.isEmpty()) return false;
        try (PreparedStatement statement = this.connection.prepareStatement(
                "INSERT INTO pack_claims (owner, claimer) SELECT ?, ?"
                        + " WHERE NOT EXISTS (SELECT 1 FROM pack_claims WHERE owner = ? AND claimer = ?)")) {
            statement.setString(1, owner);
            statement.setString(2, claimer);
            statement.setString(3, owner);
            statement.setString(4, claimer);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add pack claim", e);
        }
    }

    /** 包分配结果：fresh=true 表示本次是新随机分配，客户端不可能预先拥有。 */
    public static final class PackAssignment {
        public final String packUuid;
        public final boolean fresh;

        PackAssignment(String packUuid, boolean fresh) {
            this.packUuid = packUuid;
            this.fresh = fresh;
        }
    }

    /** 分配（或复用）该玩家的指纹包。 */
    public synchronized PackAssignment ensurePackAssigned(String playerUuid, String playerName) {
        String key = Uuids.normalize(playerUuid);
        try {
            upsertPlayer(key, playerName == null || playerName.isEmpty() ? key : playerName);
            String existing = readString("SELECT pack_uuid FROM packs WHERE uuid = ?", key);
            if (existing != null && !existing.isEmpty()) return new PackAssignment(existing, false);
            String created = UUID.randomUUID().toString();
            try (PreparedStatement statement = this.connection.prepareStatement(
                    "MERGE INTO packs (uuid, pack_uuid, confirmed, ts) KEY (uuid) VALUES (?, ?, 0, ?)")) {
                statement.setString(1, key);
                statement.setString(2, created);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return new PackAssignment(created, true);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to assign pack: " + key, e);
        }
    }

    public synchronized void markPackConfirmed(String playerUuid) {
        execute("UPDATE packs SET confirmed = 1 WHERE uuid = ?", Uuids.normalize(playerUuid));
    }

    public synchronized void markPunished(String playerUuid) {
        String key = Uuids.normalize(playerUuid);
        if (key.isEmpty()) return;
        try (PreparedStatement statement = this.connection.prepareStatement(
                "MERGE INTO flags (uuid, punished) KEY (uuid) VALUES (?, 1)")) {
            statement.setString(1, key);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark punished: " + key, e);
        }
    }

    /** 删掉尚未确认的包分配；没有包记录时返回 false。 */
    public synchronized boolean removeUnconfirmedPack(String playerUuid) {
        String key = Uuids.normalize(playerUuid);
        try (PreparedStatement statement = this.connection.prepareStatement(
                "DELETE FROM packs WHERE uuid = ? AND confirmed = 0")) {
            statement.setString(1, key);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove pack: " + key, e);
        }
    }

    public synchronized void touchPack(String playerUuid) {
        touch("packs", Uuids.normalize(playerUuid));
    }

    public synchronized void touchBlob(String playerUuid) {
        touch("blobs", Uuids.normalize(playerUuid));
    }

    /** Blob id 表：玩家键 -> blob id（十六进制）。 */
    public synchronized void saveBlobIds(Map<String, String> blobIds) {
        try {
            this.connection.setAutoCommit(false);
            for (Map.Entry<String, String> entry : blobIds.entrySet()) {
                try (PreparedStatement statement = this.connection.prepareStatement(
                        "MERGE INTO blobs (uuid, blob_id) KEY (uuid) VALUES (?, ?)")) {
                    String key = Uuids.normalize(entry.getKey());
                    if (key.isEmpty() || entry.getValue() == null) continue;
                    statement.setString(1, key);
                    statement.setString(2, entry.getValue());
                    statement.executeUpdate();
                }
            }
            this.connection.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly();
            throw new IllegalStateException("Failed to save blob ids", e);
        } finally {
            restoreAutoCommit();
        }
    }

    /** Blob 命中关系：owner -> [claimer]。 */
    /** 新增一条指纹命中关系；同一对重复写入是幂等的（MERGE 语义）。 */
    public synchronized void addBlobClaim(String ownerUuid, String claimerUuid) {
        String owner = Uuids.normalize(ownerUuid);
        String claimer = Uuids.normalize(claimerUuid);
        if (owner.isEmpty() || claimer.isEmpty() || owner.equals(claimer)) return;
        try (PreparedStatement statement = this.connection.prepareStatement(
                "MERGE INTO blob_claims (owner, claimer) KEY (owner, claimer) VALUES (?, ?)")) {
            statement.setString(1, owner);
            statement.setString(2, claimer);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add blob claim", e);
        }
    }

    /* ================================================================== */
    /*  内部实现                                                          */
    /* ================================================================== */

    private Map<String, Object> newRecord(String uuid, Map<String, Object> row) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("name", row.get("name"));
        record.put("aliases", new ArrayList<String>());
        record.put("uuid", uuid);
        record.put("Punished", false);
        record.put("ids", new LinkedHashMap<String, Object>());
        record.put("pack", new LinkedHashMap<String, Object>());
        record.put("blob", new LinkedHashMap<String, Object>());
        return record;
    }

    private void fillAliases(Map<String, Map<String, Object>> records, String only) throws SQLException {
        String sql = "SELECT uuid, alias_name FROM aliases" + (only == null ? "" : " WHERE uuid = ?");
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            if (only != null) statement.setString(1, only);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> record = records.get(resultSet.getString(1));
                    if (record == null) continue;
                    aliasesOf(record).add(resultSet.getString(2));
                }
            }
        }
    }

    private void fillIdentifiers(Map<String, Map<String, Object>> records, String only) throws SQLException {
        String sql = "SELECT uuid, kind, id_value FROM identifiers" + (only == null ? "" : " WHERE uuid = ?");
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            if (only != null) statement.setString(1, only);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> record = records.get(resultSet.getString(1));
                    if (record == null) continue;
                    idsOf(record).put(resultSet.getString(2), resultSet.getString(3));
                }
            }
        }
    }

    private void fillPacks(Map<String, Map<String, Object>> records, String only) throws SQLException {
        String sql = "SELECT uuid, pack_uuid, confirmed, ts FROM packs" + (only == null ? "" : " WHERE uuid = ?");
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            if (only != null) statement.setString(1, only);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> record = records.get(resultSet.getString(1));
                    if (record == null) continue;
                    Map<String, Object> pack = packOf(record);
                    pack.put("uuid", resultSet.getString(2));
                    pack.put("confirmed", resultSet.getInt(3) != 0);
                    long ts = resultSet.getLong(4);
                    if (ts > 0) pack.put("ts", ts);
                }
            }
        }
        try (PreparedStatement statement = this.connection.prepareStatement(
                "SELECT owner, claimer FROM pack_claims" + (only == null ? "" : " WHERE owner = ?"))) {
            if (only != null) statement.setString(1, only);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> record = records.get(resultSet.getString(1));
                    if (record == null) continue;
                    claimedByOf(packOf(record)).add(resultSet.getString(2));
                }
            }
        }
    }

    private void fillBlobs(Map<String, Map<String, Object>> records, String only) throws SQLException {
        String sql = "SELECT uuid, blob_id, ts FROM blobs" + (only == null ? "" : " WHERE uuid = ?");
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            if (only != null) statement.setString(1, only);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> record = records.get(resultSet.getString(1));
                    if (record == null) continue;
                    Map<String, Object> blob = blobOf(record);
                    blob.put("id", resultSet.getString(2));
                    long ts = resultSet.getLong(3);
                    if (ts > 0) blob.put("ts", ts);
                }
            }
        }
        try (PreparedStatement statement = this.connection.prepareStatement(
                "SELECT owner, claimer FROM blob_claims" + (only == null ? "" : " WHERE owner = ?"))) {
            if (only != null) statement.setString(1, only);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> record = records.get(resultSet.getString(1));
                    if (record == null) continue;
                    claimedByOf(blobOf(record)).add(resultSet.getString(2));
                }
            }
        }
    }

    private void fillPunished(Map<String, Map<String, Object>> records, String only) throws SQLException {
        String sql = "SELECT uuid, punished FROM flags" + (only == null ? "" : " WHERE uuid = ?");
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            if (only != null) statement.setString(1, only);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> record = records.get(resultSet.getString(1));
                    if (record != null && resultSet.getInt(2) != 0) record.put("Punished", true);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> aliasesOf(Map<String, Object> record) {
        return (List<String>) record.get("aliases");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> idsOf(Map<String, Object> record) {
        return (Map<String, Object>) record.get("ids");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> packOf(Map<String, Object> record) {
        return (Map<String, Object>) record.get("pack");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> blobOf(Map<String, Object> record) {
        return (Map<String, Object>) record.get("blob");
    }

    @SuppressWarnings("unchecked")
    private static List<String> claimedByOf(Map<String, Object> section) {
        return (List<String>) section.computeIfAbsent("claimed_by", ignored -> new ArrayList<String>());
    }

    /** 老版本可能把档案存在别的键下（例如按名字或旧格式），把它的全部关联数据并入当前键。 */
    private String adoptLegacy(String key) {
        // 只认壳记录：有包分配但从未完成登录（没有历史名字、没有标识符）。
        // 有 aliases 说明那个账号真的登录过，同名也只是重名，不是同一个人，不能吞并。
        String legacy = readString("SELECT p.uuid FROM players p WHERE p.uuid <> ?"
                + " AND p.name = (SELECT name FROM players WHERE uuid = ?)"
                + " AND NOT EXISTS (SELECT 1 FROM aliases a WHERE a.uuid = p.uuid)"
                + " AND NOT EXISTS (SELECT 1 FROM identifiers i WHERE i.uuid = p.uuid)", key, key);
        if (legacy == null) return null;

        try {
            this.connection.setAutoCommit(false);

            // 历史名字：并集
            execute("MERGE INTO aliases KEY (uuid, alias_name)"
                    + " SELECT ?, alias_name FROM aliases WHERE uuid = ?", key, legacy);

            // 标识符：当前键已有同 kind 的保留自己的，缺的才补
            execute("INSERT INTO identifiers (uuid, kind, id_value, ts)"
                    + " SELECT ?, kind, id_value, ts FROM identifiers WHERE uuid = ?"
                    + " AND kind NOT IN (SELECT kind FROM identifiers WHERE uuid = ?)", key, legacy, key);

            // 一人一条的表：当前键没有记录时才补，别覆盖这次登录刚写下的东西
            copyIfAbsent("packs", "pack_uuid, confirmed, ts", key, legacy);
            copyIfAbsent("blobs", "blob_id, ts", key, legacy);

            // 惩罚标记取并集：老记录惩罚过就给当前键补一行，当前键没有行时靠 MERGE 插入
            execute("MERGE INTO flags KEY (uuid)"
                    + " SELECT ?, 1 FROM flags WHERE uuid = ? AND punished = 1", key, legacy);

            // 命中关系：owner 和 claimer 两侧都要换键，否则另一头悬空
            mergeClaims("pack_claims", key, legacy);
            mergeClaims("blob_claims", key, legacy);

            for (String table : LEGACY_OWNED_TABLES) {
                execute("DELETE FROM " + table + " WHERE uuid = ?", legacy);
            }
            this.connection.commit();
            return legacy;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly();
            throw new IllegalStateException("Failed to adopt legacy record: " + key, e);
        } finally {
            restoreAutoCommit();
        }
    }

    /** 一人一条的表：把老记录的字段补进当前键，当前键已有数据时保留自己的。 */
    private void copyIfAbsent(String table, String columns, String key, String legacy) {
        if (!SINGLETON_TABLES.contains(table)) throw new IllegalArgumentException("Unexpected table: " + table);
        execute("INSERT INTO " + table + " (uuid, " + columns + ")"
                + " SELECT ?, " + columns + " FROM " + table + " WHERE uuid = ?"
                + " AND NOT EXISTS (SELECT 1 FROM " + table + " WHERE uuid = ?)", key, legacy, key);
    }

    /* ================================================================== */
    /*  封禁记录                                                           */
    /* ================================================================== */

    /** 记一条封禁记录；同号同来源只保留首次（MERGE），重复触发不会刷出多条。 */
    public synchronized void recordBan(String playerUuid, String playerName, String source, String triggerUuid) {
        String key = Uuids.normalize(playerUuid);
        if (key.isEmpty() || source == null || source.isEmpty()) return;
        String name = playerName == null ? "" : playerName;
        String trigger = triggerUuid == null || triggerUuid.isEmpty() ? null : Uuids.normalize(triggerUuid);
        if (trigger != null && trigger.equals(key)) return;   // 自己触发自己不算株连
        try (PreparedStatement statement = this.connection.prepareStatement(
                "MERGE INTO ban_records (uuid, name, source, trigger_uuid, ts) KEY (uuid, source)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, name);
            statement.setString(3, source);
            statement.setString(4, trigger);
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record ban: " + key, e);
        }
    }

    /** 被连坐封禁的小号数量（主号之外、由关联关系带出来的那些）。 */
    public synchronized int countRelatedBans() {
        Long value = readLong("SELECT COUNT(*) FROM ban_records WHERE trigger_uuid IS NOT NULL", new Object[0]);
        return value == null ? 0 : value.intValue();
    }

    /**
     * 被连坐封禁的小号在各维度上的分布。
     *
     * <p>前五项是"这个小号身上带有该维度的指纹"，后三项是"这个小号因为哪个检测项被封"。
     * 同一个小号通常带多个维度，所以各项相加会大于小号数量。
     */
    public synchronized Map<String, Integer> countAltDimensions() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String dimension : BAN_DIMENSIONS) counts.put(dimension, 0);
        String alts = "(SELECT uuid FROM ban_records WHERE trigger_uuid IS NOT NULL)";

        for (String kind : ALT_IDENTIFIER_DIMENSIONS) {
            Long value = readLong("SELECT COUNT(DISTINCT uuid) FROM identifiers WHERE kind = ? AND uuid IN " + alts, kind);
            counts.put(kind, value == null ? 0 : value.intValue());
        }
        Long packs = readLong("SELECT COUNT(DISTINCT uuid) FROM packs WHERE uuid IN " + alts, new Object[0]);
        counts.put("ResourcePackCache", packs == null ? 0 : packs.intValue());
        for (String source : ALT_BAN_SOURCES) {
            // 旧库里来源名是 DeviceIdFormat，改名后要一并认，否则历史封禁记录会在 -stats 里凭空消失
            Long value = "IdFormat".equals(source)
                    ? readLong("SELECT COUNT(*) FROM ban_records WHERE source IN ('IdFormat', 'DeviceIdFormat') AND trigger_uuid IS NOT NULL", new Object[0])
                    : readLong("SELECT COUNT(*) FROM ban_records WHERE source = ? AND trigger_uuid IS NOT NULL", source);
            counts.put(source, value == null ? 0 : value.intValue());
        }
        return counts;
    }

    /** 命中关系表：老键在两侧都换成当前键，再清掉老键的行。 */
    private void mergeClaims(String table, String key, String legacy) {
        if (!CLAIM_TABLES.contains(table)) throw new IllegalArgumentException("Unexpected table: " + table);
        // 老键作为 owner 的那些命中（claimer 已经是当前键的会自命中，排除）
        execute("MERGE INTO " + table + " KEY (owner, claimer)"
                + " SELECT ?, claimer FROM " + table + " WHERE owner = ? AND claimer <> ?", key, legacy, key);
        // 老键作为 claimer 的那些命中；owner 为当前键的行是上一步插进来的，排除掉防止自命中
        execute("MERGE INTO " + table + " KEY (owner, claimer)"
                + " SELECT owner, ? FROM " + table + " WHERE claimer = ? AND owner <> ?", key, legacy, key);
        execute("DELETE FROM " + table + " WHERE owner = ? OR claimer = ?", legacy, legacy);
    }

    private void upsertPlayer(String key, String name) {
        // 建档时间只在第一次写入，之后每次登录只更新名字
        execute("INSERT INTO players (uuid, name, created_at) SELECT ?, ?, ?"
                + " WHERE NOT EXISTS (SELECT 1 FROM players WHERE uuid = ?)",
                key, name, System.currentTimeMillis(), key);
        execute("UPDATE players SET name = ? WHERE uuid = ?", name, key);
    }

    private void insertAlias(String key, String alias) throws SQLException {
        try (PreparedStatement statement = this.connection.prepareStatement(
                "MERGE INTO aliases (uuid, alias_name) KEY (uuid, alias_name) VALUES (?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, alias);
            statement.executeUpdate();
        }
    }

    private void upsertIdentifier(String key, String kind, String value) throws SQLException {
        if (kind == null || kind.isEmpty() || value == null || value.isEmpty()) return;
        if (key.isEmpty()) return;
        if (kind.length() > IDENTIFIER_KIND_MAX_LENGTH || value.length() > IDENTIFIER_VALUE_MAX_LENGTH) return;
        try (PreparedStatement statement = this.connection.prepareStatement(
                "MERGE INTO identifiers (uuid, kind, id_value, ts) KEY (uuid, kind) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, kind);
            statement.setString(3, value);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    /** 写盘节流：一小时内重复刷新不落盘。 */
    private void touch(String table, String key) {
        try {
            Long current = readLong("SELECT ts FROM " + table + " WHERE uuid = ?", key);
            if (current == null) return;
            long now = System.currentTimeMillis();
            if (current > 0 && now - current < TOUCH_INTERVAL_MS) return;
            try (PreparedStatement statement = this.connection.prepareStatement(
                    "UPDATE " + table + " SET ts = ? WHERE uuid = ?")) {
                statement.setLong(1, now);
                statement.setString(2, key);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to touch " + table + ": " + key, e);
        }
    }

    private boolean readFlag(String sql, String key) {
        Long value = readLong(sql, key);
        return value != null && value != 0;
    }

    private Long readLong(String sql, Object... params) {
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return null;
                long value = resultSet.getLong(1);
                return resultSet.wasNull() ? null : value;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
    }

    private String readString(String sql, Object... params) {
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
    }

    private void execute(String sql, Object... params) {
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            bind(statement, params);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Update failed: " + sql, e);
        }
    }

    private List<Map<String, Object>> query(String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                int columns = resultSet.getMetaData().getColumnCount();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(resultSet.getMetaData().getColumnLabel(i).toLowerCase(), resultSet.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private List<Map<String, Object>> queryAll(String sql) throws SQLException {
        return query(sql);
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    private void rollbackQuietly() {
        try {
            this.connection.rollback();
        } catch (SQLException ignored) {
            // 事务已失效，无法补救
        }
    }

    private void restoreAutoCommit() {
        try {
            this.connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 同上
        }
    }
}
