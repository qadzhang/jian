package jian.io.orc;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : OrcRegressionTest —— ORC 列存读写回归测试集
// │  Why  : 固化 ORC 读写行为(因为 BOOL/INT 往返类型必须保真(BOOL 不退化为
// │         LONG 0/1、INT 不升位 LONG)、0 行 dtype 必须按文件元数据还原(不丢列、
// │         不全退化 STRING)等边界行为一旦退化会丢类型信息,所以全部固化为本测试集)。
// │  Who  : CI(./mvnw -Pcolumnar test -pl jian-io-orc)
// │  When : 改动 Orc.java 的 vectorValue / 0 行 Schema 构建后必须跑
// │  Where: jian-io-orc/src/test/java/jian/io/orc/OrcRegressionTest.java
// │  How  : 数据走向:DataFrame → Orc.write → .orc 文件 → Orc.read →
// │         断言 dtype 与值逐列相等。
class OrcRegressionTest {

    @TempDir Path tmp;

    // ======================== BOOL/INT 往返类型保真 ========================

    @Test
    void BOOL列往返保持BOOL_值还原为Boolean() throws Exception {
        // 因为读侧若对 LongColumnVector 一律装箱 Long,vip 列会退化为 dtype=LONG、
        // 首值 1(非 true),所以按列的 ORC 类型映射回 Boolean
        DataFrame df = DataFrame.of(
                Schema.of("vip", DType.BOOL),
                new Object[][]{{true}, {false}, {true}});
        Path p = tmp.resolve("bool.orc");
        Orc.write(df, p.toString()).go();
        DataFrame r = Orc.read(p.toString()).go();
        assertThat(r.dtypes().get(0)).as("BOOL 往返应为 BOOL").isEqualTo(DType.BOOL);
        assertThat(r.getColumn("vip").get(0)).isEqualTo(true);
        assertThat(r.getColumn("vip").get(1)).isEqualTo(false);
        assertThat(r.getColumn("vip").get(2)).isEqualTo(true);
    }

    @Test
    void INT列往返保持INT() throws Exception {
        // 因为读侧若把整型统一装箱 Long,INT 列会升位为 LONG,所以按写侧 schema 的
        // 宽度(Integer/Long)精确还原
        DataFrame df = DataFrame.of(
                Schema.of("n", DType.INT),
                new Object[][]{{7}, {42}});
        Path p = tmp.resolve("int.orc");
        Orc.write(df, p.toString()).go();
        DataFrame r = Orc.read(p.toString()).go();
        assertThat(r.dtypes().get(0)).as("INT 往返应为 INT").isEqualTo(DType.INT);
        assertThat(r.getColumn("n").get(0)).isInstanceOf(Integer.class).isEqualTo(7);
        assertThat(r.getColumn("n").get(1)).isEqualTo(42);
    }

    @Test
    void LONG_DOUBLE_STRING列往返dtype不回归() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "score", DType.DOUBLE, "name", DType.STRING),
                new Object[][]{{1L, 90.5, "alice"}});
        Path p = tmp.resolve("basic.orc");
        Orc.write(df, p.toString()).go();
        DataFrame r = Orc.read(p.toString()).go();
        assertThat(r.dtypes()).containsExactly(DType.LONG, DType.DOUBLE, DType.STRING);
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(1L);
        assertThat(r.getDoubleColumn("score").getDouble(0)).isEqualTo(90.5);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
    }

    // ======================== 0 行保列(dtype 不退化) ========================

    @Test
    void 零行DataFrame往返保留列名与dtype() throws Exception {
        // 因为 Schema.infer(空数据) 会把全列推断成 STRING,0 行读回若走数据推断
        // 会得到 [STRING,STRING,STRING],所以 dtype 按文件元数据还原
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "vip", DType.BOOL, "name", DType.STRING),
                new Object[0][3]);
        assertThat(df.rowCount()).isEqualTo(0);
        Path p = tmp.resolve("empty.orc");
        Orc.write(df, p.toString()).go();
        DataFrame r = Orc.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(0);
        assertThat(r.columnNames()).as("0 行不丢列").containsExactly("id", "vip", "name");
        assertThat(r.dtypes()).as("0 行 dtype 按文件元数据还原").containsExactly(DType.LONG, DType.BOOL, DType.STRING);
    }

    @Test
    void 零行INT_DOUBLE列dtype同样保真() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("n", DType.INT, "f", DType.DOUBLE),
                new Object[0][2]);
        Path p = tmp.resolve("empty2.orc");
        Orc.write(df, p.toString()).go();
        DataFrame r = Orc.read(p.toString()).go();
        assertThat(r.columnNames()).containsExactly("n", "f");
        assertThat(r.dtypes()).containsExactly(DType.INT, DType.DOUBLE);
    }
}
