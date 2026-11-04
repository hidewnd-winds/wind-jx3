package com.hidewnd.winds.common.base.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 根据文件头识别图片格式的工具类。 */
public final class ImageUtils {

    private ImageUtils() {
    }

    /**
     * 从流的当前位置读取最多 12 字节，识别图片扩展名。
     * 仅检查文件头签名，不解码完整图片；流由调用方负责关闭。
     *
     * @param input 位于图片文件起始位置的输入流
     * @return 带点的 .png、.jpg、.gif 或 .webp；签名不匹配或不足时返回 null
     * @throws IOException 读取文件头失败
     */
    public static String detectImageExtension(InputStream input) throws IOException {
        byte[] header = input.readNBytes(12);
        if (isPng(header)) {
            return ".png";
        }
        if (isJpeg(header)) {
            return ".jpg";
        }
        if (isGif(header)) {
            return ".gif";
        }
        if (isWebp(header)) {
            return ".webp";
        }
        return null;
    }

    private static boolean isPng(byte[] header) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        return startsWith(header, signature);
    }

    private static boolean isJpeg(byte[] header) {
        return startsWith(header, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
    }

    private static boolean isGif(byte[] header) {
        return startsWith(header, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(header, "GIF89a".getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean isWebp(byte[] header) {
        return header.length >= 12
                && startsWith(header, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && header[8] == 'W'
                && header[9] == 'E'
                && header[10] == 'B'
                && header[11] == 'P';
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }
}
