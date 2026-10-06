package com.altdetector.store;

import cn.nukkit.utils.Config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * players.yml 数据层：玩家档案、检测标识符、资源包/Blob 指纹关联的全部读写。
 *
 * <p>记录结构（players 键下，uuid 为键）：
 * <pre>
 * name / aliases / xuid        —— 身份（storePlayerProfile 写入）
 * ids: {device, selfSigned, clientRandom} —— 登录标识符
 * pack: {uuid, claimed_by}     —— 资源包指纹与命中者
 * blob: {id, claimed_by}       —— Blob 指纹与命中者
 * ResourcePackSpoof            —— 客户端谎报缓存标记
 * </pre>
 *
 * <p>所有写操作先 {@code reload()} 再 {@code save()}，保证与磁盘同步；
 * 只读操作每次从 Config 解析副本，调用方修改副本不会直接影响磁盘。
 */
public class PlayerStore {

    private final Config playersConfig;

    public PlayerStore(Config playersConfig) {
        this.playersConfig = playersConfig;
    }

    /** /alias 查询前刷新磁盘数据，避免读到内存中的过期副本。 */
    public void reload() {
        playersConfig.reload();
    }

    /* ================================================================== */
    /*  基础结构                                                          */
    /* ================================================================== */

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> getPlayers() {
        Object raw = playersConfig.get("players");
        Map<String, Map<String, Object>> r = new LinkedHashMap<>();
        if (raw instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) raw).entrySet()) {
                if (e.getValue() instanceof Map) {
                    Map<String, Object> inner = new LinkedHashMap<>();
                    for (Map.Entry<Object, Object> ie : ((Map<Object, Object>) e.getValue()).entrySet())
                        inner.put(String.valueOf(ie.getKey()), ie.getValue());
                    r.put(String.valueOf(e.getKey()), inner);
                }
            }
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet())
                m.put(String.valueOf(e.getKey()), e.getValue());
            return m;
        }
        return null;
    }

    /** 取/建嵌套 map 并写回 info（修正 asMap 副本未落盘的问题）。 */
    public static Map<String, Object> resolveNested(Map<String, Object> info, String key) {
        Map<String, Object> m = asMap(info.get(key));
        if (m == null) m = new LinkedHashMap<>();
        info.put(key, m);
        return m;
    }

    public static Map<String, Object> newPlayerRecord() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "");
        info.put("aliases", new ArrayList<>());
        info.put("ResourcePackSpoof", false);
        info.put("ids", new LinkedHashMap<>());
        info.put("pack", new LinkedHashMap<>());
        info.put("blob", new LinkedHashMap<>());
        return info;
    }

    /* ================================================================== */
    /*  启动迁移与结构规范化                                                */
    /* ================================================================== */

    /** 旧版扁平结构（identifiers/pack_claims/assigned_packs/blob_ids/blob_claims）静默合并进嵌套记录。 */
    @SuppressWarnings("unchecked")
    public void migrateLegacyStorage() {
        Object playersRaw = playersConfig.get("players");
        Object idsRaw = playersConfig.get("identifiers");
        Object packClaimsRaw = playersConfig.get("pack_claims");
        Object assignedRaw = playersConfig.get("assigned_packs");
        Object blobIdsRaw = playersConfig.get("blob_ids");
        Object blobClaimsRaw = playersConfig.get("blob_claims");

        boolean hasLegacy = (idsRaw instanceof Map) || (packClaimsRaw instanceof Map)
                || (assignedRaw instanceof Map) || (blobIdsRaw instanceof Map) || (blobClaimsRaw instanceof Map);
        if (!hasLegacy) return;

        Map<String, Map<String, Object>> players = new LinkedHashMap<>();
        if (playersRaw instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) playersRaw).entrySet()) {
                if (e.getValue() instanceof Map) {
                    Map<String, Object> inner = new LinkedHashMap<>();
                    for (Map.Entry<Object, Object> ie : ((Map<Object, Object>) e.getValue()).entrySet())
                        inner.put(String.valueOf(ie.getKey()), ie.getValue());
                    players.put(String.valueOf(e.getKey()), inner);
                }
            }
        }

        if (idsRaw instanceof Map) {
            for (Map.Entry<Object, Object> te : ((Map<Object, Object>) idsRaw).entrySet()) {
                String type = String.valueOf(te.getKey());
                if (!(te.getValue() instanceof Map)) continue;
                for (Map.Entry<Object, Object> ie : ((Map<Object, Object>) te.getValue()).entrySet()) {
                    List<String> uuids = new ArrayList<>();
                    if (ie.getValue() instanceof List)
                        for (Object o : (List<Object>) ie.getValue()) uuids.add(String.valueOf(o));
                    else uuids.add(String.valueOf(ie.getValue()));
                    for (String uuid : uuids) {
                        Map<String, Object> info = players.computeIfAbsent(uuid, k -> newPlayerRecord());
                        resolveNested(info, "ids").put(type, String.valueOf(ie.getKey()));
                    }
                }
            }
        }

        if (assignedRaw instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) assignedRaw).entrySet()) {
                Map<String, Object> info = players.computeIfAbsent(String.valueOf(e.getKey()), k -> newPlayerRecord());
                resolveNested(info, "pack").put("uuid", String.valueOf(e.getValue()));
            }
        }

        if (packClaimsRaw instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) packClaimsRaw).entrySet()) {
                List<String> claimers = new ArrayList<>();
                if (e.getValue() instanceof List)
                    for (Object o : (List<Object>) e.getValue()) claimers.add(String.valueOf(o));
                else claimers.add(String.valueOf(e.getValue()));
                Map<String, Object> info = players.computeIfAbsent(String.valueOf(e.getKey()), k -> newPlayerRecord());
                resolveNested(info, "pack").put("claimed_by", claimers);
            }
        }

        if (blobIdsRaw instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) blobIdsRaw).entrySet()) {
                Map<String, Object> info = players.computeIfAbsent(String.valueOf(e.getKey()), k -> newPlayerRecord());
                resolveNested(info, "blob").put("id", String.valueOf(e.getValue()));
            }
        }

        if (blobClaimsRaw instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) blobClaimsRaw).entrySet()) {
                List<String> claimers = new ArrayList<>();
                if (e.getValue() instanceof List)
                    for (Object o : (List<Object>) e.getValue()) claimers.add(String.valueOf(o));
                else claimers.add(String.valueOf(e.getValue()));
                Map<String, Object> info = players.computeIfAbsent(String.valueOf(e.getKey()), k -> newPlayerRecord());
                resolveNested(info, "blob").put("claimed_by", claimers);
            }
        }

        playersConfig.set("players", players);
        playersConfig.remove("identifiers");
        playersConfig.remove("pack_claims");
        playersConfig.remove("assigned_packs");
        playersConfig.remove("blob_ids");
        playersConfig.remove("blob_claims");
        playersConfig.save();
    }

    @SuppressWarnings("unchecked")
    public void normalizePlayers() {
        Map<String, Map<String, Object>> players = getPlayers();
        boolean changed = false;
        for (Map<String, Object> info : players.values()) {
            if (!info.containsKey("name")) { info.put("name", ""); changed = true; }
            if (!info.containsKey("aliases")) { info.put("aliases", new ArrayList<>()); changed = true; }
            if (!info.containsKey("ResourcePackSpoof")) { info.put("ResourcePackSpoof", false); changed = true; }
            if (!info.containsKey("ids")) { info.put("ids", new LinkedHashMap<>()); changed = true; }
            if (!info.containsKey("pack")) { info.put("pack", new LinkedHashMap<>()); changed = true; }
            if (!info.containsKey("blob")) { info.put("blob", new LinkedHashMap<>()); changed = true; }
        }
        if (changed) {
            playersConfig.reload();
            playersConfig.set("players", players);
            playersConfig.save();
        }
    }

    /* ================================================================== */
    /*  维度视图（供检测器与命令查询）                                       */
    /* ================================================================== */

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, List<String>>> getIdentifiers() {
        Map<String, Map<String, List<String>>> r = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> pe : getPlayers().entrySet()) {
            String uuid = pe.getKey();
            Map<String, Object> ids = asMap(pe.getValue().get("ids"));
            if (ids == null) continue;
            for (Map.Entry<String, Object> ie : ids.entrySet()) {
                String identifier = String.valueOf(ie.getValue());
                if (identifier.isEmpty() || "null".equalsIgnoreCase(identifier)) continue;
                r.computeIfAbsent(ie.getKey(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(identifier, k -> new ArrayList<>())
                        .add(uuid);
            }
        }
        return r;
    }

    public Map<String, List<String>> getPackClaims() {
        Map<String, List<String>> r = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> pe : getPlayers().entrySet()) {
            Map<String, Object> pack = asMap(pe.getValue().get("pack"));
            if (pack == null) continue;
            Object cb = pack.get("claimed_by");
            if (cb instanceof List) {
                List<String> list = new ArrayList<>();
                for (Object o : (List<Object>) cb) list.add(String.valueOf(o));
                if (!list.isEmpty()) r.put(pe.getKey(), list);
            }
        }
        return r;
    }

    public Map<String, String> getAssignedPacks() {
        Map<String, String> r = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> pe : getPlayers().entrySet()) {
            Map<String, Object> pack = asMap(pe.getValue().get("pack"));
            if (pack == null) continue;
            Object pu = pack.get("uuid");
            if (pu != null && !String.valueOf(pu).isEmpty() && !"null".equalsIgnoreCase(String.valueOf(pu)))
                r.put(pe.getKey(), String.valueOf(pu));
        }
        return r;
    }

    /* ================================================================== */
    /*  身份档案与标识符写入                                                */
    /* ================================================================== */

    public void storePlayerProfile(String playerUuid, String playerName, String xuid) {
        Map<String, Map<String, Object>> players = getPlayers();
        Map<String, Object> info = players.get(playerUuid);
        boolean changed = false;

        if (info == null) {
            info = new LinkedHashMap<>();
            info.put("name", playerName);
            if (xuid != null && !xuid.isEmpty()) info.put("xuid", xuid);
            List<String> aliases = new ArrayList<>();
            aliases.add(playerName);
            info.put("aliases", aliases);
            info.put("ResourcePackSpoof", false);
            info.put("ids", new LinkedHashMap<>());
            info.put("pack", new LinkedHashMap<>());
            info.put("blob", new LinkedHashMap<>());
            players.put(playerUuid, info);
            changed = true;
        } else {
            if (!playerName.equals(info.get("name"))) { info.put("name", playerName); changed = true; }
            if (xuid != null && !xuid.isEmpty() && !xuid.equals(info.get("xuid"))) { info.put("xuid", xuid); changed = true; }
            Object aliasesObj = info.get("aliases");
            List<String> aliases = new ArrayList<>();
            if (aliasesObj instanceof List) for (Object o : (List<?>) aliasesObj) aliases.add(String.valueOf(o));
            if (!aliases.contains(playerName)) { aliases.add(playerName); info.put("aliases", aliases); changed = true; }
            if (!info.containsKey("ResourcePackSpoof")) { info.put("ResourcePackSpoof", false); changed = true; }
        }

        // 收养同名空壳记录：首次检测（可能发生在未认证连接上）留下的壳——pack 已分配给该
        // 设备且客户端可能已缓存——搬到当前身份名下并删除壳，保证下次重连按 UUID 就能命中，
        // 同时 players.yml 里不再残留 name 缺失的空玩家
        if (adoptShellRecords(playerUuid, playerName, info, players)) changed = true;

        if (changed) {
            playersConfig.reload();
            playersConfig.set("players", players);
            playersConfig.save();
        }
    }

    public void storeIdentifier(String type, String identifier, String playerUuid) {
        if (identifier == null || identifier.isEmpty()) return;
        playersConfig.reload();
        Map<String, Map<String, Object>> players = getPlayers();
        Map<String, Object> info = players.get(playerUuid);
        if (info == null) {
            info = newPlayerRecord();
            info.put("name", playerUuid);
            players.put(playerUuid, info);
        }
        Map<String, Object> ids = resolveNested(info, "ids");
        if (!identifier.equals(ids.get(type))) {
            ids.put(type, identifier);
            playersConfig.set("players", players);
            playersConfig.save();
        }
    }

    public void addPackClaim(String claimerUuid, String ownerUuid) {
        if (ownerUuid == null) return;
        playersConfig.reload();
        Map<String, Map<String, Object>> players = getPlayers();
        Map<String, Object> info = players.get(ownerUuid);
        if (info == null) return;
        Map<String, Object> pack = resolveNested(info, "pack");
        List<String> list = new ArrayList<>();
        Object cb = pack.get("claimed_by");
        if (cb instanceof List) for (Object o : (List<Object>) cb) list.add(String.valueOf(o));
        if (!list.contains(claimerUuid)) {
            list.add(claimerUuid);
            pack.put("claimed_by", list);
            playersConfig.set("players", players);
            playersConfig.save();
        }
    }

    /* ================================================================== */
    /*  Spoof 标记                                                        */
    /* ================================================================== */

    public boolean isSpoofFlagged(String playerUuid) {
        Map<String, Object> info = getPlayers().get(playerUuid);
        if (info == null) return false;
        Object flag = info.get("ResourcePackSpoof");
        if (flag instanceof Boolean) return (Boolean) flag;
        return "true".equalsIgnoreCase(String.valueOf(flag));
    }

    public void markSpoof(String playerUuid) {
        Map<String, Map<String, Object>> players = getPlayers();
        Map<String, Object> info = players.get(playerUuid);
        if (info == null) {
            info = newPlayerRecord();
            info.put("name", playerUuid);
            players.put(playerUuid, info);
        }
        Object current = info.get("ResourcePackSpoof");
        boolean already = current instanceof Boolean ? (Boolean) current
                : "true".equalsIgnoreCase(String.valueOf(current));
        if (already) return;
        info.put("ResourcePackSpoof", true);
        players.put(playerUuid, info);
        playersConfig.reload();
        playersConfig.set("players", players);
        playersConfig.save();
    }

    /* ================================================================== */
    /*  空壳记录收养（身份切换兜底，见 PackDetector）                        */
    /* ================================================================== */

    /**
     * 空壳记录判定：从未走过 PlayerLoginEvent 的记录。
     * aliases 为空即壳（登录成功时总会把玩家名写进 aliases）；
     * 兼容存量数据：lastMs 存在且非 0 的老记录一定登录过，不是壳。
     */
    public boolean isShellRecord(Map<String, Object> info) {
        if (info == null) return false;
        Object lm = info.get("lastMs");
        if (lm instanceof Number && ((Number) lm).longValue() != 0L) return false;
        Object al = info.get("aliases");
        if (al instanceof List && !((List<?>) al).isEmpty()) return false;
        return true;
    }

    /** 按名字查找可收养的空壳记录的 pack uuid（忽略大小写；只认从未完成登录的壳）。 */
    public String findAdoptableShellPack(String playerName, String excludeUuid) {
        if (playerName == null || playerName.isEmpty()) return null;
        try {
            for (Map.Entry<String, Map<String, Object>> pe : getPlayers().entrySet()) {
                if (pe.getKey().equalsIgnoreCase(excludeUuid)) continue;
                Map<String, Object> info = pe.getValue();
                if (!isShellRecord(info)) continue;
                Object nm = info.get("name");
                if (nm == null || String.valueOf(nm).trim().isEmpty()
                        || !String.valueOf(nm).equalsIgnoreCase(playerName)) continue;
                Map<String, Object> pack = asMap(info.get("pack"));
                if (pack == null) continue;
                Object pu = pack.get("uuid");
                if (pu != null && !String.valueOf(pu).isEmpty() && !"null".equalsIgnoreCase(String.valueOf(pu)))
                    return String.valueOf(pu);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 把玩家名匹配到的所有空壳记录并进当前记录后删除壳（只搬当前记录缺失的键：
     * pack.uuid/claimed_by/blob/ids）。只改传入的 players 副本，不自行落盘，由调用方统一保存。
     *
     * @return 是否收养了至少一条壳记录
     */
    public boolean adoptShellRecords(String playerUuid, String playerName, Map<String, Object> current,
                                     Map<String, Map<String, Object>> players) {
        if (playerName == null || playerName.isEmpty() || current == null || players == null) return false;
        List<String> shells = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> pe : players.entrySet()) {
            if (pe.getKey().equalsIgnoreCase(playerUuid)) continue;
            Map<String, Object> info = pe.getValue();
            if (!isShellRecord(info)) continue;
            Object nm = info.get("name");
            if (nm == null || String.valueOf(nm).trim().isEmpty()
                    || !String.valueOf(nm).equalsIgnoreCase(playerName)) continue;
            shells.add(pe.getKey());
        }
        if (shells.isEmpty()) return false;
        for (String shellKey : shells) {
            Map<String, Object> shell = players.get(shellKey);
            if (shell == null) continue;
            Map<String, Object> shellPack = resolveNested(shell, "pack");
            Map<String, Object> curPack = resolveNested(current, "pack");
            Object su = shellPack.get("uuid");
            boolean suValid = su != null && !String.valueOf(su).isEmpty() && !"null".equalsIgnoreCase(String.valueOf(su));
            Object cu = curPack.get("uuid");
            boolean cuEmpty = cu == null || String.valueOf(cu).isEmpty() || "null".equalsIgnoreCase(String.valueOf(cu));
            if (suValid && cuEmpty) curPack.put("uuid", String.valueOf(su));
            Object scb = shellPack.get("claimed_by");
            if (scb instanceof List && !((List<?>) scb).isEmpty() && curPack.get("claimed_by") == null)
                curPack.put("claimed_by", scb);
            mergeShellNested(shell, current, "blob");
            mergeShellNested(shell, current, "ids");
            players.remove(shellKey);
        }
        return true;
    }

    /** 空壳数据合并：from 的嵌套 map 中 to 缺失的键搬过去。 */
    private void mergeShellNested(Map<String, Object> from, Map<String, Object> to, String key) {
        Map<String, Object> src = asMap(from.get(key));
        if (src == null || src.isEmpty()) return;
        Map<String, Object> dst = resolveNested(to, key);
        for (Map.Entry<String, Object> e : src.entrySet())
            if (!dst.containsKey(e.getKey())) dst.put(e.getKey(), e.getValue());
    }

    /* ================================================================== */
    /*  资源包指纹分配（PackDetector 调用）                                 */
    /* ================================================================== */

    /**
     * 确保玩家记录存在且已分配指纹 pack，返回 pack uuid。
     * 记录不存在时以真实玩家名建空壳（此记录在重连成功前是"空壳"，
     * 若玩家下次连接时 Xbox 认证状态变化导致 UUID 切换（见 PackDetector），
     * 需要靠名字找回这条壳记录；带名字的残留也便于人工识别）。
     */
    public String ensurePackAssigned(String playerUuidStr, String playerName) {
        playersConfig.reload();
        Map<String, Map<String, Object>> players = getPlayers();
        Map<String, Object> info = players.get(playerUuidStr);
        if (info == null) {
            info = newPlayerRecord();
            info.put("name", (playerName != null && !playerName.isEmpty()) ? playerName : playerUuidStr);
            players.put(playerUuidStr, info);
        }
        Map<String, Object> pack = resolveNested(info, "pack");
        Object pu = pack.get("uuid");
        String newPack = (pu != null && !String.valueOf(pu).isEmpty() && !"null".equalsIgnoreCase(String.valueOf(pu)))
                ? String.valueOf(pu) : null;
        if (newPack == null) {
            newPack = UUID.randomUUID().toString();
            pack.put("uuid", newPack);
            playersConfig.set("players", players);
            playersConfig.save();
        }
        return newPack;
    }

    /* ================================================================== */
    /*  Blob 指纹落盘（BlobDetector 调用）                                  */
    /* ================================================================== */

    public void saveBlobIds(Map<String, String> blobIds) {
        playersConfig.reload();
        Map<String, Map<String, Object>> players = getPlayers();
        for (Map.Entry<String, String> e : blobIds.entrySet()) {
            Map<String, Object> info = players.get(e.getKey());
            if (info == null) continue;
            Map<String, Object> blob = resolveNested(info, "blob");
            blob.put("id", e.getValue());
        }
        playersConfig.set("players", players);
        playersConfig.save();
    }

    public void saveBlobClaims(Map<String, List<String>> blobClaims) {
        playersConfig.reload();
        Map<String, Map<String, Object>> players = getPlayers();
        for (Map.Entry<String, List<String>> e : blobClaims.entrySet()) {
            Map<String, Object> info = players.get(e.getKey());
            if (info == null) continue;
            Map<String, Object> blob = resolveNested(info, "blob");
            blob.put("claimed_by", new ArrayList<>(e.getValue()));
        }
        playersConfig.set("players", players);
        playersConfig.save();
    }

    /* ================================================================== */
    /*  展示辅助                                                          */
    /* ================================================================== */

    /** 存量记录兼容读取：仅用于 /alias 排序，新记录不再写入该字段。 */
    public long lastMs(String uuid) {
        Map<String, Object> p = getPlayers().get(uuid);
        if (p == null) return 0L;
        Object v = p.get("lastMs");
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    public String displayName(String uuid) {
        Map<String, Object> profile = getPlayers().get(uuid);
        return profile != null && profile.get("name") != null ? String.valueOf(profile.get("name")) : uuid;
    }

    public String getDisplayIdentifier(String uuid) {
        Map<String, Object> info = getPlayers().get(uuid);
        if (info != null) {
            Object xuid = info.get("xuid");
            if (xuid != null && !String.valueOf(xuid).isEmpty() && !String.valueOf(xuid).equals("null"))
                return "xuid: " + xuid;
        }
        return "uuid: " + uuid;
    }

    /** 统计全服当前名同名（忽略大小写）的账号数，用于决定 /alias 输出是否需要附带 xuid/uuid 消歧。 */
    public Map<String, Integer> buildNameCollisionMap() {
        Map<String, Integer> count = new HashMap<>();
        for (Map<String, Object> info : getPlayers().values()) {
            Object n = info.get("name");
            if (n == null) continue;
            count.merge(String.valueOf(n).toLowerCase(), 1, Integer::sum);
        }
        return count;
    }
}
