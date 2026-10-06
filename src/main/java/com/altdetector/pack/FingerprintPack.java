package com.altdetector.pack;

import cn.nukkit.resourcepacks.ResourcePack;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 指纹资源包：以 pack uuid 派生确定性内容（仅含 manifest 的最小 zip），
 * 客户端缓存后即成为该设备的"该账号玩过"凭证。
 */
public class FingerprintPack implements ResourcePack {
    private final UUID packId;
    private final String packVersion;
    private final int packIndex;
    private final byte[] packData;
    private final byte[] sha256;

    public FingerprintPack(UUID packId, int packIndex) {
        this.packId = packId;
        this.packIndex = packIndex;
        this.packVersion = "1.0.0";
        this.packData = generatePackData(packId);
        this.sha256 = computeSha256(this.packData);
    }

    private static byte[] generatePackData(UUID packId) {
        String moduleUuid = new UUID(
                packId.getMostSignificantBits() ^ 0xDEAD,
                packId.getLeastSignificantBits() ^ 0xBEEF
        ).toString();
        String manifest = "{"
                + "\"format_version\":2,"
                + "\"header\":{"
                + "\"name\":\"Test\","
                + "\"description\":\"Test\","
                + "\"uuid\":\"" + packId + "\","
                + "\"version\":[1,0,0],"
                + "\"min_engine_version\":[1,16,0]"
                + "},"
                + "\"modules\":[{"
                + "\"type\":\"resources\","
                + "\"uuid\":\"" + moduleUuid + "\","
                + "\"version\":[1,0,0]"
                + "}]"
                + "}";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry("manifest.json");
            zos.putNextEntry(entry);
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (Exception e) { throw new RuntimeException("生成资源包失败", e); }
        return baos.toByteArray();
    }

    private static byte[] computeSha256(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (Exception e) { throw new RuntimeException("SHA-256 失败", e); }
    }

    @Override public String getPackName() { return "Test"; }
    @Override public UUID getPackId() { return packId; }
    @Override public String getPackVersion() { return packVersion; }
    @Override public int getPackSize() { return packData.length; }
    @Override public byte[] getSha256() { return sha256.clone(); }

    @Override
    public byte[] getPackChunk(int off, int len) {
        int size = packData.length;
        int readLen = Math.min(len, size - off);
        if (readLen <= 0) return new byte[0];
        byte[] chunk = new byte[readLen];
        System.arraycopy(packData, off, chunk, 0, readLen);
        return chunk;
    }
}
