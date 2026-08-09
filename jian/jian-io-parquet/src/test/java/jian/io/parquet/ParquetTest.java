package jian.io.parquet;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ParquetTest {

    @TempDir Path tmp;

    @Test
    void 往返一致() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE, "vip", DType.BOOL),
                new Object[][]{
                        {1L, "alice", 90.5, true},
                        {2L, "bob", 85.0, false},
                        {3L, "carol", 76.5, true}
                });
        Path p = tmp.resolve("data.parquet");
        Parquet.write(df, p.toString()).go();
        assertThat(java.nio.file.Files.size(p)).isGreaterThan(0);

        DataFrame r = Parquet.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.columnNames()).containsExactly("id", "name", "score", "vip");
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(1L);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(r.getDoubleColumn("score").getDouble(1)).isEqualTo(85.0);
    }

    @Test
    void 缺失值往返() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE, "s", DType.STRING),
                new Object[][]{{1.0, "x"}, {null, null}, {3.0, "z"}});
        Path p = tmp.resolve("na.parquet");
        Parquet.write(df, p.toString()).go();

        DataFrame r = Parquet.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(3);
        // 第 2 行缺失。
        // 缺失值语义(AGENTS.md §3.5):DOUBLE 列内部用 NaN 表示缺失,
        // 所以 DoubleColumn.get(缺失行) 返回 Double.NaN(不返回 null),与 getDouble 一致;
        // 但 IO 边界(getRow / export)统一用 isNull() 判断。这里用 isNull() 测,符合语义。
        assertThat(r.getColumn("v").isNull(1)).isTrue();
        assertThat(r.getStringColumn("s").isNull(1)).isTrue();
        // 双重保险:DOUBLE 列缺失行的 getDouble 应是 NaN(get 则返回 Double.NaN 装箱)
        assertThat(r.getDoubleColumn("v").getDouble(1)).isNaN();
        assertThat(r.getDoubleColumn("v").get(1)).isEqualTo(Double.NaN);
    }

    @Test
    void 含中文() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("姓名", DType.STRING, "年龄", DType.LONG),
                new Object[][]{{"张三", 30L}, {"李四", 25L}});
        Path p = tmp.resolve("zh.parquet");
        Parquet.write(df, p.toString()).go();
        DataFrame r = Parquet.read(p.toString()).go();
        assertThat(r.getStringColumn("姓名").get(0)).isEqualTo("张三");
        assertThat(r.getLongColumn("年龄").getLong(1)).isEqualTo(25L);
    }
}
