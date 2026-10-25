package com.altdetector.net;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerLoginEvent;
import com.altdetector.AltDetector;
import com.altdetector.store.PlayerStore;

/**
 * NetworkID 检测维度：登录时捕获玩家的 NetherNet NetworkID 并入库。
 *
 * <p>NetworkID 是客户端自报的传输层对端标识（与 DeviceId 同级的信号强度：
 * 普通用户无感知，改装客户端可伪造），适合作为关联参考而非认证依据。
 * 同一 NetworkID 对应多个 uuid 即小号关联，经 {@link PlayerStore#getIdentifiers()}
 * 的 {@code networkid} 维度参与 {@code /alias} 与 {@link AltDetector#getRelatedByDimension}。
 *
 * <p>RakNet 连接没有 NetworkID（协议里不存在该概念），此类连接静默跳过；
 * 核心不含 NetherNet 类时由 {@link NetherNetAccessor} 兜底返回 null，同样跳过。
 */
public class NetworkIdDetector implements Listener {

    /** players.yml 中 ids 下的维度键，与 /alias 展示标签对应。 */
    public static final String DIMENSION = "networkid";

    private final AltDetector plugin;
    private final PlayerStore store;

    public NetworkIdDetector(AltDetector plugin, PlayerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!this.plugin.networkIdEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        String networkId = NetherNetAccessor.getNetworkId(player);
        if (networkId == null) {
            // RakNet 连接或核心无 NetherNet 类：无值可存，不影响其他维度
            return;
        }
        this.store.storeIdentifier(DIMENSION, networkId, player.getUniqueId().toString());
    }
}
