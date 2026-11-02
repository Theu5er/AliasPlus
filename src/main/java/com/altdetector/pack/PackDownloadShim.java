package com.altdetector.pack;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.server.DataPacketSendEvent;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.ResourcePackChunkDataPacket;
import cn.nukkit.network.protocol.ResourcePackDataInfoPacket;

import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资源包清单照常列全服，下载数据统一换成玩家自己的那一个包。
 *
 * <p>客户端按清单请求别人的包时，收到的字节是他自己包的内容，dataInfo 里的 sha256、
 * 大小、分片数同步改写，校验一定通过；包 uuid 保持客户端请求的那个不动，所以客户端
 * 会照常按 uuid 存下来。
 *
 * <p>这一层只做数据替换，不碰清单、不碰判定。
 */
public class PackDownloadShim implements Listener {

    private final Map<UUID, byte[]> ownData = new ConcurrentHashMap<>();
    private final Map<UUID, byte[]> ownHash = new ConcurrentHashMap<>();

    /** 记录玩家自己的包，登录分发阶段调用。 */
    public void remember(Player player, FingerprintPack pack) {
        if (player == null || pack == null) return;
        UUID id = player.getUniqueId();
        if (id == null) return;
        byte[] data = pack.getPackChunk(0, pack.getPackSize());
        ownData.put(id, data);
        ownHash.put(id, sha256(data));
    }

    /** 玩家下线时清掉，避免残留。 */
    public void forget(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        if (id == null) return;
        ownData.remove(id);
        ownHash.remove(id);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPacketSend(DataPacketSendEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        UUID id = player.getUniqueId();
        if (id == null) return;          // 登录完成前 uuid 为空，不能拿来做 map 键
        byte[] data = ownData.get(id);
        if (data == null) return;

        DataPacket packet = event.getPacket();
        if (packet instanceof ResourcePackDataInfoPacket) {
            ResourcePackDataInfoPacket info = (ResourcePackDataInfoPacket) packet;
            byte[] hash = ownHash.get(id);
            if (hash != null) info.sha256 = hash;
            info.compressedPackSize = data.length;
            int maxChunk = info.maxChunkSize > 0 ? info.maxChunkSize : data.length;
            info.chunkCount = Math.max(1, (int) Math.ceil(data.length / (double) maxChunk));
        } else if (packet instanceof ResourcePackChunkDataPacket) {
            ResourcePackChunkDataPacket chunk = (ResourcePackChunkDataPacket) packet;
            int offset = (int) chunk.progress;
            if (offset < 0 || offset >= data.length) return;
            int len = Math.min(chunk.data == null ? data.length - offset : chunk.data.length, data.length - offset);
            byte[] out = new byte[len];
            System.arraycopy(data, offset, out, 0, len);
            chunk.data = out;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 failed", e);
        }
    }
}
