package com.altdetector.pack;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.server.DataPacketReceiveEvent;
import cn.nukkit.event.server.DataPacketSendEvent;
import cn.nukkit.network.protocol.ResourcePackClientResponsePacket;
import cn.nukkit.network.protocol.ResourcePacksInfoPacket;
import cn.nukkit.resourcepacks.ResourcePack;
import cn.nukkit.resourcepacks.ResourcePackManager;
import com.altdetector.AltDetector;
import com.altdetector.store.PlayerStore;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 资源包指纹维度检测。
 *
 * <p>流程（无踢出，单次登录完成）：新账号首次连接收到 info 包时当场分配专属指纹包并注册，
 * info 一次性下发全部指纹包（含自己的）→ 客户端下载缺失包并缓存 → 按客户端响应记录
 * 缓存命中（该设备玩过哪些账号，claim 单向记录在指纹主人名下）→ 直接放行进服。
 * 已分配的老账号不再下发指纹包列表，客户端秒过。
 *
 * <p>客户端缓存的指纹包即"该设备玩过此账号"的凭证：同设备后续账号的 info 响应里
 * 不会包含已缓存的包，据此建立关联。
 */
public class PackDetector implements Listener {

    private final AltDetector plugin;
    private final PlayerStore store;

    private final Map<UUID, List<String>> sentPacks = new HashMap<>();
    private final Map<String, FingerprintPack> packCache = new HashMap<>();

    public PackDetector(AltDetector plugin, PlayerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sentPacks.remove(event.getPlayer().getUniqueId());
    }

    /* ================================================================== */
    /*  数据包处理                                                        */
    /* ================================================================== */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPackInfoSend(DataPacketSendEvent event) {
        if (event.isCancelled()) return;
        if (!plugin.packEnabled()) return; // 资源包指纹维度总开关
        if (!(event.getPacket() instanceof ResourcePacksInfoPacket)) return;

        Player player = event.getPlayer();
        if (player == null) return;

        ResourcePacksInfoPacket pk = (ResourcePacksInfoPacket) event.getPacket();
        UUID playerUuid = player.getUniqueId();
        String playerUuidStr = playerUuid.toString();

        Map<String, String> assignedPacks = store.getAssignedPacks();
        String playerPack = assignedPacks.get(playerUuidStr);

        if (playerPack != null && !playerPack.isEmpty()) {
            // 已分配的老账号：不再下发指纹包列表（客户端已有缓存，秒过）
            setResourcePackEntries(pk, new ArrayList<>());
            return;
        }

        // 新账号：当场分配专属指纹包，info 一次性下发全部指纹包（含自己的），
        // 客户端在本回合下载并缓存自己的指纹，无需踢出重连
        playerPack = store.ensurePackAssigned(playerUuidStr, player.getName());
        getOrCreatePack(playerPack);
        assignedPacks = store.getAssignedPacks();

        List<String> packsToSend = new ArrayList<>(assignedPacks.values());
        sentPacks.put(playerUuid, packsToSend);

        List<ResourcePack> packs = new ArrayList<>();
        for (String p : packsToSend) packs.add(getOrCreatePack(p));

        setResourcePackEntries(pk, packs);
    }

    private void setResourcePackEntries(ResourcePacksInfoPacket pk, List<ResourcePack> packs) {
        try {
            Field f1 = ResourcePacksInfoPacket.class.getDeclaredField("resourcePackEntries");
            f1.setAccessible(true);
            f1.set(pk, packs.toArray(new ResourcePack[0]));

            Field f2 = ResourcePacksInfoPacket.class.getDeclaredField("behaviourPackEntries");
            f2.setAccessible(true);
            f2.set(pk, new ResourcePack[0]);
        } catch (Exception e) {
            plugin.getLogger().warning("[设置] 失败: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPackResponse(DataPacketReceiveEvent event) {
        if (!plugin.packEnabled()) return; // 资源包指纹维度总开关
        if (!(event.getPacket() instanceof ResourcePackClientResponsePacket)) return;

        ResourcePackClientResponsePacket rp = (ResourcePackClientResponsePacket) event.getPacket();
        Player player = event.getPlayer();
        if (player == null) return;

        UUID playerUuid = player.getUniqueId();
        String playerUuidStr = playerUuid.toString();

        byte status = rp.responseStatus;

        List<String> sentList = sentPacks.get(playerUuid);
        if (sentList == null) return; // 老账号（未下发指纹包列表）的响应不参与检测

        if (status == ResourcePackClientResponsePacket.STATUS_HAVE_ALL_PACKS) {
            // 客户端声称已缓存全部下发的指纹包（含刚分配、不可能有缓存的自己的包）
            // → 谎报/伪造，标记 Spoof，且全部按命中入账（与旧逻辑一致）
            if (sentList.size() >= 3) store.markSpoof(playerUuidStr);
            recordClaims(playerUuidStr, sentList, new ArrayList<>());
            sentPacks.remove(playerUuid);
            return;
        }

        if (status == ResourcePackClientResponsePacket.STATUS_SEND_PACKS) {
            List<String> packEntries = extractPackEntries(rp);
            recordClaims(playerUuidStr, sentList, packEntries);
            sentPacks.remove(playerUuid);
            return;
        }

        if (status == ResourcePackClientResponsePacket.STATUS_COMPLETED) {
            store.markSpoof(playerUuidStr);
            sentPacks.remove(playerUuid);
        }
    }

    /**
     * 记录缓存命中：sent 中存在但 needed 中没有的包 = 客户端已缓存 = 该设备玩过对应账号。
     * claim 单向记录在指纹主人名下（claimed_by）；命中已惩罚账号的指纹时，在全部 claim
     * 记录完毕后统一惩罚命中者。
     */
    private void recordClaims(String claimerUuid, List<String> sentList, List<String> needed) {
        List<String> cached = new ArrayList<>(sentList);
        cached.removeAll(needed);

        Map<String, String> assignedPacks = store.getAssignedPacks();
        boolean punishClaimer = false;
        for (String packUuid : cached) {
            String ownerUuid = findOwnerByPackUuid(packUuid, assignedPacks);
            if (ownerUuid != null && !ownerUuid.equals(claimerUuid)
                    && store.addPackClaim(claimerUuid, ownerUuid)
                    && plugin.isPunished(ownerUuid))
                punishClaimer = true; // 命中已惩罚账号的指纹（同设备）
        }
        if (punishClaimer) plugin.punish(claimerUuid, false); // 检测记录完毕后再惩罚
    }

    private List<String> extractPackEntries(ResourcePackClientResponsePacket rp) {
        List<String> uuids = new ArrayList<>();
        try {
            Field f = ResourcePackClientResponsePacket.class.getDeclaredField("packEntries");
            f.setAccessible(true);
            Object value = f.get(rp);
            if (value instanceof Object[]) {
                for (Object entry : (Object[]) value) {
                    if (entry == null) continue;
                    String str = entry.toString();
                    if (str.contains("uuid=")) {
                        String uuidStr = str.substring(str.indexOf("uuid=") + 5);
                        if (uuidStr.contains(",")) uuidStr = uuidStr.substring(0, uuidStr.indexOf(","));
                        uuids.add(uuidStr.trim().toLowerCase());
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[提取] 失败: " + e.getMessage());
        }
        return uuids;
    }

    private String findOwnerByPackUuid(String packUuid, Map<String, String> assignedPacks) {
        String target = packUuid.toLowerCase();
        for (Map.Entry<String, String> entry : assignedPacks.entrySet())
            if (entry.getValue().toLowerCase().equals(target)) return entry.getKey();
        return null;
    }

    /* ================================================================== */
    /*  指纹包生成与注册                                                   */
    /* ================================================================== */

    private FingerprintPack getOrCreatePack(String packUuid) {
        FingerprintPack existing = packCache.get(packUuid);
        if (existing != null) return existing;
        int stableIndex = Math.abs(packUuid.hashCode());
        FingerprintPack pack = new FingerprintPack(UUID.fromString(packUuid), stableIndex);
        packCache.put(packUuid, pack);
        registerPackWithManager(pack);
        return pack;
    }

    @SuppressWarnings("unchecked")
    private void registerPackWithManager(FingerprintPack pack) {
        try {
            ResourcePackManager manager = plugin.getServer().getResourcePackManager();
            if (manager == null) return;
            try {
                Field f = ResourcePackManager.class.getDeclaredField("allPacksById");
                f.setAccessible(true);
                ((Map<UUID, ResourcePack>) f.get(manager)).put(pack.getPackId(), pack);
            } catch (NoSuchFieldException ignored) {}
            try {
                Field f = ResourcePackManager.class.getDeclaredField("resourcePacks");
                f.setAccessible(true);
                ((Set<ResourcePack>) f.get(manager)).add(pack);
            } catch (NoSuchFieldException ignored) {}
            try {
                Field f = ResourcePackManager.class.getDeclaredField("resourcePacksById");
                f.setAccessible(true);
                ((Map<UUID, ResourcePack>) f.get(manager)).put(pack.getPackId(), pack);
            } catch (NoSuchFieldException ignored) {}
        } catch (Exception e) {
            plugin.getLogger().warning("[注册] 异常: " + e.getMessage());
        }
    }
}
