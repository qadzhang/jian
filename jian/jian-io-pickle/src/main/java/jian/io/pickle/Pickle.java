package jian.io.pickle;

import jian.core.DataFrame;
import jian.core.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

// ┌─ What : Pickle —— 自定义 .jpk 序列化格式(对齐 pandas.to_pickle 诉求:DataFrame 落盘再加载)
// │  Why  : 规范 02 §3.9;不用 JDK 序列化(已废弃不安全)、不用 Kryo(有 CVE-2026-41862);
// │        自写"魔数 + JSON(records orient) + CRC32",可控、安全、无 RCE 风险、可 debug
// │  Who  : 用户经 df.toPickle / Jian.readPickle 调用
// │  When : DataFrame 缓存、跨会话传递
// │  Where: jian-io-pickle/Pickle.java
// │  How  : 数据走向:
// │           写:DataFrame → records orient JSON 字符串 → [魔数][长度][JSON][CRC] → .jpk;
// │           读:校验魔数 + CRC → 取 JSON → records orient 解析 → DataFrame(复用 jian-io-json 逻辑)。
// │         关键变量变化:
// │           - MAGIC = "JPK2"(改为 v2 格式:JSON 内核,前版二进制调试困难);
// │           - JSON 用 jian-io-json 的 records orient(已测 round-trip 一致)。
// │         逻辑路线:
// │           路径 A(读时魔数错)→ IOException;
// │           路径 B(CRC 不匹配)→ IOException("文件损坏");
// │           路径 C(正常)→ JSON 解析重建 DataFrame。
/**
 * 自定义 .jpk 序列化格式,对齐 pandas.to_pickle 诉求(DataFrame 落盘再加载)。
 *
 * <p><b>格式 v2</b>(JSON 内核,可 debug):
 * <pre>
 * [魔数 4字节 "JPK2"]
 * [payload 长度 4字节]
 * [payload:records orient JSON 字符串]
 * [CRC32 校验 4字节]
 * </pre>
 *
 * <p><b>安全</b>:反序列化只读 JSON 数据,不实例化任意类,无 RCE 风险(规范 §3.9)。
 * <p><b>不与 Python pickle 互通</b>(规范已说明),但满足 DataFrame 落盘核心诉求。
 *
 * <p>用法:
 * <pre>{@code
 * Pickle.write(df, "data.jpk");
 * DataFrame loaded = Pickle.read("data.jpk");
 * }</pre>
 */
public final class Pickle {

    private Pickle() {}

    private static final int MAGIC = 0x4A504B32;  // "JPK2"

    /**
     * 序列化 DataFrame 到 .jpk 文件。
     * @param df DataFrame 要序列化的数据帧,不允许 null
     * @param path String 输出 .jpk 文件路径,需为合法可写路径,不允许 null
     * @throws IOException 目标路径不可写或写出过程发生 IO 错误时抛出
     */
    public static void write(DataFrame df, String path) throws IOException {
        try (OutputStream fos = Files.newOutputStream(Path.of(path))) {
            write(df, fos);
        }
    }

    /**
     * 序列化 DataFrame 到输出流(魔数 + payload + CRC32)。
     * @param df DataFrame 要序列化的数据帧,不允许 null
     * @param os OutputStream 目标输出流,调用方负责关闭;不允许 null
     * @throws IOException 写出过程发生 IO 错误时抛出
     */
    public static void write(DataFrame df, OutputStream os) throws IOException {
        // 因为 0 行经 RECORDS 会丢列(payload="[]"),所以 0 行切 COLUMNS 保列元数据
        // (读侧 JsonReader 自动检测 COLUMNS 形态)
        var orient = df.rowCount() == 0
                ? jian.io.json.Json.Orient.COLUMNS : jian.io.json.Json.Orient.RECORDS;
        String json = jian.io.json.Json.toJsonString(df, orient);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[8];
        header[0] = 'J'; header[1] = 'P'; header[2] = 'K'; header[3] = '2';
        header[4] = (byte) (payload.length >> 24);
        header[5] = (byte) (payload.length >> 16);
        header[6] = (byte) (payload.length >> 8);
        header[7] = (byte) payload.length;
        // body = header + payload,算 CRC
        CRC32 crc = new CRC32();
        crc.update(header);
        crc.update(payload);
        int crcVal = (int) crc.getValue();
        os.write(header);
        os.write(payload);
        os.write(new byte[]{(byte)(crcVal >> 24), (byte)(crcVal >> 16), (byte)(crcVal >> 8), (byte)crcVal});
    }

    /**
     * 从 .jpk 文件反序列化。
     * @param path String .jpk 文件路径,需为合法可读文件,不允许 null
     * @return DataFrame 还原后的数据帧(类型按 JSON records 解析重建)
     * @throws IOException 文件过短、魔数错误、长度不匹配或 CRC 校验失败时抛出(含中文错误说明)
     */
    public static DataFrame read(String path) throws IOException {
        try (InputStream fis = Files.newInputStream(Path.of(path))) {
            return read(fis);
        }
    }

    /**
     * 从输入流反序列化(校验魔数 + CRC32 后解析 JSON)。
     * 说明:一次性 readAllBytes 入内存 —— 超大文件需等量堆,
     * jian 定位单机内存库,超大文件建议分块或 v2 流式化。
     * @param is InputStream .jpk 数据输入流,调用方负责关闭;不允许 null
     * @return DataFrame 还原后的数据帧(类型按 JSON records 解析重建)
     * @throws IOException 数据过短、魔数错误、长度不匹配或 CRC 校验失败时抛出(含中文错误说明)
     */
    public static DataFrame read(InputStream is) throws IOException {
        byte[] all = is.readAllBytes();
        if (all.length < 12) {
            throw new IOException("文件过短(<" + 12 + " 字节),非 jian-io-pickle 格式");
        }
        // 校验魔数
        if (all[0] != 'J' || all[1] != 'P' || all[2] != 'K' || all[3] != '2') {
            throw new IOException("魔数错误:期望 JPK2,实际 " + (char) all[0] + (char) all[1] + (char) all[2] + (char) all[3]);
        }
        int payloadLen = ((all[4] & 0xFF) << 24) | ((all[5] & 0xFF) << 16) | ((all[6] & 0xFF) << 8) | (all[7] & 0xFF);
        // 因为 int 算术在 payloadLen 接近 Integer.MAX_VALUE 时溢出为负、检查会静默通过,
        // 后续 crc.update 抛裸 AIOOBE,所以做 long 提升校验;负值 payloadLen 同样先拒绝
        // (pandas read_pickle 对损坏文件抛可读 UnpicklingError)
        // 严格等长校验(.jpk 头部声明了 payloadLen,尾部多余
        // 字节 = 文件被追加/拼接的异常形态;旧实现只拦"长度不够",多出的尾巴静默忽略,
        // 与"CRC 校验失败:文件损坏"的防御意图不一致。注:Python pickle.load 本身容忍
        // 尾部字节,此为 jian 自定义格式的完整性加固,非 pandas 对齐项)
        if (payloadLen < 0 || (long) payloadLen + 12L != all.length) {
            throw new IOException("文件长度与声明不符:声明 payload " + payloadLen
                    + "(总长应为 " + (12L + (long) Math.max(payloadLen, 0)) + ")"
                    + ",实际总长 " + all.length
                    + (all.length > 12L + Math.max(payloadLen, 0) ? "(含多余尾部字节,疑似文件被追加/损坏)" : ""));
        }
        // 校验 CRC
        CRC32 crc = new CRC32();
        crc.update(all, 0, 8 + payloadLen);
        int expectedCrc = ((all[8 + payloadLen] & 0xFF) << 24) | ((all[8 + payloadLen + 1] & 0xFF) << 16)
                | ((all[8 + payloadLen + 2] & 0xFF) << 8) | (all[8 + payloadLen + 3] & 0xFF);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("CRC 校验失败:文件损坏或非 jian-io-pickle 格式");
        }
        // 解析 JSON(0 行 pickle 的 payload 是 COLUMNS 形态 {"a":[],...},
        // 按顶层类型自动选 orient —— object 且值全数组 → COLUMNS,否则 RECORDS)
        String json = new String(all, 8, payloadLen, StandardCharsets.UTF_8);
        jian.io.json.Json.Orient o = jian.io.json.Json.Orient.RECORDS;
        if (!json.isEmpty() && json.charAt(0) == '{') o = jian.io.json.Json.Orient.COLUMNS;
        return jian.io.json.Json.parse(json, o);
    }
}
