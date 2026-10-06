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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 资源包指纹维度检测。
 *
 * <p>流程：新玩家首次连接收到全部已分配指纹包 → 按客户端响应记录缓存命中（该设备玩过
 * 哪些账号）→ {@code assignPackAndDisconnect} 分配专属包并踢出重连 → 重连在
 * {@link #RECONNECT_WINDOW_MS} 窗口内走 waitingComplete 路径（发自己的包，
 * HAVE_ALL/COMPLETED 后清除标记放行，不再踢）。
 *
 * <p>身份切换兜底：MOT 对 Xbox 验签失败的连接改用"OfflinePlayer:名字"派生的离线 UUID，
 * 验签成功则用认证 UUID。首次检测若发生在未认证连接上，壳记录挂在旧 UUID 名下；重连一旦
 * 认证成功按 UUID 就查不到 pack，会 fall-through 把玩家再踢一次（表现为"偶尔初始化两次"
 * +残留空壳记录）。见 {@link #onPackInfoSend(DataPacketSendEvent)} 中的收养逻辑。
 */
public class PackDetector implements Listener {

    private static final long RECONNECT_WINDOW_MS = 60000;

    private final AltDetector plugin;
    private final PlayerStore store;

    private final Map<String, Long> pendingReconnect = new HashMap<>();
    private final Set<UUID> waitingComplete = new java.util.HashSet<>();
    private final Map<UUID, List<String>> sentPacks = new HashMap<>();
    private final Map<String, FingerprintPack> packCache = new HashMap<>();

    public PackDetector(AltDetector plugin, PlayerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        waitingComplete.remove(uuid);
        sentPacks.remove(uuid);
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
            Long t = pendingReconnect.get(playerUuidStr);
            if (t != null) {
                if (System.currentTimeMillis() - t < RECONNECT_WINDOW_MS) {
                    FingerprintPack pack = getOrCreatePack(playerPack);
                    waitingComplete.add(playerUuid);
                    sentPacks.put(playerUuid, new ArrayList<>(Collections.singletonList(playerPack)));
                    setResourcePackEntries(pk, Collections.singletonList(pack));
                    return;
                }
                pendingReconnect.remove(playerUuidStr);
            } else {
                setResourcePackEntries(pk, new ArrayList<>());
                return;
            }
        }

        // 身份切换兜底：MOT 对 Xbox 验签失败的连接改用"OfflinePlayer:名字"派生的离线 UUID，
        // 验签成功则用认证 UUID。首次检测若发生在未认证连接上，壳记录和重连标记都挂在旧 UUID
        // 名下；重连一旦认证成功，这里按 UUID 就查不到 pack，会 fall-through 把玩家再踢一次
        // （表现为"偶尔初始化两次"+残留空壳记录）。因此按名字收养空壳记录（aliases 为空，
        // 即从未完成登录的），直接走重连放行路径。只收养空壳：撞上正常同名记录的
        // 玩家仍走常规检测，不产生绕过盲区。
        if (playerPack == null || playerPack.isEmpty()) {
            String adopted = store.findAdoptableShellPack(player.getName(), playerUuidStr);
            if (adopted != null) {
                FingerprintPack pack = getOrCreatePack(adopted);
                if (pack != null) {
                    pendingReconnect.put(playerUuidStr, System.currentTimeMillis());
                    waitingComplete.add(playerUuid);
                    sentPacks.put(playerUuid, new ArrayList<>(Collections.singletonList(adopted)));
                    setResourcePackEntries(pk, Collections.singletonList(pack));
                    return;
                }
            }
        }

        List<String> packsToSend = new ArrayList<>(assignedPacks.values());
        if (playerPack != null && !playerPack.isEmpty()) packsToSend.remove(playerPack);

        sentPacks.put(playerUuid, new ArrayList<>(packsToSend));

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

        if (waitingComplete.contains(playerUuid)) {
            if (status == ResourcePackClientResponsePacket.STATUS_HAVE_ALL_PACKS
                    || status == ResourcePackClientResponsePacket.STATUS_COMPLETED) {
                waitingComplete.remove(playerUuid);
                sentPacks.remove(playerUuid);
                pendingReconnect.remove(playerUuidStr);
            }
            return;
        }

        List<String> sentList = sentPacks.get(playerUuid);
        if (sentList == null) return;

        if (sentList.isEmpty()) {
            if (status == ResourcePackClientResponsePacket.STATUS_HAVE_ALL_PACKS
                    || status == ResourcePackClientResponsePacket.STATUS_COMPLETED
                    || status == ResourcePackClientResponsePacket.STATUS_SEND_PACKS) {
                assignPackAndDisconnect(player, playerUuidStr);
            }
            return;
        }

        if (status == ResourcePackClientResponsePacket.STATUS_HAVE_ALL_PACKS) {
            if (sentList.size() >= 3) store.markSpoof(playerUuidStr);
            Map<String, String> assignedPacks = store.getAssignedPacks();
            for (String packUuid : sentList) {
                String ownerUuid = findOwnerByPackUuid(packUuid, assignedPacks);
                if (ownerUuid != null && !ownerUuid.equals(playerUuidStr))
                    store.addPackClaim(playerUuidStr, ownerUuid);
            }
            assignPackAndDisconnect(player, playerUuidStr);
            sentPacks.remove(playerUuid);
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
            assignPackAndDisconnect(player, playerUuidStr);
            return;
        }

        if (status == ResourcePackClientResponsePacket.STATUS_COMPLETED) {
            store.markSpoof(playerUuidStr);
            assignPackAndDisconnect(player, playerUuidStr);
            sentPacks.remove(playerUuid);
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

    private void assignPackAndDisconnect(Player player, String playerUuidStr) {
        String packUuid = store.ensurePackAssigned(playerUuidStr, player.getName());
        getOrCreatePack(packUuid);

        pendingReconnect.put(playerUuidStr, System.currentTimeMillis());
        sentPacks.remove(player.getUniqueId());
        player.kick("请重新进入服务器完成初始化", false);
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
