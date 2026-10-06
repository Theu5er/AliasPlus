package com.altdetector.protocol;

import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.ProtocolInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client Cache Miss Response (0x88) —— 服务端 -> 客户端。
 * <p>
 * Nukkit-MOT 核心未实现该包，本插件自行定义并在 onEnable 时注册。
 * <p>
 * 线上格式（与 CloudburstMC Protocol v361 序列化器一致）：
 * <pre>
 * uvarint count
 * for each blob:
 *     int64 blobId        (little endian)
 *     byte[] payload      (uvarint 长度前缀)
 * </pre>
 */
public class ClientCacheMissResponsePacket extends DataPacket {

    public static final byte NETWORK_ID = ProtocolInfo.CLIENT_CACHE_MISS_RESPONSE_PACKET;

    /** blobId（有符号 long，按位与客户端一致） -> blob 数据。 */
    public final Map<Long, byte[]> blobs = new LinkedHashMap<>();

    @Override
    public byte pid() {
        return NETWORK_ID;
    }

    @Override
    public void decode() {
        this.decodeUnsupported();
    }

    @Override
    public void encode() {
        this.reset();
        this.putUnsignedVarInt(this.blobs.size());
        for (Map.Entry<Long, byte[]> e : this.blobs.entrySet()) {
            this.putLLong(e.getKey());
            this.putByteArray(e.getValue());
        }
    }
}
