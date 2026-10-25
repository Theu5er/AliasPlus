package com.altdetector.net;

import cn.nukkit.Player;
import cn.nukkit.network.session.NetherNetPlayerSession;
import cn.nukkit.network.session.NetworkPlayerSession;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;

/**
 * NetherNet NetworkID 的唯一读取点，也是 Alias+ 中唯一直接引用 NetherNet 内部类的类。
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
 * <p>容错策略：核心不含 NetherNet 类（纯 RakNet 核心、或未来版本类名漂移）时，
 * 所有方法返回 null / false 而不抛出——因此把引用集中隔离在本类，加载失败只影响这里，
 * 插件其余部分（含 RakNet 玩家）照常工作。
 */
final class NetherNetAccessor {

    private NetherNetAccessor() {
    }

    /**
     * 读取玩家的 NetworkID。
     *
     * @return NetworkID；RakNet 连接、信令未附带 PlayerInfo、或核心无 NetherNet 类时为 null
     */
    static String getNetworkId(Player player) {
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
