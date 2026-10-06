package com.altdetector.util;

/**
 * 标准 XXHash64（XXH64）纯 Java 实现，seed=0。
 * <p>
 * 基岩版客户端使用 XXHash64(data, seed=0) 作为 Client Blob Cache 的 BlobId
 * （参见 Mojang 开发者 Tomcc 的官方说明），因此插件生成的 blob 必须用同一算法
 * 计算内容哈希，才能与客户端缓存对齐。
 * <p>
 * 算法参考公开的 XXH64 规范实现（Yann Collet，公有领域）。
 */
public final class XxHash64 {

    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private XxHash64() {
    }

    public static long hash(byte[] data, long seed) {
        return hash(data, 0, data.length, seed);
    }

    public static long hash(byte[] data, int offset, int length, long seed) {
        long hash;
        int index = offset;
        int remaining = length;

        if (length >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;

            int limit = offset + length - 32;
            do {
                v1 = round(v1, getLongLE(data, index));
                v2 = round(v2, getLongLE(data, index + 8));
                v3 = round(v3, getLongLE(data, index + 16));
                v4 = round(v4, getLongLE(data, index + 24));
                index += 32;
            } while (index <= limit);

            hash = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            hash = mergeRound(hash, v1);
            hash = mergeRound(hash, v2);
            hash = mergeRound(hash, v3);
            hash = mergeRound(hash, v4);
            remaining -= (index - offset);
        } else {
            hash = seed + P5;
        }

        hash += length;

        while (remaining >= 8) {
            long k1 = round(0, getLongLE(data, index));
            hash ^= k1;
            hash = Long.rotateLeft(hash, 27) * P1 + P4;
            index += 8;
            remaining -= 8;
        }

        if (remaining >= 4) {
            hash ^= (getIntLE(data, index) & 0xFFFFFFFFL) * P1;
            hash = Long.rotateLeft(hash, 23) * P2 + P3;
            index += 4;
            remaining -= 4;
        }

        while (remaining > 0) {
            hash ^= (data[index] & 0xFFL) * P5;
            hash = Long.rotateLeft(hash, 11) * P1;
            index++;
            remaining--;
        }

        hash ^= hash >>> 33;
        hash *= P2;
        hash ^= hash >>> 29;
        hash *= P3;
        hash ^= hash >>> 32;

        return hash;
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        acc = Long.rotateLeft(acc, 31);
        acc *= P1;
        return acc;
    }

    private static long mergeRound(long acc, long val) {
        val = round(0, val);
        acc ^= val;
        acc = acc * P1 + P4;
        return acc;
    }

    private static long getLongLE(byte[] data, int index) {
        return (data[index] & 0xFFL)
                | ((data[index + 1] & 0xFFL) << 8)
                | ((data[index + 2] & 0xFFL) << 16)
                | ((data[index + 3] & 0xFFL) << 24)
                | ((data[index + 4] & 0xFFL) << 32)
                | ((data[index + 5] & 0xFFL) << 40)
                | ((data[index + 6] & 0xFFL) << 48)
                | ((data[index + 7] & 0xFFL) << 56);
    }

    private static int getIntLE(byte[] data, int index) {
        return (data[index] & 0xFF)
                | ((data[index + 1] & 0xFF) << 8)
                | ((data[index + 2] & 0xFF) << 16)
                | ((data[index + 3] & 0xFF) << 24);
    }
}
