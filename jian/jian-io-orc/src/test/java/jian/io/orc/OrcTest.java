package jian.io.orc;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OrcTest {

    @TempDir Path tmp;

    @Test
    void 往返一致() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{
                        {1L, "alice", 90.5},
                        {2L, "bob", 85.0},
                        {3L, "carol", 76.5}
                });
        Path p = tmp.resolve("data.orc");
        Orc.write(df, p.toString()).go();
        assertThat(java.nio.file.Files.size(p)).isGreaterThan(0L);

        DataFrame r = Orc.read(p).go();
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.columnNames()).containsExactly("id", "name", "score");
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(1L);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(r.getDoubleColumn("score").getDouble(1)).isEqualTo(85.0);
    }

    @Test
    void 缺失值() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE, "s", DType.STRING),
                new Object[][]{{1.0, "x"}, {null, null}, {3.0, "z"}});
        Path p = tmp.resolve("na.orc");
        Orc.write(df, p.toString()).go();
        DataFrame r = Orc.read(p).go();
        assertThat(r.rowCount()).isEqualTo(3);
        // 缺失值语义(AGENTS.md §3.5):DOUBLE 列内部用 NaN 表示缺失,
        // DoubleColumn.get(缺失行) 返回 Double.NaN(不返回 null);用 isNull() 判断。
        assertThat(r.getColumn("v").isNull(1)).isTrue();
        assertThat(r.getStringColumn("s").isNull(1)).isTrue();
    }

    @Test
    void 含中文() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("姓名", DType.STRING),
                new Object[][]{{"张三"}, {"李四"}});
        Path p = tmp.resolve("zh.orc");
        Orc.write(df, p.toString()).go();
        DataFrame r = Orc.read(p).go();
        assertThat(r.getStringColumn("姓名").get(0)).isEqualTo("张三");
    }
}
