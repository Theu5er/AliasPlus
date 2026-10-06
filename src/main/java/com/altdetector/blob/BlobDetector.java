package com.altdetector.blob;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.server.DataPacketReceiveEvent;
import cn.nukkit.level.Level;
import cn.nukkit.level.util.BitArrayVersion;
import cn.nukkit.level.util.PalettedBlockStorage;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.network.Network;
import cn.nukkit.network.protocol.ClientCacheStatusPacket;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.LevelChunkPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.network.protocol.SubChunkPacket;
import cn.nukkit.network.protocol.SubChunkRequestPacket;
import cn.nukkit.network.protocol.types.SubChunkRequestResult;
import cn.nukkit.scheduler.TaskHandler;
import cn.nukkit.utils.TextFormat;
import com.altdetector.AltDetector;
import com.altdetector.protocol.ClientCacheBlobStatusPacket;
import com.altdetector.protocol.ClientCacheMissResponsePacket;
import com.altdetector.store.PlayerStore;
import com.altdetector.util.XxHash64;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Client Blob Cache 维度检测。
 *
 * <p>基岩客户端内置持久 blob 缓存（内容寻址、跨账号共享）：为每个账号生成唯一 biome
 * 指纹 blob；对在线玩家发起探测（cacheEnabled 区块引用 4 个共享负段 blob + 若干指纹
 * blob），客户端 0x87 响应中 hit 的指纹即代表该设备缓存过对应账号——换号命中即关联。
 *
 * <p>共享负段 blob 同时是探测区块的结构承载（客户端只处理含已知负段的 cacheEnabled 区块），
 * 其 hit 仅说明设备玩过本服，与小号判断无关，静默跳过。
 */
public class BlobDetector implements Listener {

    private static final int MIN_BLOB_PROTOCOL = ProtocolInfo.v1_18_0;
    private static final int OVERWORLD_BIOME_SECTIONS = 24;
    private static final int NEGATIVE_SUB_CHUNKS = 4;
    private static final int PROBE_SUB_CHUNK_COUNT = NEGATIVE_SUB_CHUNKS;

    private static final byte[][] NEG_BLOBS = new byte[NEGATIVE_SUB_CHUNKS][];
    private static final long[] NEG_BLOB_HASHES = new long[NEGATIVE_SUB_CHUNKS];

    static {
        for (int i = 0; i < NEGATIVE_SUB_CHUNKS; i++) {
            NEG_BLOBS[i] = new byte[]{(byte) 9, (byte) 0, (byte) (-4 + i)};
            NEG_BLOB_HASHES[i] = XxHash64.hash(NEG_BLOBS[i], 0L);
        }
    }

    private static final int[] BIOME_POOL = {1, 2, 3, 4, 5};
    private static final int PROBE_CHUNK_BASE = 200_000;

    private final AltDetector plugin;
    private final PlayerStore store;

    private Map<String, String> blobIds = new LinkedHashMap<>();
    private Map<String, List<String>> blobClaims = new LinkedHashMap<>();
    private final Map<UUID, byte[]> blobData = new HashMap<>();
    private final Map<Long, UUID> blobOwners = new HashMap<>();
    private final Map<UUID, Set<Long>> pendingProbe = new HashMap<>();
    private final Map<UUID, Set<Long>> pendingChunks = new HashMap<>();
    private final Map<UUID, Boolean> cacheSupported = new ConcurrentHashMap<>();
    private final Map<Player, Boolean> preLoginSupported = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<UUID> cacheStatusSent = ConcurrentHashMap.newKeySet();
    private final Set<UUID> fullScanPending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TaskHandler> scanTimeouts = new HashMap<>();

    private long statProbesSent;
    private long statHits;
    private long statMisses;
    private long statClaimsFound;
    private long statSkippedUnsupported;

    /** 完整扫描结束回调（/alias 的挂起输出在其上执行）。由 AliasCommand 注册。 */
    private Consumer<UUID> scanFinishedCallback;

    public BlobDetector(AltDetector plugin, PlayerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void setScanFinishedCallback(Consumer<UUID> callback) {
        this.scanFinishedCallback = callback;
    }

    /* ================================================================== */
    /*  生命周期                                                          */
    /* ================================================================== */

    public void registerPackets() {
        try {
            Network network = plugin.getServer().getNetwork();
            network.registerPacket((byte) ProtocolInfo.CLIENT_CACHE_BLOB_STATUS_PACKET, ClientCacheBlobStatusPacket.class);
            network.registerPacket((byte) ProtocolInfo.CLIENT_CACHE_MISS_RESPONSE_PACKET, ClientCacheMissResponsePacket.class);
        } catch (Exception e) {
            plugin.getLogger().warning("注册 blob cache 协议包失败: " + e);
        }
    }

    public void loadData() {
        this.blobIds = new LinkedHashMap<>();
        this.blobClaims = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> pe : store.getPlayers().entrySet()) {
            String uuid = pe.getKey();
            Map<String, Object> blob = PlayerStore.asMap(pe.getValue().get("blob"));
            if (blob == null) continue;
            Object idObj = blob.get("id");
            if (idObj != null && !String.valueOf(idObj).isEmpty() && !"null".equalsIgnoreCase(String.valueOf(idObj))) {
                this.blobIds.put(uuid, String.valueOf(idObj));
            }
            Object cb = blob.get("claimed_by");
            if (cb instanceof List) {
                List<String> list = new ArrayList<>();
                for (Object o : (List<Object>) cb) list.add(String.valueOf(o));
                if (!list.isEmpty()) this.blobClaims.put(uuid, list);
            }
        }

        this.blobData.clear();
        this.blobOwners.clear();
        for (Map.Entry<String, String> e : this.blobIds.entrySet()) {
            try {
                UUID owner = UUID.fromString(e.getKey());
                long id = Long.parseUnsignedLong(e.getValue(), 16);
                this.blobOwners.put(id, owner);
            } catch (Exception ignored) {
            }
        }
    }

    /* ================================================================== */
    /*  事件                                                              */
    /* ================================================================== */

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.blobEnabled()) return;
        Player player = event.getPlayer();
        Boolean early = this.preLoginSupported.remove(player);
        if (early != null) {
            this.cacheSupported.put(player.getUniqueId(), early);
        }
        // 进服不探测：只确保该账号的指纹记录存在，供以后的 /alias 完整扫描引用
        try {
            this.ensureBlob(player.getUniqueId().toString());
        } catch (Exception ignored) {
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingProbe.remove(uuid);
        pendingChunks.remove(uuid);
        cacheSupported.remove(uuid);
        cacheStatusSent.remove(uuid);
        this.fullScanPending.remove(uuid);
        TaskHandler timeout = this.scanTimeouts.remove(uuid);
        if (timeout != null) timeout.cancel();
    }

    @EventHandler
    public void onBlobCacheReceive(DataPacketReceiveEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        DataPacket packet = event.getPacket();

        if (packet instanceof ClientCacheStatusPacket) {
            boolean supported = ((ClientCacheStatusPacket) packet).supported;
            if (player.loggedIn || player.spawned) {
                this.cacheSupported.put(player.getUniqueId(), supported);
            } else {
                this.preLoginSupported.put(player, supported);
            }
            return;
        }

        if (packet instanceof ClientCacheBlobStatusPacket) {
            ClientCacheBlobStatusPacket status = (ClientCacheBlobStatusPacket) packet;
            event.setCancelled();
            this.handleBlobStatus(player, status);
            return;
        }

        if (packet instanceof SubChunkRequestPacket) {
            SubChunkRequestPacket req = (SubChunkRequestPacket) packet;
            Set<Long> chunks = this.pendingChunks.get(player.getUniqueId());
            if (chunks != null && !chunks.isEmpty()) {
                long key = chunkKey(req.subChunkPosition.x, req.subChunkPosition.z);
                if (chunks.contains(key)) {
                    event.setCancelled();
                    this.sendAirSubChunks(player, req);
                }
            }
        }
    }

    /* ================================================================== */
    /*  完整扫描（/alias 对在线玩家触发）                                    */
    /* ================================================================== */

    /** 手动触发的完整 BlobCache 扫描：/alias <在线玩家> 时调用，探测全部已知指纹（含其自身）。静默执行。 */
    public void startFullScan(Player player) {
        UUID uuid = player.getUniqueId();
        if (!this.fullScanPending.add(uuid)) return;
        this.scanPlayer(player, 0);
    }

    private void scanPlayer(Player player, int attempt) {
        UUID uuid = player.getUniqueId();

        if (!player.isConnected() || !player.spawned) {
            this.finishScan(uuid);
            return;
        }
        if (player.protocol < MIN_BLOB_PROTOCOL) {
            this.statSkippedUnsupported++;
            this.finishScan(uuid);
            return;
        }

        Boolean supported = this.cacheSupported.get(uuid);
        if (supported == null) {
            if (attempt < 10) {
                plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
                    if (player.isConnected() && player.spawned) this.scanPlayer(player, attempt + 1);
                    else this.finishScan(uuid);
                }, 20);
            } else {
                this.statSkippedUnsupported++;
                this.finishScan(uuid);
            }
            return;
        }
        if (!supported) {
            this.statSkippedUnsupported++;
            this.finishScan(uuid);
            return;
        }
        if (player.getLevel() == null || player.getLevel().getDimension() != Level.DIMENSION_OVERWORLD) {
            this.finishScan(uuid);
            return;
        }

        long ownId = this.ensureBlob(uuid.toString());

        // 完整扫描：引用全部已知指纹（含其自身——自身 miss 会被下发打标，
        // 使该设备缓存住此账号的指纹，供之后同设备其他账号命中）
        List<Long> ids = new ArrayList<>();
        ids.add(ownId);
        for (String other : this.recentOwners(Integer.MAX_VALUE)) {
            if (other.equals(uuid.toString())) continue;
            Long id = this.parseBlobId(this.blobIds.get(other));
            if (id != null && !ids.contains(id)) ids.add(id);
        }

        if (this.cacheStatusSent.add(uuid)) {
            ClientCacheStatusPacket enable = new ClientCacheStatusPacket();
            enable.supported = true;
            player.dataPacket(enable);
        }

        // 兜底超时：客户端 0x87 丢失时不至于永远挂起；部分结果同样保存
        TaskHandler timeout = plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (this.fullScanPending.contains(uuid)) {
                this.pendingProbe.remove(uuid);
                this.pendingChunks.remove(uuid);
                this.finishScan(uuid);
            }
        }, 600);
        TaskHandler old = this.scanTimeouts.put(uuid, timeout);
        if (old != null) old.cancel();

        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (player.isConnected() && player.spawned) {
                try {
                    this.sendProbeChunks(player, ids);
                } catch (Exception e) {
                    plugin.getLogger().warning("发送 blob 探测区块失败: " + e);
                    this.finishScan(uuid);
                }
            } else {
                this.finishScan(uuid);
            }
        }, 5);
    }

    private void finishScan(UUID uuid) {
        if (!this.fullScanPending.remove(uuid)) return;
        TaskHandler timeout = this.scanTimeouts.remove(uuid);
        if (timeout != null) timeout.cancel();

        // 扫描完成：若本次 /alias 挂起了输出请求，由回调延迟一拍执行，
        // 确保展示的是本次扫描后的最新 BlobCache 关联
        if (this.scanFinishedCallback != null) this.scanFinishedCallback.accept(uuid);
    }

    /* ================================================================== */
    /*  探测与响应                                                         */
    /* ================================================================== */

    private void sendProbeChunks(Player player, List<Long> ids) {
        UUID uuid = player.getUniqueId();
        int base = Math.abs(uuid.hashCode());
        int bx = PROBE_CHUNK_BASE + (base & 0xFFFF);
        int bz = PROBE_CHUNK_BASE + ((base >>> 16) & 0xFFFF);

        Set<Long> pending = new HashSet<>();
        Set<Long> chunks = new HashSet<>();
        for (long h : NEG_BLOB_HASHES) pending.add(h);

        for (int i = 0; i < ids.size(); i++) {
            long fp = ids.get(i);
            int cx = bx + i;
            int cz = bz;
            try {
                LevelChunkPacket pk = new LevelChunkPacket();
                pk.chunkX = cx;
                pk.chunkZ = cz;
                pk.dimension = Level.DIMENSION_OVERWORLD;
                pk.subChunkCount = PROBE_SUB_CHUNK_COUNT;
                pk.cacheEnabled = true;
                long[] blobIds = new long[PROBE_SUB_CHUNK_COUNT + 1];
                System.arraycopy(NEG_BLOB_HASHES, 0, blobIds, 0, NEGATIVE_SUB_CHUNKS);
                blobIds[PROBE_SUB_CHUNK_COUNT] = fp;
                pk.blobIds = blobIds;
                pk.data = new byte[]{0};
                player.dataPacket(pk);
                pending.add(fp);
                chunks.add(chunkKey(cx, cz));
                this.statProbesSent++;
            } catch (Exception e) {
                plugin.getLogger().warning("发送 blob 探测区块失败: " + e);
            }
        }

        this.pendingProbe.put(uuid, pending);
        this.pendingChunks.put(uuid, chunks);
    }

    private void handleBlobStatus(Player player, ClientCacheBlobStatusPacket status) {
        UUID uuid = player.getUniqueId();
        Set<Long> pending = this.pendingProbe.get(uuid);
        if (pending == null || pending.isEmpty()) return;

        ClientCacheMissResponsePacket missResponse = null;
        for (long id : status.missIds) {
            if (!pending.contains(id)) continue;
            UUID owner = this.blobOwners.get(id);
            if (owner != null && !owner.equals(uuid)) {
                continue;
            }
            byte[] data = this.getBlobData(id);
            if (data == null) continue;
            if (missResponse == null) missResponse = new ClientCacheMissResponsePacket();
            missResponse.blobs.put(id, data);
            this.statMisses++;
        }
        if (missResponse != null) {
            player.dataPacket(missResponse);
        }

        for (long id : status.hitIds) {
            if (!pending.contains(id)) continue;
            UUID owner = this.blobOwners.get(id);
            if (owner == null) continue; // 共享负段 hit：仅说明设备玩过本服，与小号判断无关，静默跳过
            this.statHits++;
            if (owner.equals(uuid)) continue;
            this.addBlobClaim(owner, uuid, player);
        }

        pending.removeAll(this.toSet(status.missIds));
        pending.removeAll(this.toSet(status.hitIds));
        if (pending.isEmpty()) {
            this.pendingProbe.remove(uuid);
            this.pendingChunks.remove(uuid);
            this.finishScan(uuid);
        }
    }

    private void sendAirSubChunks(Player player, SubChunkRequestPacket req) {
        try {
            SubChunkPacket resp = new SubChunkPacket();
            resp.dimension = req.dimension;
            resp.cacheEnabled = false;
            resp.centerPosition = req.subChunkPosition;
            if (req.positionOffsets.isEmpty()) {
                SubChunkPacket.SubChunkData d = new SubChunkPacket.SubChunkData();
                d.position = req.subChunkPosition;
                d.offset = new BlockVector3(0, 0, 0);
                d.result = SubChunkRequestResult.SUCCESS_ALL_AIR;
                d.data = new byte[0];
                resp.subChunks.add(d);
            } else {
                for (BlockVector3 off : req.positionOffsets) {
                    SubChunkPacket.SubChunkData d = new SubChunkPacket.SubChunkData();
                    d.position = req.subChunkPosition.add(off.x, off.y, off.z);
                    d.offset = off;
                    d.result = SubChunkRequestResult.SUCCESS_ALL_AIR;
                    d.data = new byte[0];
                    resp.subChunks.add(d);
                }
            }
            player.dataPacket(resp);
        } catch (Exception e) {
            plugin.getLogger().warning("回应探测 subchunk 请求失败: " + e);
        }
    }

    /* ================================================================== */
    /*  指纹 blob 生成与管理                                                */
    /* ================================================================== */

    private long ensureBlob(String ownerUuid) {
        String hex = this.blobIds.get(ownerUuid);
        Long id = hex != null ? this.parseBlobId(hex) : null;
        if (id != null) return id;
        UUID uuid = UUID.fromString(ownerUuid);
        byte[] data = this.buildBiomeBlob(uuid);
        long id2 = XxHash64.hash(data, 0L);
        this.blobIds.put(ownerUuid, toHex(id2));
        this.blobData.put(uuid, data);
        this.blobOwners.put(id2, uuid);
        this.store.saveBlobIds(this.blobIds);
        return id2;
    }

    private byte[] getBlobData(long blobId) {
        for (int i = 0; i < NEG_BLOB_HASHES.length; i++) {
            if (NEG_BLOB_HASHES[i] == blobId) return NEG_BLOBS[i];
        }
        UUID owner = this.blobOwners.get(blobId);
        if (owner == null) return null;
        byte[] data = this.blobData.get(owner);
        if (data == null) {
            data = this.buildBiomeBlob(owner);
            this.blobData.put(owner, data);
        }
        return data;
    }

    private byte[] buildBiomeBlob(UUID uuid) {
        cn.nukkit.utils.BinaryStream stream = new cn.nukkit.utils.BinaryStream();
        Random rng = new Random(uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits());
        for (int i = 0; i < OVERWORLD_BIOME_SECTIONS; i++) {
            PalettedBlockStorage storage = PalettedBlockStorage.createWithDefaultState(
                    BitArrayVersion.V0, BIOME_POOL[rng.nextInt(BIOME_POOL.length)]);
            storage.writeTo(stream, id -> id);
        }
        return stream.getBuffer();
    }

    private void addBlobClaim(UUID owner, UUID claimer, Player claimerPlayer) {
        String ownerStr = owner.toString();
        String claimerStr = claimer.toString();
        List<String> list = this.blobClaims.computeIfAbsent(ownerStr, k -> new ArrayList<>());
        if (list.contains(claimerStr)) return;
        list.add(claimerStr);
        this.statClaimsFound++;
        this.store.saveBlobClaims(this.blobClaims);

        String ownerName = this.store.displayName(ownerStr);
        String claimerName = claimerPlayer.getName();
        String msg = TextFormat.AQUA + "[BlobCache] " + TextFormat.YELLOW + claimerName
                + TextFormat.WHITE + " 的客户端命中了 " + TextFormat.YELLOW + ownerName
                + TextFormat.WHITE + " 的指纹（共享同一设备客户端，疑似小号）";
        for (Player p : plugin.getServer().getOnlinePlayers().values()) {
            if (p.hasPermission("altdetector.notify")) p.sendMessage(msg);
        }
    }

    /** /alias 输出用：与目标玩家在 Blob 维度双向关联的账号（它命中了谁 + 谁命中了它）。 */
    public Set<String> getBlobRelated(String targetUuid) {
        Set<String> related = new HashSet<>();
        for (Map.Entry<String, List<String>> e : this.blobClaims.entrySet()) {
            if (e.getValue().contains(targetUuid) && !e.getKey().equals(targetUuid))
                related.add(e.getKey());
        }
        for (String c : this.blobClaims.getOrDefault(targetUuid, new ArrayList<>())) {
            if (!c.equals(targetUuid)) related.add(c);
        }
        return related;
    }

    private List<String> recentOwners(int limit) {
        List<String> all = new ArrayList<>(this.blobIds.keySet());
        if (all.size() <= limit) return all;
        List<String> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> {
            long la = this.store.lastMs(a), lb = this.store.lastMs(b);
            if (la != lb) return Long.compare(lb, la);
            return a.compareTo(b);
        });
        return sorted.subList(0, limit);
    }

    /* ================================================================== */
    /*  工具                                                              */
    /* ================================================================== */

    private static Set<Long> toSet(long[] array) {
        Set<Long> set = new HashSet<>();
        for (long v : array) set.add(v);
        return set;
    }

    private static Long parseBlobId(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        try { return Long.parseUnsignedLong(hex, 16); } catch (NumberFormatException e) { return null; }
    }

    private static String toHex(long id) {
        return String.format("%016x", id);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
