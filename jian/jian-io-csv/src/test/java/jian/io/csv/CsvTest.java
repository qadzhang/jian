package jian.io.csv;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTest {

    @TempDir Path tmp;

    @Test
    void csv读写往返() throws Exception {
        Path p = tmp.resolve("users.csv");
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{{1L, "alice", 90.5}, {2L, "bob", 85.0}, {3L, "carol", 76.5}});
        Csv.write(df, p.toString()).go();

        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.columnNames()).containsExactly("id", "name", "score");
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(r.getDoubleColumn("score").getDouble(1)).isEqualTo(85.0);
    }

    @Test
    void tsv() throws Exception {
        Path p = tmp.resolve("data.tsv");
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.STRING, "b", DType.LONG),
                new Object[][]{{"x", 1L}, {"y", 2L}});
        Csv.write(df, p.toString()).delimiter('\t').go();
        DataFrame r = Csv.read(p.toString()).delimiter('\t').go();
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getStringColumn("a").get(0)).isEqualTo("x");
    }

    @Test
    void csv含中文() throws Exception {
        Path p = tmp.resolve("zh.csv");
        DataFrame df = DataFrame.of(
                Schema.of("姓名", DType.STRING, "年龄", DType.LONG),
                new Object[][]{{"张三", 30L}, {"李四", 25L}});
        Csv.write(df, p.toString()).go();
        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.getStringColumn("姓名").get(0)).isEqualTo("张三");
        assertThat(r.getLongColumn("年龄").getLong(1)).isEqualTo(25L);
    }

    @Test
    void csv缺失值() throws Exception {
        Path p = tmp.resolve("na.csv");
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{{1L, "alice", 90.0}, {2L, null, 80.0}});
        Csv.write(df, p.toString()).go();
        DataFrame r = Csv.read(p.toString()).go();
        assertThat(r.getStringColumn("name").get(1)).isNull();
    }

    @Test
    void fwf定宽() throws Exception {
        Path p = tmp.resolve("data.txt");
        java.nio.file.Files.writeString(p,
                "id   name      \n1    alice     \n2    bob       \n");
        DataFrame r = Csv.readFwf(p.toString()).widths(5, 10).go();
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.columnNames()).containsExactly("id", "name");
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    @Test
    void 无表头() throws Exception {
        Path p = tmp.resolve("noheader.csv");
        java.nio.file.Files.writeString(p, "1,alice,90\n2,bob,85\n");
        DataFrame r = Csv.read(p.toString()).header(false).go();
        assertThat(r.columnNames()).containsExactly("_0", "_1", "_2");
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void 写出不带表头() throws Exception {
        Path p = tmp.resolve("noheader_out.csv");
        DataFrame df = DataFrame.of(Schema.of("a", DType.LONG), new Object[][]{{1L}, {2L}});
        Csv.write(df, p.toString()).header(false).go();
        // 写出时没表头,读取时也用 header=false
        DataFrame r = Csv.read(p.toString()).header(false).go();
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void schema指定列类型() throws Exception {
        Path p = tmp.resolve("typed.csv");
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "phone", DType.LONG, "name", DType.STRING),
                new Object[][]{{1L, 13800000000L, "alice"}});
        Csv.write(df, p.toString()).go();
        Schema s = Schema.of("id", DType.LONG, "phone", DType.LONG, "name", DType.STRING);
        DataFrame r = Csv.read(p.toString()).schema(s).go();
        assertThat(r.getLongColumn("phone").getLong(0)).isEqualTo(13800000000L);
    }

    @Test
    void allString全部字符串() throws Exception {
        Path p = tmp.resolve("allstr.csv");
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "score", DType.DOUBLE),
                new Object[][]{{1L, 90.5}, {2L, 85.0}});
        Csv.write(df, p.toString()).go();
        DataFrame r = Csv.read(p.toString()).allString(true).go();
        assertThat(r.dtypes()).containsExactly(DType.STRING, DType.STRING);
        assertThat(r.getStringColumn("id").get(0)).isEqualTo("1");
        assertThat(r.getStringColumn("score").get(0)).isEqualTo("90.5");
    }

    @Test
    void allString手机号() throws Exception {
        Path p = tmp.resolve("phone.csv");
        DataFrame df = DataFrame.of(
                Schema.of("name", DType.STRING, "phone", DType.LONG),
                new Object[][]{{"alice", 13800000000L}});
        Csv.write(df, p.toString()).go();
        DataFrame r = Csv.read(p.toString()).allString(true).go();
        assertThat(r.getStringColumn("phone").get(0)).isEqualTo("13800000000");
    }

    // ======================== 安全:CSV 公式注入防护(§3.7.3) ========================

    @Test
    void 公式注入默认防护() throws Exception {
        Path p = tmp.resolve("formula.csv");
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.STRING, "b", DType.STRING),
                new Object[][]{{"=SUM(A1:A9)", "@cmd|calc.exe"}, {"normal", "-1"}});
        Csv.write(df, p.toString()).go();
        String content = java.nio.file.Files.readString(p);
        // = + @ 开头被前缀 ' 防 Excel/WPS 当公式执行;普通文本不受影响
        assertThat(content).contains("'=SUM(A1:A9)");
        assertThat(content).contains("'@cmd|calc.exe");
        assertThat(content).contains("normal");
    }

    @Test
    void 公式注入可关闭() throws Exception {
        Path p = tmp.resolve("formula_off.csv");
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.STRING),
                new Object[][]{{"=SUM(A1)"}});
        Csv.write(df, p.toString()).sanitizeFormulas(false).go();
        assertThat(java.nio.file.Files.readString(p)).contains("=SUM(A1)");
    }
}
