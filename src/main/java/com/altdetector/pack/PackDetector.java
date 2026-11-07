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
 * <p>流程：玩家首次连接收到全部已分配指纹包 → 按客户端响应记录缓存命中（该设备玩过
 * 哪些账号）→ {@code assignPack} 分配专属包并记住，下载阶段用他自己的包顶替数据，
 * 客户端据此把清单里的每个 uuid 都存下来。客户端回 HAVE_ALL_PACKS 直接断开。
 *
 * <p>身份切换兜底：MOT 对 Xbox 验签失败的连接改用"OfflinePlayer:名字"派生的离线 UUID，
 * 验签成功则用认证 UUID。首次检测若发生在未认证连接上，壳记录挂在旧 UUID 名下；重连一旦
 * 认证成功按 UUID 就查不到 pack，会 fall-through 把玩家再踢一次（表现为"偶尔初始化两次"
 * +残留空壳记录）。见 {@link #onPackInfoSend(DataPacketSendEvent)} 中的收养逻辑。
 */
public class PackDetector implements Listener {

    private final AltDetector plugin;
    private final PlayerStore store;

    private final Map<UUID, List<String>> sentPacks = new HashMap<>();
    private final Map<String, FingerprintPack> packCache = new HashMap<>();
    private final PackDownloadShim downloadShim = new PackDownloadShim();

    public PackDetector(AltDetector plugin, PlayerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /** 下载替换层，需要在主类里注册事件。 */
    public PackDownloadShim getDownloadShim() {
        return downloadShim;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sentPacks.remove(event.getPlayer().getUniqueId());
        downloadShim.forget(event.getPlayer());
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

        if (playerPack != null && !playerPack.isEmpty() && store.isPackConfirmed(playerUuidStr)) {
            // 已经完成过初始化：只下发服务器自己的资源包
            keepServerPacks(pk, null);
            return;
        }

        // 身份切换兜底：MOT 对 Xbox 验签失败的连接改用"OfflinePlayer:名字"派生的离线 UUID，
        // 验签成功则用认证 UUID。首次检测若发生在未认证连接上，壳记录挂在旧 UUID 名下；
        // 重连一旦认证成功，这里按 UUID 就查不到 pack，会 fall-through 再初始化一次。
        // 因此按名字收养空壳记录（aliases 为空，即从未完成登录的），按已初始化放行。
        if (playerPack == null || playerPack.isEmpty()) {
            String adopted = store.findAdoptableShellPack(player.getName(), playerUuidStr);
            if (adopted != null) {
                keepServerPacks(pk, null);
                return;
            }
        }

        List<String> packsToSend = new ArrayList<>(assignedPacks.values());

        // 补上他自己刚分配的指纹包：客户端不可能预先拥有它，
        // 初始化阶段真回 HAVE_ALL_PACKS 就只能是谎报
        if (playerPack == null || playerPack.isEmpty()) {
            playerPack = store.ensurePackAssigned(playerUuidStr, player.getName());
        }
        if (playerPack != null && !playerPack.isEmpty() && !packsToSend.contains(playerPack))
            packsToSend.add(playerPack);

        sentPacks.put(playerUuid, new ArrayList<>(packsToSend));

        List<ResourcePack> packs = new ArrayList<>();
        for (String p : packsToSend) packs.add(getOrCreatePack(p));

        setResourcePackEntries(pk, packs);
    }

    /* 已初始化过的账号：清单只留服务器自己的资源包，指纹包不再下发；extra 非空时追加指定包 */
    private void keepServerPacks(ResourcePacksInfoPacket pk, ResourcePack extra) {
        try {
            Field f = ResourcePacksInfoPacket.class.getDeclaredField("resourcePackEntries");
            f.setAccessible(true);
            ResourcePack[] arr = (ResourcePack[]) f.get(pk);
            List<ResourcePack> kept = new ArrayList<>();
            if (arr != null) {
                for (ResourcePack p : arr) if (!(p instanceof FingerprintPack)) kept.add(p);
            }
            if (extra != null) kept.add(extra);
            f.set(pk, kept.toArray(new ResourcePack[0]));
        } catch (Exception e) {
            plugin.getLogger().warning("[Setup] failed: " + e.getMessage());
        }
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
            plugin.getLogger().warning("[Setup] failed: " + e.getMessage());
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
        if (sentList == null) {
            // 这一连接没下发指纹包；客户端报下载完成，说明它确实把包拿全了
            if (status == ResourcePackClientResponsePacket.STATUS_COMPLETED
                    && !store.isPackConfirmed(playerUuidStr)) {
                store.markPackConfirmed(playerUuidStr);
            }
            return;
        }

        if (sentList.isEmpty()) {
            if (status == ResourcePackClientResponsePacket.STATUS_HAVE_ALL_PACKS
                    || status == ResourcePackClientResponsePacket.STATUS_COMPLETED
                    || status == ResourcePackClientResponsePacket.STATUS_SEND_PACKS) {
                assignPack(player, playerUuidStr);
            }
            return;
        }

        if (status == ResourcePackClientResponsePacket.STATUS_HAVE_ALL_PACKS) {
            sentPacks.remove(playerUuid);
            if (!plugin.resourcePackSpoofEnabled()) return; // 检测关闭：交给 Nukkit 放行
            // 客户端声称持有刚分配给他的包，只可能是谎报：先触发惩罚，再断开
            plugin.punish(playerUuidStr);
            player.close();
            return;
        }

        if (status == ResourcePackClientResponsePacket.STATUS_SEND_PACKS) {
            List<String> packEntries = extractPackEntries(rp);
            List<String> cachedUuids = new ArrayList<>(sentList);
            cachedUuids.removeAll(packEntries);

            if (!cachedUuids.isEmpty()) {
                Map<String, String> assignedPacks = store.getAssignedPacks();
                for (String cachedUuid : cachedUuids) {
                    String ownerUuid = findOwnerByPackUuid(cachedUuid, assignedPacks);
                    if (ownerUuid != null && !ownerUuid.equals(playerUuidStr))
                        store.addPackClaim(playerUuidStr, ownerUuid);
                }
            }
            assignPack(player, playerUuidStr);
            return;
        }

        if (status == ResourcePackClientResponsePacket.STATUS_COMPLETED) {
            // 没经过下载请求就报完成，同样是谎报，直接断开
            sentPacks.remove(playerUuid);
            player.close();
        }
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
            plugin.getLogger().warning("[Extract] failed: " + e.getMessage());
        }
        return uuids;
    }

    private String findOwnerByPackUuid(String packUuid, Map<String, String> assignedPacks) {
        String target = packUuid.toLowerCase();
        for (Map.Entry<String, String> entry : assignedPacks.entrySet())
            if (entry.getValue().toLowerCase().equals(target)) return entry.getKey();
        return null;
    }

    /* 首次检测通过：分配专属包并记住，下载阶段统一用它顶替 */
    private void assignPack(Player player, String playerUuidStr) {
        String packUuid = store.ensurePackAssigned(playerUuidStr, player.getName());
        FingerprintPack pack = getOrCreatePack(packUuid);

        downloadShim.remember(player, pack);
        sentPacks.remove(player.getUniqueId());
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
        downloadShim.markFingerprint(pack.getPackId());
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
            plugin.getLogger().warning("[Register] error: " + e.getMessage());
        }
    }
}
