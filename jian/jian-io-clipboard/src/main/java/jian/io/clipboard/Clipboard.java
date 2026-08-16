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
    /** 读命令失败一次性提示开关(避免每次 read 刷屏) */
    private static volatile boolean readFailWarned = false;

    /**
     * 清除内存降级缓存。
     * <p>因为 memoryFallback 是 static volatile,测试间不清理会污染下一个测试的读;
     * 且一旦命令不可用降级到内存,之后即使 xclip/pbcopy 恢复可用,read 也只会返回
     * 旧内存值(粘滞,无法自愈),所以本方法设为 public —— 用户在剪贴板命令
     * 恢复后(如新装 xclip / 进入图形会话)可显式调用本方法恢复真实剪贴板路径。
     * 语义:清空后,下一次 read 重新探测真实剪贴板命令。
     */
    public static void resetMemoryFallback() { memoryFallback = null; }

    // ┌─ What : testForceMemoryFallback —— 测试专用缝(包私有):强制读写走内存降级路径
    // │  Why  : 测试类的前提是"CI 无剪贴板命令 → write 落 memoryFallback → read 从内存解析"。
    // │         但开发机装有 xclip 时该前提被打破:write 走真实 X 剪贴板,多个 xclip daemon
    // │         争夺 selection 所有权,read 可能拿到旧 daemon 的内容(实测 flaky);
    // │         "清空降级后走真实路径"在有 xclip 的机器上也会读到不可控的真实剪贴板内容。
    // │  Who  : 仅同包测试(ClipboardTest / ClipboardRegressionTest)
    // │         在 @BeforeEach 置 true、@AfterEach 还原 false;生产代码零引用。
    // │  When : 剪贴板单元测试运行期间
    // │  Where: Clipboard.writeText / readText 入口
    // │  How  : 关键变量变化:testForceMemoryFallback=true →
    // │           writeText 直接 return false(不碰子进程)→ write 把 TSV 存 memoryFallback;
    // │           readText 在 memoryFallback 为 null 时返回 ""(不碰真实剪贴板)。
    // │         逻辑路线:flag=true → 读写均短路到内存路径(有降级用降级,无降级为空);
    // │           flag=false → 生产路径完全不变。
    static volatile boolean testForceMemoryFallback = false;

    // ======================== 写(DF → 剪贴板)========================

    /**
     * 把 DataFrame 写入剪贴板(TSV 格式,粘贴到 Excel 自动分列)。
     * <p>因为本 TSV 的设计目的地就是"粘贴到 Excel 自动分列",无防护的 "=cmd|calc"
     * 粘贴即被 Excel 当公式执行(OWASP CSV Injection,AGENTS.md §3.7.3 要求
     * CSV/Excel/TSV 一致),所以 TSV 值(含表头列名)走与 Csv 相同的
     * {@code = + - @} 前缀防护。null 仍输出空串;数值/布尔的字符串形式("-1.5"/"true")
     * 不可能构成公式载荷,豁免(与 Csv 同款口径)。
     * @param df DataFrame 要写入剪贴板的数据帧,不允许 null
     * @throws IOException 写出过程发生 IO 错误时抛出(注:剪贴板命令不可用时不抛,降级到内存变量并打 warning)
     */
    public static void write(DataFrame df) throws IOException {
        // 伪代码:
        //   1. 表头列名逐个过公式注入防护(与 Csv 表头同口径)
        //   2. 逐行逐值:非 Number/Boolean 的字符串若以(跳过前导空白后的)= + - @ 开头 → 加 ' 前缀
        //   3. 拼 TSV(制表符分隔 + 换行)→ 写剪贴板(失败降级内存)
        StringBuilder sb = new StringBuilder();
        StringBuilder hdr = new StringBuilder();
        java.util.List<String> names = df.columnNames();
        for (int c = 0; c < names.size(); c++) {
            if (c > 0) hdr.append('\t');
            hdr.append(startsWithFormulaAfterWhitespace(names.get(c)) ? "'" + names.get(c) : names.get(c));
        }
        sb.append(hdr).append('\n');
        for (Object[] row : df.iterRows()) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < row.length; c++) {
                if (c > 0) line.append('\t');
                Object v = row[c];
                if (v == null) {
                    line.append("");   // null 仍输出空串(缺失值语义不变)
                    continue;
                }
                String s = String.valueOf(v);
                boolean sanitizable = !(v instanceof Number) && !(v instanceof Boolean);
                line.append(sanitizable && startsWithFormulaAfterWhitespace(s) ? "'" + s : s);
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

    // ┌─ What : startsWithFormulaAfterWhitespace —— 公式注入检测(OWASP 严格版)
    // │  Why  : 与 Csv.CsvWriter / Excel.startsWithFormulaAfterWhitespace 同款逻辑 ——
    // │         跳过前导空白类字符后再判定首字符是否公式起始符(防 "\t=cmd|..." / " =cmd|..." 绕过),
    //         三处实现互指,修改须同步
    // │  Who  : Clipboard.write()(表头 + 字符串值)
    // │  When : 拼接 TSV 文本前
    // │  Where: jian-io-clipboard/Clipboard.java
    // │  How  : 关键变量变化:i 从 0 起跳过空格/Tab/CR/LF/NUL/BOM 六类字符,停在首个有效字符;
    // │           该字符 ∈ {=, +, -, @} → true(需加 ' 前缀);全为空白/空串 → false。
    // │         逻辑路线(三条路径):空串→false;首有效字符是公式符→true;否则 false。
    private static boolean startsWithFormulaAfterWhitespace(String s) {
        if (s.isEmpty()) return false;
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t'
                || s.charAt(i) == '\r' || s.charAt(i) == '\n'
                || s.charAt(i) == '\u0000' || s.charAt(i) == '\uFEFF')) {
            i++;
        }
        if (i >= s.length()) return false;
        char ch = s.charAt(i);
        return ch == '=' || ch == '+' || ch == '-' || ch == '@';
    }

    // ======================== 读(剪贴板 → DF)========================

    /** 从剪贴板读 DataFrame(按 TSV 解析,首行作列名)。 */
    public static DataFrame read() throws IOException {
        String tsv = readText();
        return parseTsv(tsv);
    }

    // ======================== 内部:平台命令探测 ========================

    private static boolean writeText(String text) throws IOException {
        if (testForceMemoryFallback) return false;  // 测试缝:强制走内存降级(见字段注释)
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
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // stderr/stdout 都丢弃:防子进程写满 pipe 缓冲区(典型 64KB)阻塞——
            // 写命令只需要 stdin;stdout 管道若不显式处理,异常路径下 fd 会挂到 GC。
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
            // try-with-resources 关闭输出流 + waitFor 带超时(防挂死)
            try (var pOut = p.getOutputStream()) {
                pOut.write(text.getBytes(StandardCharsets.UTF_8));
            }
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            return finished && p.exitValue() == 0;
        } catch (InterruptedException e) { // 恢复中断标志
            Thread.currentThread().interrupt();  // 恢复中断标志
            if (p != null && p.isAlive()) p.destroyForcibly();  // 中断路径同样回收进程
            return false;
        } catch (IOException e) {
            // 因为 write 阶段抛 IOException(管道破裂等)时进程可能仍在运行,
            // 所以 destroyForcibly 回收,防 fd/进程泄漏(不回收则进程挂起泄漏)
            if (p != null && p.isAlive()) p.destroyForcibly();
            return false;  // 命令不存在或写失败
        }
    }

    private static String readText() throws IOException {
        if (memoryFallback != null) return memoryFallback;
        if (testForceMemoryFallback) return "";   // 测试缝:无降级内容时不碰真实剪贴板(见字段注释)
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
            // 因为子进程 stderr 写满管道缓冲区会阻塞 stdout 读取,所以丢弃 stderr(与 writeText 同口径)
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            // 关闭 Process 的 InputStream + waitFor 带超时(防 native FD 泄漏 + 挂死)
            try (var is = p.getInputStream()) {
                String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                }
                // 失败退出码一次性提示(不抛错,保持优雅降级)
                // —— 因为命令失败/超时的 stdout 空串与"剪贴板确实为空"不可区分,所以提示一次。
                if ((!finished || p.exitValue() != 0) && !readFailWarned) {
                    readFailWarned = true;
                    System.err.println("[jian] 剪贴板读取命令失败(exit=" + p.exitValue()
                        + "),返回空内容;如刚安装 xclip/pbcopy,可调 Clipboard.resetMemoryFallback() 重试");
                }
                return text;
            }
        } catch (InterruptedException e) { // 恢复中断标志
            Thread.currentThread().interrupt();  // 恢复中断标志
            return "";
        }
    }

    /** 解析 TSV 字符串为 DataFrame(对齐 pandas.read_clipboard 按 \t 分列)。 */
    static DataFrame parseTsv(String tsv) {
        if (tsv == null || tsv.isBlank()) {
            return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
        }
        String[] lines = tsv.split("\r?\n");
        if (lines.length == 0) {
            return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
        }
        String[] header = lines[0].split("\t", -1);
        List<String> names = new ArrayList<>();
        // 因为空串列名轻则下游推断怪、重则两空字段触发"列名重复"IAE,
        // 所以空表头字段兜底 "_0"/"_1"(FwfReader 同款;pandas 用 Unnamed:N,语义相同)。
        for (int c = 0; c < header.length; c++) {
            names.add(header[c].trim().isEmpty() ? "_" + c : header[c]);
        }
        // 因为 Excel/WPS 允许两列同名,复制到剪贴板即 TSV 重复表头,而 Schema 校验对
        // 重名抛 IAE 会让整表读不进来(Csv/Excel 都自动去重,
        // 唯 Clipboard 抛错,三入口行为分裂),所以与 Csv.dedupHeaderNames 同口径
        // 去重:重名自动加 _1/_2 后缀(pandas mangle_dupe_cols 同语义;
        // jian 用 _1 而 pandas 用 .1,§10.16#16 已声明)。
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int c = 0; c < names.size(); c++) {
            String base = names.get(c);
            String cand = base;
            int k = 1;
            while (!seen.add(cand)) cand = base + "_" + k++;
            names.set(c, cand);
        }
        Object[][] rows = new Object[lines.length - 1][names.size()];
        for (int r = 1; r < lines.length; r++) {
            String[] parts = lines[r].split("\t", -1);
            for (int c = 0; c < names.size(); c++) {
                // 因为 TSV 与 Csv 两条读路径必须同口径(且 pandas read_clipboard 默认不 trim),
                // 所以不 trim;需要清洗的用户自行 df.applyToString(c -> c.trim())。
                String v = c < parts.length ? parts[c] : "";
                rows[r - 1][c] = v.isEmpty() ? null : v;
            }
        }
        return DataFrame.of(Schema.infer(names, rows), rows);
    }
}
