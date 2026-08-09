package jian.io.clipboard;

import jian.core.DataFrame;
import jian.core.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// ┌─ What : Clipboard —— 系统剪贴板读写(对齐 pandas.read_clipboard / to_clipboard,自写跨平台)
// │  Why  : 规范 02 §3.10 + §6.7;跨平台零本机绑定(Linux→xclip/xsel, macOS→pbcopy/pbpaste, Windows→clip)
// │  Who  : 用户经 df.toClipboard / Jian.readClipboard 调用
// │  When : 复制表格到剪贴板供粘贴 Excel/文档;从剪贴板读表格
// │  Where: jian-io-clipboard/Clipboard.java
// │  How  : 数据走向:
// │           写:DataFrame → TSV(制表符分隔)字符串 → 剪贴板命令 stdin;
// │           读:剪贴板命令 stdout → TSV 字符串 → Object[][] + 推断 → DataFrame。
// │         关键变量变化:
// │           - cmd:按 os.name 探测不同平台命令;
// │           - 数据格式统一 TSV(制表符 + 换行,粘贴到 Excel/WPS 自动分列)。
// │         逻辑路线:
// │           路径 A(Linux)→ xclip -selection clipboard 或 xsel --clipboard --input;
// │           路径 B(macOS)→ pbcopy / pbpaste;
// │           路径 C(Windows)→ clip / powershell Get-Clipboard;
// │           路径 D(命令不存在)→ 降级到内存变量 + warning,不崩溃(规范 §3.10)。
/**
 * 系统剪贴板读写,对齐 pandas.read_clipboard / to_clipboard。
 *
 * <p><b>跨平台</b>(规范 §6.7 零本机绑定):
 * <ul>
 *   <li>Linux:xclip / xsel(需用户装,apt install xclip);</li>
 *   <li>macOS:pbcopy / pbpaste(系统自带);</li>
 *   <li>Windows:clip / powershell Get-Clipboard。</li>
 * </ul>
 *
 * <p>命令不存在时降级到内存变量(同 JVM 内可读回,跨进程不可),不崩溃。
 */
public final class Clipboard {

    private Clipboard() {}

    // 降级内存变量(命令不可用时用)
    private static volatile String memoryFallback = null;

    // ======================== 写(DF → 剪贴板)========================

    /**
     * 把 DataFrame 写入剪贴板(TSV 格式,粘贴到 Excel 自动分列)。
     * @param df DataFrame 要写入剪贴板的数据帧,不允许 null
     * @throws IOException 写出过程发生 IO 错误时抛出(注:剪贴板命令不可用时不抛,降级到内存变量并打 warning)
     */
    public static void write(DataFrame df) throws IOException {
        // 转 TSV
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", df.columnNames())).append('\n');
        for (Object[] row : df.iterRows()) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < row.length; c++) {
                if (c > 0) line.append('\t');
                Object v = row[c];
                line.append(v == null ? "" : String.valueOf(v));
            }
            sb.append(line).append('\n');
        }
        String tsv = sb.toString();
        if (!writeText(tsv)) {
            // 命令不可用,降级到内存
            memoryFallback = tsv;
            System.err.println("[jian] 剪贴板命令不可用,数据保存到内存变量(同 JVM 内可读回;建议安装 xclip/pbcopy/clip)");
        }
    }

    // ======================== 读(剪贴板 → DF)========================

    /** 从剪贴板读 DataFrame(按 TSV 解析,首行作列名)。 */
    public static DataFrame read() throws IOException {
        String tsv = readText();
        return parseTsv(tsv);
    }

    // ======================== 内部:平台命令探测 ========================

    private static boolean writeText(String text) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd;
        if (os.contains("linux")) {
            cmd = new String[]{"sh", "-c", "xclip -selection clipboard 2>/dev/null || xsel --clipboard --input 2>/dev/null"};
        } else if (os.contains("mac") || os.contains("darwin")) {
            cmd = new String[]{"pbcopy"};
        } else if (os.contains("win")) {
            cmd = new String[]{"cmd", "/c", "clip"};
        } else {
            return false;  // 未知平台
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 把 stderr 重定向到单独的丢弃文件,避免子进程写满 stderr pipe 缓冲区(典型 64KB)
            // 导致阻塞——尤其当 xclip/xsel 在无 X server 环境下大量报错时(2026-08-09 修复)。
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            // Web 安全修复(2026-08-08):try-with-resources 关闭输出流 + waitFor 带超时(防挂死)
            try (var pOut = p.getOutputStream()) {
                pOut.write(text.getBytes(StandardCharsets.UTF_8));
            }
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            return finished && p.exitValue() == 0;
        } catch (InterruptedException e) { // 恢复中断标志
            Thread.currentThread().interrupt();  // 恢复中断标志
            return false;
        } catch (IOException e) {
            return false;  // 命令不存在
        }
    }

    private static String readText() throws IOException {
        if (memoryFallback != null) return memoryFallback;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd;
        if (os.contains("linux")) {
            cmd = new String[]{"sh", "-c", "xclip -selection clipboard -o 2>/dev/null || xsel --clipboard --output 2>/dev/null"};
        } else if (os.contains("mac") || os.contains("darwin")) {
            cmd = new String[]{"pbpaste"};
        } else if (os.contains("win")) {
            cmd = new String[]{"powershell", "-Command", "Get-Clipboard"};
        } else {
            return "";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 把 stderr 丢弃(见 writeText 同款修复):防子进程 stderr 写满缓冲区阻塞 stdout 读取。
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            // Web 安全修复(2026-08-08):关闭 Process 的 InputStream + waitFor 带超时(防 native FD 泄漏 + 挂死)
            try (var is = p.getInputStream()) {
                String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
                return text;
            }
        } catch (InterruptedException e) { // 恢复中断标志
            Thread.currentThread().interrupt();  // 恢复中断标志
            return "";
        }
    }

    /** 解析 TSV 字符串为 DataFrame(对齐 pandas.read_clipboard 按 \t 分列)。 */
    private static DataFrame parseTsv(String tsv) {
        if (tsv == null || tsv.isBlank()) {
            return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
        }
        String[] lines = tsv.split("\r?\n");
        if (lines.length == 0) {
            return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
        }
        String[] header = lines[0].split("\t", -1);
        List<String> names = new ArrayList<>();
        for (String h : header) names.add(h.trim());
        Object[][] rows = new Object[lines.length - 1][names.size()];
        for (int r = 1; r < lines.length; r++) {
            String[] parts = lines[r].split("\t", -1);
            for (int c = 0; c < names.size(); c++) {
                String v = c < parts.length ? parts[c].trim() : "";
                rows[r - 1][c] = v.isEmpty() ? null : v;
            }
        }
        return DataFrame.of(Schema.infer(names, rows), rows);
    }
}
