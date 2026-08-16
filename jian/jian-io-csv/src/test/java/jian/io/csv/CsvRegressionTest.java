package jian.io.csv;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : CsvRegressionTest —— CSV 读写回归测试集
// │  Why  : 固化 CSV 读取/写出行为(因为空文件、多字段、BOM、公式注入防护、
// │         重复表头等边界行为一旦回归会破坏下游 ETL,所以全部固化为本测试集)。
// │  Who  : CI(./mvnw test -pl jian-io-csv)
// │  When : 改动 CsvReader/CsvWriter/FwfReader 行为后必须跑
// │  Where: jian-io-csv/src/test/java/jian/io/csv/CsvRegressionTest.java
// │  How  : 数据走向:临时文件(空/BOM/多字段/重复表头/公式载荷)→ Csv.read/readFwf/
// │         Csv.write → 断言列名、行数、值与 dtype。
class CsvRegressionTest {

    @TempDir Path tmp;

    // ======================== 读取:空文件 / 多字段 / BOM ========================

    @Test
    void 空文件不再产出UFFFF幽灵列() throws Exception {
        Path p = tmp.resolve("empty.csv");
        Files.writeString(p, "");
        DataFrame df = Csv.read(p.toString()).go();
        // 因为预读字符在 EOF 不回推(否则 (char)-1 = U+FFFF 被当列名),
        // 所以空文件读出 0 列 0 行
        assertThat(df.rowCount()).isZero();
        assertThat(df.columnNames()).isEmpty();
    }

    @Test
    void 多字段截断保留并告警一次() throws Exception {
        Path p = tmp.resolve("extra.csv");
        Files.writeString(p, "a,b\n1,2,3,4\n5,6\n");
        DataFrame df = Csv.read(p.toString()).go();
        // 保留截断(宽容语义,数据不丢前 2 列)
        assertThat(df.columnNames()).containsExactly("a", "b");
        assertThat(df.get(0, 0)).isEqualTo(1);   // Schema 推断转数值
        assertThat(df.get(0, 1)).isEqualTo(2);
        assertThat(df.rowCount()).isEqualTo(2);
    }

    @Test
    void 多字段告警可关() throws Exception {
        Path p = tmp.resolve("extra2.csv");
        Files.writeString(p, "a,b\n1,2,3\n");
        DataFrame df = Csv.read(p.toString()).warnExtraCols(false).go();
        assertThat(df.get(0, 0)).isEqualTo(1);   // 不警告但不抛(开关生效路径)
    }

    @Test
    void 仅UTF8_BOM自动剥离() throws Exception {
        Path p = tmp.resolve("bom.csv");
        Files.write(p, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        Files.writeString(p, "a,b\n1,2\n", java.nio.file.StandardOpenOption.APPEND);
        DataFrame df = Csv.read(p.toString()).go();
        assertThat(df.columnNames()).containsExactly("a", "b");
    }

    // ======================== 写出:公式注入防护 ========================

    @Test
    void 公式注入跳过NUL与BOM前缀() throws Exception {
        Path p = tmp.resolve("f.csv");
        DataFrame df = DataFrame.ofColumns(new java.util.LinkedHashMap<>(java.util.Map.of("s",
                new Object[]{"\u0000=1+1", "\uFEFF=cmd", "normal", " =space"})));
        Csv.write(df, p.toString()).go();
        String out = Files.readString(p);
        // NUL/BOM/空格前缀的公式串都被加 ' 转义
        assertThat(out).contains("'\u0000=1+1").contains("'\uFEFF=cmd").contains("' =space");
        assertThat(out).contains("normal");
    }

    @Test
    void 表头公式载荷被防护() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("=cmd|calc", DType.LONG, "+name", DType.STRING, "@more", DType.STRING),
                new Object[][]{{1L, "x", "y"}});
        Path p = tmp.resolve("hdr.csv");
        Csv.write(df, p.toString()).go();
        String first = Files.readString(p).lines().findFirst().orElse("");
        assertThat(first).as("表头列名过 sanitize(加 ' 前缀)").startsWith("'");
        assertThat(first).doesNotContain("=cmd|calc,+name,@more");
    }

    @Test
    void 负数数值列roundTrip保dtype不被注入前缀污染() throws Exception {
        // 因为数值/布尔的字符串形式不可能构成公式载荷,所以不加 ' 前缀
        // (否则读回整列降 STRING 且值带字面 ')
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{-0.0}, {-1.5}, {1.5}});
        Path p = tmp.resolve("neg.csv");
        Csv.write(df, p.toString()).go();
        assertThat(Files.readString(p)).doesNotContain("'");       // 数值无 ' 前缀
        DataFrame back = Csv.read(p.toString()).go();
        assertThat(back.getColumn("v").dtype()).isEqualTo(DType.DOUBLE);
        assertThat(back.getColumn("v").getDouble(1)).isEqualTo(-1.5);
        // 字符串列的注入防护不受影响("=cmd" 仍加 ')
        DataFrame s = DataFrame.of(Schema.of("x", DType.STRING), new Object[][]{{"=cmd|calc"}});
        Path p2 = tmp.resolve("inj.csv");
        Csv.write(s, p2.toString()).go();
        assertThat(Files.readString(p2)).contains("'=cmd|calc");
    }

    // ======================== 读取:重复表头自动改名 ========================

    @Test
    void 重复表头自动改名加_1后缀() throws Exception {
        // 因为重复表头若不改名会触发"列名重复"校验、一个字段都拿不到,
        // 所以第 2 个重复名加 _1(有意差异:pandas 用 name.1,jian 统一 _1 与 Excel 一致)。
        Path p = tmp.resolve("dup.csv");
        Files.writeString(p, "id,name,name\n1,alice,alice2\n2,bob,bob2\n");
        DataFrame df = Csv.read(p.toString()).go();
        assertThat(df.columnNames()).containsExactly("id", "name", "name_1");
        assertThat(df.rowCount()).isEqualTo(2);
        assertThat(((Number) df.getColumn("id").get(1)).longValue()).isEqualTo(2L);   // 数值列照常推断
        assertThat(df.getColumn("name").get(0)).isEqualTo("alice");
        assertThat(df.getColumn("name_1").get(0)).isEqualTo("alice2");
    }

    @Test
    void 三重重复表头依次加_1_2() throws Exception {
        Path p = tmp.resolve("dup3.csv");
        Files.writeString(p, "name,name,name\na,b,c\n");
        DataFrame df = Csv.read(p.toString()).go();
        assertThat(df.columnNames()).containsExactly("name", "name_1", "name_2");
        assertThat(df.getColumn("name_1").get(0)).isEqualTo("b");
        assertThat(df.getColumn("name_2").get(0)).isEqualTo("c");
    }

    @Test
    void 无重复表头不受影响() throws Exception {
        Path p = tmp.resolve("normal.csv");
        Files.writeString(p, "id,name\n1,alice\n");
        DataFrame df = Csv.read(p.toString()).go();
        assertThat(df.columnNames()).containsExactly("id", "name");
    }

    @Test
    void header为false时重复不存在不走改名() throws Exception {
        Path p = tmp.resolve("noheader.csv");
        Files.writeString(p, "1,2\n3,4\n");
        DataFrame df = Csv.read(p.toString()).header(false).go();
        assertThat(df.columnNames()).containsExactly("_0", "_1");
        assertThat(df.rowCount()).isEqualTo(2);
    }

    // ======================== FWF:剥 UTF-8 BOM ========================

    @Test
    void 带BOM的FWF首列名不带BOM字符() throws Exception {
        // 因为 Files.readAllLines 不过滤 BOM(首列名会变 "\uFEFFid" 且宽度错位一列),
        // 所以 FWF 与 CsvReader 同口径先剥 BOM。
        Path p = tmp.resolve("bom.fwf");
        // UTF-8 BOM(EF BB BF)用字符 \uFEFF 写出(UTF-8 编码下正好 3 字节 BOM)
        Files.writeString(p, "\uFEFFid  name    \n1   alice   \n2   bob     \n");
        DataFrame df = Csv.readFwf(p.toString()).widths(4, 9).go();
        assertThat(df.columnNames()).as("BOM 应被剥离,首列名为 id").containsExactly("id", "name");
        assertThat(df.rowCount()).isEqualTo(2);
        assertThat(((Number) df.getColumn("id").get(0)).longValue()).isEqualTo(1L);
        assertThat(df.getColumn("name").get(1)).isEqualTo("bob");
    }

    @Test
    void 无BOM的FWF行为不变() throws Exception {
        Path p = tmp.resolve("plain.fwf");
        Files.writeString(p, "id  name\n1   alice\n");
        DataFrame df = Csv.readFwf(p.toString()).widths(4, 4).go();
        assertThat(df.columnNames()).containsExactly("id", "name");
    }
}
