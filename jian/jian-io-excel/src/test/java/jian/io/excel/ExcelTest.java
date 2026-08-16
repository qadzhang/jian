package jian.io.excel;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : jian-io-excel 测试 —— 读写往返 + 多 sheet
class ExcelTest {

    @TempDir Path tmp;

    @Test
    void xlsx读写往返() throws Exception {
        Path p = tmp.resolve("data.xlsx");
        DataFrame df = df();
        Excel.write(df, p.toString()).sheetName("users").go();

        DataFrame r = Excel.read(p).sheet("users").go();
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.columnNames()).containsExactly("id", "name", "score");
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(r.getDoubleColumn("score").getDouble(1)).isEqualTo(85.0);
    }

    @Test
    void sheetNames枚举() throws Exception {
        Path p = tmp.resolve("multi.xlsx");
        try (Excel.ExcelMultiWriter w = Excel.writer(p.toString())) {
            w.write(df(), "Sheet1");
            w.write(df(), "Sheet2");
        }
        List<String> names = Excel.sheetNames(p.toString());
        assertThat(names).containsExactly("Sheet1", "Sheet2");
    }

    @Test
    void 多sheet写入与读取() throws Exception {
        Path p = tmp.resolve("multi.xlsx");
        DataFrame a = DataFrame.of(Schema.of("x", DType.LONG), new Object[][]{{1L}, {2L}});
        DataFrame b = DataFrame.of(Schema.of("y", DType.STRING), new Object[][]{{"a"}, {"b"}});
        try (Excel.ExcelMultiWriter w = Excel.writer(p.toString())) {
            w.write(a, "A");
            w.write(b, "B");
        }
        DataFrame ra = Excel.read(p).sheet("A").go();
        DataFrame rb = Excel.read(p).sheet("B").go();
        assertThat(ra.columnNames()).containsExactly("x");
        assertThat(rb.columnNames()).containsExactly("y");
        assertThat(rb.getStringColumn("y").get(0)).isEqualTo("a");
    }

    @Test
    void sheet不存在抛友好提示() throws Exception {
        Path p = tmp.resolve("x.xlsx");
        Excel.write(df(), p.toString()).go();
        try {
            Excel.read(p).sheet("NotExist").go();
            org.assertj.core.api.Assertions.fail("应抛异常");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("sheet 不存在");
        }
    }

    @Test
    void 整数列保留精度() throws Exception {
        // 因为 jian Excel.write→read 的写出走保精度路径,超 2^53 的 LONG 精确无损
        // (读回 dtype 仍 LONG、值逐位相等 —— 与外部 POI 直写文件的 double 降级不同,
        // 见 ExcelTypeTest.大整数超2_53同格收敛为Long),所以直接断言精确值,锁定往返无损契约。
        Path p = tmp.resolve("ids.xlsx");
        long bigId = 9_000_000_000_000_000_001L;
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING),
                new Object[][]{{bigId, "a"}, {bigId + 1, "b"}});
        Excel.write(df, p.toString()).go();
        DataFrame r = Excel.read(p.toString()).go();
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getColumn("id").get(0)).isEqualTo(bigId);      // 超 2^53 逐位无损
        assertThat(r.getColumn("id").get(1)).isEqualTo(bigId + 1);
        assertThat(r.getColumn("name").get(0)).isEqualTo("a");
        assertThat(r.getColumn("name").get(1)).isEqualTo("b");
    }

    private DataFrame df() {
        return DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{
                        {1L, "alice", 90.5},
                        {2L, "bob", 85.0},
                        {3L, "carol", 76.5}
                });
    }
}
