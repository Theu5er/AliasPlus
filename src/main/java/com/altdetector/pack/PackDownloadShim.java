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
import java.util.Set;
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
    private final Set<UUID> fingerprintIds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> maxChunks = new ConcurrentHashMap<>();

    /** 登记指纹包 uuid，只有这些包的下载会被替换。 */
    public void markFingerprint(UUID packId) {
        if (packId != null) fingerprintIds.add(packId);
    }

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
            if (!fingerprintIds.contains(info.packId)) return;   // 服务器自己的资源包原样下发
            maxChunks.put(info.packId, info.maxChunkSize);
            byte[] hash = ownHash.get(id);
            if (hash != null) info.sha256 = hash;
            info.compressedPackSize = data.length;
            int maxChunk = info.maxChunkSize > 0 ? info.maxChunkSize : data.length;
            info.chunkCount = Math.max(1, (int) Math.ceil(data.length / (double) maxChunk));
        } else if (packet instanceof ResourcePackChunkDataPacket) {
            ResourcePackChunkDataPacket chunk = (ResourcePackChunkDataPacket) packet;
            if (!fingerprintIds.contains(chunk.packId)) return;  // 同上
            int offset = (int) chunk.progress;
            if (offset < 0 || offset >= data.length) return;
            // 分片长度必须按我们自己声明的 size 算，不能沿用原包的分片长度
            int max = maxChunks.getOrDefault(chunk.packId, data.length);
            int len = Math.min(max, data.length - offset);
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
