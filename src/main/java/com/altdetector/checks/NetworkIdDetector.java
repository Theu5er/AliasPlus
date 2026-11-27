package com.altdetector.checks;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerLoginEvent;
import cn.nukkit.network.session.NetherNetPlayerSession;
import cn.nukkit.network.session.NetworkPlayerSession;
import com.altdetector.AltDetector;
import com.altdetector.store.PlayerStore;
import com.altdetector.util.Uuids;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;

/**
 * NetworkID 检测维度：登录时捕获玩家的 NetherNet NetworkID 并入库。
 *
 * <p>NetworkID 是客户端自报的传输层对端标识（与 DeviceId 同级的信号强度：
 * 普通用户无感知，改装客户端可伪造），适合作为关联参考而非认证依据。
 * 同一 NetworkID 对应多个 uuid 即小号关联，经 {@link PlayerStore#getIdentifiers()}
 * 的 {@code NetworkID} 维度参与 {@code /alias} 与 {@link AltDetector#getRelatedByDimension}。
 *
 * <p>读取链（全部落在 Nukkit-MOT 已 shade 进核心 jar 的类上，插件自身零额外依赖）：
 * <pre>
 * Player.getNetworkSession()                       → NetworkPlayerSession
 *   （NetherNet 连接时的实际类型为 NetherNetPlayerSession）
 * NetherNetPlayerSession.getChannel()              → NetherNetChildChannel
 * channel.attr(NetherNetChildChannel.PLAYER_INFO)  → PlayerInfo（信令层构造的对端身份记录）
 * PlayerInfo.networkId()                           → String（对端自报的 NetworkID）
 * </pre>
 *
 * <p>NetworkID 语义（Mojang 官方 NetherNet 接入指南第 9 节）：传输层对端标识，
 * 不透明字符串、唯一标识客户端，由客户端在信令请求 {@code POST /v1/join/{networkId}}
 * 的路径中自报；当前格式为 64 位无符号整数的十进制形式，官方提示未来可能改变。
 *
 * <p>RakNet 连接没有 NetworkID（协议里不存在该概念），静默跳过；
 * 核心不含 NetherNet 类时（纯 RakNet 核心、或未来版本类名漂移），
 * 读取方法返回 null 而不抛出，插件其余部分照常工作。
 */
public class NetworkIdDetector implements Listener {

    /** 存储层 identifiers 表的 kind，与 /alias 展示标签对应。 */
    public static final String DIMENSION = "NetworkID";

    /** 客户端自报值，与 identifiers.id_value 列宽一致；超出的按"没有这个维度"处理。 */
    private static final int MAX_LENGTH = 128;

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
        String networkId = readNetworkId(player);
        if (networkId == null) {
            // RakNet 连接或核心无 NetherNet 类：无值可存，不影响其他维度
            return;
        }
        String sanitized = sanitize(networkId);
        if (sanitized == null) {
            return;
        }
        try {
            this.store.storeIdentifier(DIMENSION, sanitized, Uuids.keyOf(player));
        } catch (RuntimeException e) {
            // 单个维度写失败不应该打断登录，其余维度照常记录
            this.plugin.getLogger().warning("[NetworkID] store failed: " + e.getMessage());
        }
    }

    /**
     * 读取玩家的 NetworkID，是本插件唯一直接引用 NetherNet 内部类的地方。
     *
     * @return NetworkID；RakNet 连接、信令未附带 PlayerInfo、或核心无 NetherNet 类时为 null
     */
    /** 只接受可打印 ASCII，避免控制字符进库或污染日志；超长的直接丢弃。 */
    private static String sanitize(String networkId) {
        if (networkId == null || networkId.isEmpty() || networkId.length() > MAX_LENGTH) {
            return null;
        }
        for (int index = 0; index < networkId.length(); index++) {
            char c = networkId.charAt(index);
            if (c < 0x21 || c > 0x7E) {
                return null;
            }
        }
        return networkId;
    }

    private static String readNetworkId(Player player) {
        try {
            NetworkPlayerSession session = player.getNetworkSession();
            if (!(session instanceof NetherNetPlayerSession netherNetSession)) {
                return null;
            }
            NetherNetChildChannel channel = netherNetSession.getChannel();
            if (channel == null) {
                return null;
            }
            PlayerInfo info = channel.attr(NetherNetChildChannel.PLAYER_INFO).get();
            if (info == null) {
                return null;
            }
            String networkId = info.networkId();
            return (networkId == null || networkId.isEmpty()) ? null : networkId;
        } catch (Throwable t) {
            return null;
        }
    }
}
