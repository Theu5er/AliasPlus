package com.altdetector.protocol;

import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.ProtocolInfo;

/**
 * Client Cache Blob Status (0x87) —— 客户端 -> 服务端。
 * <p>
 * Nukkit-MOT 核心未实现该包，本插件自行定义并在 onEnable 时注册到
 * {@code Server.getInstance().getNetwork()}。
 * <p>
 * 线上格式（1.26.40+ / 协议 2168+ 起为 Cereal 交织布局，对旧版同样兼容）：
 * <pre>
 * uvarint missCount
 * int64[missCount] misses  (little endian，客户端【没有】缓存的 blob)
 * uvarint hitCount
 * int64[hitCount] hits     (little endian，客户端【已有】缓存的 blob)
 * </pre>
 * 旧版（naksCount | acksCount | naks | acks）两个计数紧邻开头，位置与交织布局不同：
 * 解码时优先按交织格式解析并要求精确消费，不满足则回退旧格式重解，两版通吃。
 */
public class ClientCacheBlobStatusPacket extends DataPacket {

    public static final byte NETWORK_ID = ProtocolInfo.CLIENT_CACHE_BLOB_STATUS_PACKET;

    /** 单个列表允许的最大元素数（防御畸形/恶意报文导致的内存与越界问题）。 */
    private static final int MAX_LIST_SIZE = 4096;

    /** 客户端没有的 blob id（nacks / misses）。 */
    public long[] missIds = new long[0];
    /** 客户端已有的 blob id（acks / hits）。 */
    public long[] hitIds = new long[0];

    @Override
    public byte pid() {
        return NETWORK_ID;
    }

    @Override
    public void decode() {
        this.missIds = new long[0];
        this.hitIds = new long[0];

        byte[] full;
        int start;
        try {
            full = this.getBufferUnsafe();
            start = this.getOffset();
        } catch (Throwable t) {
            return;
        }
        if (full == null || start < 0 || start >= full.length) {
            return;
        }
        byte[] raw = new byte[full.length - start];
        System.arraycopy(full, start, raw, 0, raw.length);

        try {
            // 双格式兼容：
            //   新格式（1.26.40+ / 协议 2168+，含 1.26.45.1）为 Cereal 交织布局：
            //     missCount | misses(LE64) | hitCount | hits(LE64)
            //   旧格式（naksCount | acksCount | naks | acks）两个计数紧邻开头，
            //   与交织布局位置不同，必须用第二个计数所在位置来区分。
            // 优先按交织格式解析并要求【精确消费】；不满足则回退旧格式重解。
            // 两种格式的 blob 顺序一致（misses/naks 在前），任一解析成功即得正确结果。
            long[][] r = tryDecodeInterleaved(raw);
            if (r == null) r = tryDecodeLegacy(raw);
            if (r != null) {
                this.missIds = r[0];
                this.hitIds = r[1];
            }
        } catch (Exception e) {
            // 畸形/异常报文：静默按空数据处理，不打日志不外抛
            this.missIds = new long[0];
            this.hitIds = new long[0];
        }
    }

    /** 交织格式：missCount | misses | hitCount | hits，要求精确消费。 */
    private static long[][] tryDecodeInterleaved(byte[] raw) {
        try {
            Cursor cur = new Cursor(raw);
            int missCount = cur.readCount("missCount");
            long[] miss = cur.readLongs(missCount);
            int hitCount = cur.readCount("hitCount");
            long[] hit = cur.readLongs(hitCount);
            if (cur.pos == raw.length) return new long[][]{miss, hit};
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 旧格式：naksCount | acksCount | naks | acks，要求精确消费。 */
    private static long[][] tryDecodeLegacy(byte[] raw) {
        try {
            Cursor cur = new Cursor(raw);
            int naksCount = cur.readCount("naksCount");
            int acksCount = cur.readCount("acksCount");
            long[] miss = cur.readLongs(naksCount);
            long[] hit = cur.readLongs(acksCount);
            if (cur.pos == raw.length) return new long[][]{miss, hit};
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void encode() {
        this.reset();
        this.putUnsignedVarInt(this.missIds.length);
        this.putUnsignedVarInt(this.hitIds.length);
        for (long id : this.missIds) {
            this.putLLong(id);
        }
        for (long id : this.hitIds) {
            this.putLLong(id);
        }
    }

    private static final class Cursor {
        private final byte[] data;
        private int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return this.data.length - this.pos;
        }

        int readCount(String field) {
            long v = this.readUvarint();
            if (v < 0 || v > MAX_LIST_SIZE) {
                throw new IllegalStateException("0x87 " + field + " 超出合理范围: " + v);
            }
            return (int) v;
        }

        long readUvarint() {
            long value = 0;
            int shift = 0;
            while (true) {
                if (this.pos >= this.data.length) {
                    throw new IllegalStateException("0x87 uvarint 越界（报文被截断）");
                }
                int b = this.data[this.pos++] & 0xFF;
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalStateException("0x87 uvarint 过长");
                }
            }
        }

        long[] readLongs(int count) {
            if (count * 8 > this.remaining()) {
                throw new IllegalStateException("0x87 读取 " + count + " 个 long 越界");
            }
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                out[i] = readLongLE();
            }
            return out;
        }

        long readLongLE() {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v |= (long) (this.data[this.pos++] & 0xFF) << (8 * i);
            }
            return v;
        }
    }
}
