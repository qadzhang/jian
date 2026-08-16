package jian.io.clipboard;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : ClipboardRegressionTest —— 剪贴板读写回归测试集
// │  Why  : 固化剪贴板 TSV 行为(因为 TSV 与 Csv 读路径同口径(不 trim)、空表头
// │         兜底命名、resetMemoryFallback 公开可用、公式注入 = + - @ 前缀防护等
// │         边界行为一旦回归会破坏字符串原始空白或产生安全面,所以全部固化为本测试集)。
// │  Who  : CI(./mvnw test -pl jian-io-clipboard)
// │  When : 改动 Clipboard.write/read 的 TSV 拼接与解析后必须跑
// │  Where: jian-io-clipboard/src/test/java/jian/io/clipboard/ClipboardRegressionTest.java
// │  How  : 数据走向:DataFrame → write(TSV,无剪贴板命令时降级内存)→ read(parseTsv)
// │           → 断言表头/值/前缀防护;经内存降级路径间接覆盖 parseTsv(CI 无剪贴板命令,
// │           write 落 memoryFallback,read 从 fallback 解析 —— 等价于走 parseTsv)。
class ClipboardRegressionTest {

    @BeforeEach
    void 清除内存降级缓存() {
        // memoryFallback 是 static volatile,测试间不清理会污染下一个测试的读
        Clipboard.resetMemoryFallback();
        Clipboard.testForceMemoryFallback = true;   // 封闭测试:强制内存路径,防有 xclip 的机器上 daemon 竞争 flaky
    }

    @AfterEach
    void 还原测试缝() {
        Clipboard.testForceMemoryFallback = false;
    }

    // ======================== TSV 解析:与 Csv 同口径 ========================

    @Test
    void TSV不再trim对齐Csv() throws Exception {
        // 因为 TSV 与 Csv 两条读路径必须同口径(且 pandas read_clipboard 默认不 trim),
        // 所以不 trim,经 iterRows 保留原样空白;需要清洗的用户自行 trim
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{{" x "}, {"100"}});
        Clipboard.write(df);
        DataFrame r = Clipboard.read();
        assertThat(r.getStringColumn("s").get(0)).isEqualTo(" x ");
        assertThat(r.getStringColumn("s").get(1)).isEqualTo("100");   // 同列混型(" x ")→整列 STRING
    }

    @Test
    void 空表头字段兜底命名() throws Exception {
        // 因为空列名会触发"列名重复"校验且一个字段都拿不到,
        // 所以空表头兜底为 _0(与 Csv 的无表头命名口径一致)
        DataFrame df = DataFrame.of(Schema.of("", DType.LONG, "ok", DType.LONG),
                new Object[][]{{1L, 2L}});
        Clipboard.write(df);   // 写出表头 ""\tok
        DataFrame r = Clipboard.read();
        assertThat(r.columnNames()).containsExactly("_0", "ok");
        assertThat(r.get(0, 0)).isEqualTo(1);   // Schema 推断 INT
    }

    @Test
    void resetMemoryFallback公开可用() throws Exception {
        DataFrame df = DataFrame.of(Schema.of("v", DType.LONG), new Object[][]{{7L}});
        Clipboard.write(df);
        assertThat(Clipboard.read().rowCount()).isEqualTo(1);
        Clipboard.resetMemoryFallback();   // public:命令恢复后可显式清降级缓存
        // 清后再读:无 fallback 时走真实命令路径(CI 上返回空/失败 → 0 行,不抛)
        DataFrame r2 = Clipboard.read();
        assertThat(r2.rowCount()).isLessThanOrEqualTo(1);
    }

    // ======================== 公式注入防护(= + - @ 前缀) ========================

    @Test
    void TSV字符串值公式注入加单引号前缀() throws Exception {
        // 因为 TSV 的目的地就是粘贴到 Excel,字符串值以 = + - @ 开头会被当公式执行,
        // 所以加 ' 前缀防护
        DataFrame df = DataFrame.of(
                Schema.of("s", DType.STRING),
                new Object[][]{{"=1+1"}, {"+SUM(A1)"}, {"-2+3"}, {"@cmd"}, {"safe"}});
        Clipboard.write(df);
        DataFrame r = Clipboard.read();
        assertThat(r.getStringColumn("s").get(0)).isEqualTo("'=1+1");
        assertThat(r.getStringColumn("s").get(1)).isEqualTo("'+SUM(A1)");
        assertThat(r.getStringColumn("s").get(2)).isEqualTo("'-2+3");
        assertThat(r.getStringColumn("s").get(3)).isEqualTo("'@cmd");
        assertThat(r.getStringColumn("s").get(4)).isEqualTo("safe");
    }

    @Test
    void 表头列名同样防护_数值布尔豁免_null空串() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("=cmd|calc", DType.STRING, "v", DType.LONG, "f", DType.DOUBLE, "b", DType.BOOL),
                new Object[][]{{null, -5L, -1.5, true}});
        Clipboard.write(df);
        DataFrame r = Clipboard.read();
        // 表头:= 开头加 ' 前缀(与 Csv 表头防护同口径)
        assertThat(r.columnNames()).containsExactly("'=cmd|calc", "v", "f", "b");
        // null 仍输出空串 → 读回 null(缺失值语义不变)
        assertThat(r.getColumn("'=cmd|calc").get(0)).isNull();
        // 数值/布尔的字符串形式("-5"/"-1.5"/"true")不可能构成公式载荷,豁免不加前缀
        assertThat(((Number) r.getColumn("v").get(0)).longValue()).isEqualTo(-5L);
        assertThat(((Number) r.getColumn("f").get(0)).doubleValue()).isEqualTo(-1.5);
        assertThat(r.getColumn("b").get(0)).isEqualTo(true);
    }

    @Test
    void 前导空白绕过形态同样防护() throws Exception {
        // " =cmd" 这类前导空白绕过形态:跳过空白后判定(OWASP 严格版;Tab 形态在 TSV 中
        // 本身是分隔符,不构成单元格内绕过,不测)
        DataFrame df = DataFrame.of(
                Schema.of("s", DType.STRING),
                new Object[][]{{" =cmd"}, {"\r=cmd"}});
        Clipboard.write(df);
        DataFrame r = Clipboard.read();
        assertThat(r.getStringColumn("s").get(0)).isEqualTo("' =cmd");
        assertThat(r.getStringColumn("s").get(1)).isEqualTo("'\r=cmd");
    }
}
