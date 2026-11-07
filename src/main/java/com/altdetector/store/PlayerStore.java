package com.altdetector.store;

import cn.nukkit.utils.Config;
import com.altdetector.util.Uuids;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * players.yml 数据层：玩家档案、检测标识符、资源包/Blob 指纹关联的全部读写。
 *
 * <p>players 下的键是<b>身份键</b>：xbox-auth=off 用 UUID，开启用 XUID。
 * <pre>
 * name / aliases / uuid —— 身份（uuid 即存储键）
 * ids: {device, selfSigned, clientRandom, networkid} —— 登录标识符与 NetherNet NetworkID
 * pack: {uuid, confirmed, claimed_by} —— 资源包指纹、下载确认与命中者
 * blob: {id, claimed_by}       —— Blob 指纹与命中者
 * </pre>
 *
 * <p>写操作先 {@code reload()} 再 {@code save()}；只读操作返回副本，调用方改动不影响磁盘。
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

    /** 单条记录副本；语义等同 getPlayers().get(key)，但不复制整张表。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPlayer(String key) {
        Object raw = playersConfig.get("players");
        if (!(raw instanceof Map)) return null;
        Object v = ((Map<Object, Object>) raw).get(key);
        if (!(v instanceof Map)) return null;
        Map<String, Object> inner = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> ie : ((Map<Object, Object>) v).entrySet())
            inner.put(String.valueOf(ie.getKey()), ie.getValue());
        return inner;
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
        info.put("uuid", "");
        info.put("Punished", false);
        info.put("ids", new LinkedHashMap<>());
        info.put("pack", new LinkedHashMap<>());
        info.put("blob", new LinkedHashMap<>());
        return info;
    }

    /** 以 UUID 新建记录。 */
    public static Map<String, Object> newPlayerRecordFor(String playerUuid) {
        Map<String, Object> info = newPlayerRecord();
        String key = Uuids.normalize(playerUuid);
        info.put("uuid", Uuids.isUuid(key) ? key : "");
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
        for (Map.Entry<String, Map<String, Object>> entry : players.entrySet()) {
            Map<String, Object> info = entry.getValue();
            if (!info.containsKey("name")) { info.put("name", ""); changed = true; }
            if (!info.containsKey("aliases")) { info.put("aliases", new ArrayList<>()); changed = true; }
            if (!info.containsKey("uuid")) { info.put("uuid", ""); changed = true; }
            if (!info.containsKey("Punished")) { info.put("Punished", false); changed = true; }
            if (!info.containsKey("ids")) { info.put("ids", new LinkedHashMap<>()); changed = true; }
            if (!info.containsKey("pack")) { info.put("pack", new LinkedHashMap<>()); changed = true; }
            if (!info.containsKey("blob")) { info.put("blob", new LinkedHashMap<>()); changed = true; }
            // 键本身是 UUID 时回填 uuid 字段
            String key = Uuids.normalize(entry.getKey());
            if (isEmptyValue(info.get("uuid")) && Uuids.isUuid(key)) {
                info.put("uuid", key); changed = true;
            }
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

    /** 一次落盘写完整条登录：档案 + 登录时抓到的全部标识符。 */
    public String storeLogin(String playerUuid, String playerName, Map<String, String> identifiers) {
        playersConfig.reload();
        Map<String, Map<String, Object>> players = getPlayers();
        String key = Uuids.normalize(playerUuid);
        ProfileWrite w = applyProfile(players, key, playerName);
        if (!w.skipped) {
            Map<String, Object> info = players.get(key);
            for (Map.Entry<String, String> e : identifiers.entrySet()) {
                String id = e.getValue();
                if (id == null || id.isEmpty()) continue;
                Map<String, Object> ids = resolveNested(info, "ids");
                if (!id.equals(ids.get(e.getKey()))) { ids.put(e.getKey(), id); w.changed = true; }
            }
        }
        if (w.changed) {
            playersConfig.set("players", players);
            playersConfig.save();
        }
        return w.adopted;
    }

    /** 档案写入的公共部分：只改内存副本，不落盘。 */
    private ProfileWrite applyProfile(Map<String, Map<String, Object>> players, String key, String playerName) {
        ProfileWrite w = new ProfileWrite();
        if (key.isEmpty() || playerName == null) { w.skipped = true; return w; }
        // 老版本可能把档案存在别的键下（例如 xuid），按 uuid 字段并回来
        String adopted = adoptLegacyRecords(players, key);
        boolean changed = adopted != null;

        Map<String, Object> info = players.get(key);
        if (info == null) {
            info = newPlayerRecordFor(key);
            info.put("name", playerName);
            List<String> aliases = new ArrayList<>();
            aliases.add(playerName);
            info.put("aliases", aliases);
            players.put(key, info);
            changed = true;
        } else {
            if (!playerName.equals(info.get("name"))) { info.put("name", playerName); changed = true; }
            Object aliasesObj = info.get("aliases");
            List<String> aliases = new ArrayList<>();
            if (aliasesObj instanceof List) for (Object o : (List<?>) aliasesObj) aliases.add(String.valueOf(o));
            if (!aliases.contains(playerName)) { aliases.add(playerName); info.put("aliases", aliases); changed = true; }
            if (!info.containsKey("Punished")) { info.put("Punished", false); changed = true; }
        }

        if (!key.equals(info.get("uuid"))) { info.put("uuid", key); changed = true; }

        // 收养同名空壳记录：首次检测（可能发生在未认证连接上）留下的壳——pack 已分配给该
        // 设备且客户端可能已缓存——搬到当前身份名下并删除壳，保证下次重连按身份键就能命中，
        // 同时 players.yml 里不再残留 name 缺失的空玩家
        if (adoptShellRecords(key, playerName, info, players)) changed = true;

        w.adopted = adopted;
        w.changed = changed;
        return w;
    }

    /** applyProfile 的返回值。 */
    private static final class ProfileWrite {
        String adopted;
        boolean changed;
        boolean skipped;
    }

    /** 写入某个维度的标识符（playerUuid 参数实为身份键）。 */
    public void storeIdentifier(String type, String identifier, String playerUuid) {
        if (identifier == null || identifier.isEmpty()) return;
        playersConfig.reload();
        Map<String, Map<String, Object>> players = getPlayers();
        Map<String, Object> info = players.get(playerUuid);
        if (info == null) {
            info = newPlayerRecordFor(playerUuid);
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

    /** @return true = 本次新增的 claim（已存在则返回 false，不重复写盘） */
    public boolean addPackClaim(String claimerUuid, String ownerUuid) {
        if (ownerUuid == null) return false;
        playersConfig.reload();
        Map<String, Map<String, Object>> players = getPlayers();
        Map<String, Object> info = players.get(ownerUuid);
        if (info == null) return false;
        Map<String, Object> pack = resolveNested(info, "pack");
        List<String> list = new ArrayList<>();
        Object cb = pack.get("claimed_by");
        if (cb instanceof List) for (Object o : (List<Object>) cb) list.add(String.valueOf(o));
        if (list.contains(claimerUuid)) return false;
        list.add(claimerUuid);
        pack.put("claimed_by", list);
        playersConfig.set("players", players);
        playersConfig.save();
        return true;
    }

    /** 客户端是否真的把他自己的指纹包下载完了。 */
    public boolean isPackConfirmed(String playerUuid) {
        Map<String, Object> info = getPlayer(Uuids.normalize(playerUuid));
        if (info == null) return false;
        Map<String, Object> pack = resolveNested(info, "pack");
        Object c = pack.get("confirmed");
        if (c instanceof Boolean) return (Boolean) c;
        return "true".equalsIgnoreCase(String.valueOf(c));
    }

    /** 客户端下载完指纹包后置位；之后登录不再重新走初始化。 */
    public void markPackConfirmed(String playerUuid) {
        String key = Uuids.normalize(playerUuid);
        playersConfig.reload();
        Map<String, Map<String, Object>> players = getPlayers();
        Map<String, Object> info = players.get(key);
        if (info == null) return;
        Map<String, Object> pack = resolveNested(info, "pack");
        Object c = pack.get("confirmed");
        if (c instanceof Boolean && (Boolean) c) return;
        pack.put("confirmed", true);
        playersConfig.set("players", players);
        playersConfig.save();
    }

    /* ================================================================== */
    /*  旧字段清理（ResourcePackSpoof 已由直接断连取代）                    */
    /* ================================================================== */

    /** 删掉历史记录里的 ResourcePackSpoof 字段，返回是否改动过。 */
    public boolean purgeSpoofFlags() {
        Map<String, Map<String, Object>> players = getPlayers();
        boolean changed = false;
        for (Map<String, Object> info : players.values()) {
            if (info.remove("ResourcePackSpoof") != null) changed = true;
        }
        if (changed) {
            playersConfig.set("players", players);
            playersConfig.save();
        }
        return changed;
    }

    /* ================================================================== */
    /*  Punished 标记（该账号已被本插件惩罚过）                              */
    /* ================================================================== */

    public boolean isPunishedFlagged(String playerUuid) {
        Map<String, Object> info = getPlayer(playerUuid);
        if (info == null) return false;
        Object flag = info.get("Punished");
        if (flag instanceof Boolean) return (Boolean) flag;
        return "true".equalsIgnoreCase(String.valueOf(flag));
    }

    public void markPunished(String playerUuid) {
        Map<String, Map<String, Object>> players = getPlayers();
        Map<String, Object> info = players.get(playerUuid);
        if (info == null) {
            info = newPlayerRecordFor(playerUuid);
            info.put("name", playerUuid);
            players.put(playerUuid, info);
        }
        Object current = info.get("Punished");
        boolean already = current instanceof Boolean ? (Boolean) current
                : "true".equalsIgnoreCase(String.valueOf(current));
        if (already) return;
        info.put("Punished", true);
        players.put(playerUuid, info);
        playersConfig.reload();
        playersConfig.set("players", players);
        playersConfig.save();
    }

    /* ================================================================== */
    /*  老数据归并（老版本把档案存在 uuid 以外的键下）                        */
    /* ================================================================== */

    /** 登录时把 uuid 字段相同、却挂在别的键下的老档案并进当前键（含 claim 引用重写）；返回被并入的老键，无则 null。 */
    private String adoptLegacyRecords(Map<String, Map<String, Object>> players, String key) {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : players.entrySet()) {
            if (e.getKey().trim().equalsIgnoreCase(key)) continue;
            Object u = e.getValue().get("uuid");
            if (!(u instanceof String)) continue;
            String s = ((String) u).trim();
            if (s.isEmpty() || !s.equalsIgnoreCase(key)) continue;
            stale.add(e.getKey());
        }
        String adopted = null;
        for (String old : stale) {
            Map<String, Object> oldInfo = players.remove(old);
            if (oldInfo == null) continue;
            Map<String, Object> current = players.get(key);
            if (current == null) players.put(key, oldInfo);
            else mergeRecord(current, oldInfo);
            remapClaimReferences(players, old, key);
            adopted = old;
        }
        return adopted;
    }

    /** 老记录并入新记录：只填缺失项，不覆盖已有值。 */
    @SuppressWarnings("unchecked")
    private void mergeRecord(Map<String, Object> target, Map<String, Object> source) {
        if (target == null || source == null) return;
        if (isEmptyValue(target.get("name")) && !isEmptyValue(source.get("name")))
            target.put("name", source.get("name"));
        Object sa = source.get("aliases");
        if (sa instanceof List && !((List<?>) sa).isEmpty()) {
            List<String> merged = new ArrayList<>();
            Object ta = target.get("aliases");
            if (ta instanceof List) for (Object o : (List<?>) ta) merged.add(String.valueOf(o));
            for (Object o : (List<?>) sa) {
                String name = String.valueOf(o);
                if (!merged.contains(name)) merged.add(name);
            }
            target.put("aliases", merged);
        }
        if (isEmptyValue(target.get("uuid")) && !isEmptyValue(source.get("uuid")))
            target.put("uuid", source.get("uuid"));
        for (String nested : new String[]{"ids", "pack", "blob"}) {
            Map<String, Object> src = asMap(source.get(nested));
            if (src == null || src.isEmpty()) continue;
            Map<String, Object> dst = resolveNested(target, nested);
            for (Map.Entry<String, Object> e : src.entrySet()) {
                if (isEmptyValue(dst.get(e.getKey()))) dst.put(e.getKey(), e.getValue());
            }
        }
        // 宁严勿松：丢标记等于放过已确认的小号
        if (asBoolean(source.get("Punished"))) target.put("Punished", true);
    }

    /** claim 引用重写：键改名后其他账号的命中列表仍写着老键，必须同步搬走。 */
    @SuppressWarnings("unchecked")
    private void remapClaimReferences(Map<String, Map<String, Object>> players, String oldKey, String newKey) {
        for (Map<String, Object> info : players.values()) {
            for (String nested : new String[]{"pack", "blob"}) {
                Map<String, Object> m = asMap(info.get(nested));
                if (m == null) continue;
                Object cb = m.get("claimed_by");
                if (!(cb instanceof List)) continue;
                List<String> out = new ArrayList<>();
                boolean changed = false;
                for (Object o : (List<?>) cb) {
                    String v = String.valueOf(o);
                    String nv = v.equals(oldKey) ? newKey : v;
                    if (!nv.equals(v)) changed = true;
                    if (!out.contains(nv)) out.add(nv);
                }
                if (changed) resolveNested(info, nested).put("claimed_by", out);
            }
        }
    }

    /** 记录键（现在就是 UUID）；老版本按 xuid 存的历史记录取记录里的 uuid 字段。 */
    public String uuidOf(String key) {
        String k = Uuids.normalize(key);
        Map<String, Object> info = getPlayer(k);
        if (info != null) {
            Object u = info.get("uuid");
            String s = u == null ? "" : Uuids.normalize(String.valueOf(u));
            if (Uuids.isUuid(s)) return s;
        }
        return k;
    }

    private static boolean asBoolean(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static boolean isEmptyValue(Object v) {
        if (v == null) return true;
        if (v instanceof String) {
            String s = ((String) v).trim();
            return s.isEmpty() || "null".equalsIgnoreCase(s);
        }
        if (v instanceof List) return ((List<?>) v).isEmpty();
        if (v instanceof Map) return ((Map<?, ?>) v).isEmpty();
        return false;
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

    /** 按名字找空壳记录里已分配的指纹包（PackDetector 身份切换兜底用）。 */
    public String findAdoptableShellPack(String playerName, String excludeUuid) {
        if (playerName == null || playerName.isEmpty()) return null;
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
            info = newPlayerRecordFor(playerUuidStr);
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

    public String displayName(String uuid) {
        Map<String, Object> profile = getPlayer(uuid);
        return profile != null && profile.get("name") != null ? String.valueOf(profile.get("name")) : uuid;
    }

    /** 展示用的身份串：有 uuid 字段就用它，否则用键本身。 */
    public String getDisplayIdentifier(String key) {
        Map<String, Object> info = getPlayer(key);
        if (info != null) {
            Object uuid = info.get("uuid");
            String u = uuid == null ? "" : Uuids.normalize(String.valueOf(uuid));
            if (Uuids.isUuid(u)) return "uuid: " + u;
        }
        return "uuid: " + Uuids.normalize(key);
    }

    /** 统计全服当前名同名（忽略大小写）的账号数，用于决定 /alias 输出是否需要附带 uuid 消歧。 */
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
