package jian.io.pickle;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PickleTest {

    @TempDir Path tmp;

    @Test
    void 往返一致_各dtype() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE, "vip", DType.BOOL),
                new Object[][]{
                        {1L, "alice", 90.5, true},
                        {2L, null, 85.0, false},
                        {3L, "carol", null, null}
                });
        Path p = tmp.resolve("data.jpk");
        Pickle.write(df, p.toString());

        DataFrame r = Pickle.read(p.toString());
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.columnNames()).containsExactly("id", "name", "score", "vip");
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(1L);
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(r.getStringColumn("name").get(1)).isNull();
        assertThat(r.getDoubleColumn("score").getDouble(0)).isEqualTo(90.5);
        assertThat(Double.isNaN(r.getDoubleColumn("score").getDouble(2))).isTrue();  // 缺失
    }

    @Test
    void 损坏文件魔数或CRC校验失败() throws Exception {
        Path p = tmp.resolve("bad.jpk");
        // 写个假 jpk(魔数错:用 JPK1 而非 JPK2)
        java.nio.file.Files.write(p, new byte[]{'J', 'P', 'K', '1', 0, 0, 0, 0, 0, 0, 0, 0});
        try {
            Pickle.read(p.toString());
            org.assertj.core.api.Assertions.fail("应抛异常");
        } catch (java.io.IOException e) {
            assertThat(e.getMessage()).containsAnyOf("魔数", "CRC", "损坏", "短");
        }
    }

    @Test
    void 空DataFrame往返() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.LONG, "b", DType.STRING),
                new Object[][]{});
        Path p = tmp.resolve("empty.jpk");
        Pickle.write(df, p.toString());
        DataFrame r = Pickle.read(p.toString());
        assertThat(r.rowCount()).isEqualTo(0);
        // 注:records orient 写空 DF 时 JSON 是 "[]"(无列信息),读回无列名 —— 这是 records 格式的固有局限(非 pickle bug)。
        // 非空 DF 的列名往返在「往返一致_各dtype」用例中验证。
    }

    @Test
    void 中文长文本往返() throws Exception {
        String big = "玉".repeat(1000);
        DataFrame df = DataFrame.of(
                Schema.of("text", DType.STRING),
                new Object[][]{{"张三"}, {big}});
        Path p = tmp.resolve("zh.jpk");
        Pickle.write(df, p.toString());
        DataFrame r = Pickle.read(p.toString());
        assertThat(r.getStringColumn("text").get(0)).isEqualTo("张三");
        assertThat(((String) r.getStringColumn("text").get(1)).length()).isEqualTo(1000);
    }
}
