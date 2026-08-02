package jian.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : M2.3 测试 —— pivot_table / melt / transpose / dropDuplicates
class DataFrameReshapeTest {

    @Test
    void pivotTable_长转宽() {
        DataFrame df = longDf();
        // 行=date, 列=product, 值=sales 求和
        DataFrame r = df.pivotTable("date", "product", "sales", "sum");
        // 行数 = 不同 date 数(2);列 = date + A + B
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.columnNames()).containsExactly("date", "A", "B");
        // 第 0 行(date=d1):A=100, B=110
        assertThat(r.getDoubleColumn("A").getDouble(0)).isEqualTo(100.0);
        assertThat(r.getDoubleColumn("B").getDouble(0)).isEqualTo(110.0);
    }

    @Test
    void pivotTable_mean默认() {
        DataFrame df = longDf();
        DataFrame r = df.pivotTable("date", "product", "sales");
        // d1/A 只有一个值 100,mean = 100
        assertThat(r.getDoubleColumn("A").getDouble(0)).isCloseTo(100.0, within(1e-10));
    }

    @Test
    void melt_宽转长() {
        DataFrame df = wideDf();
        // id=name, value cols = [q1, q2]
        DataFrame r = df.melt(new String[]{"name"}, new String[]{"q1", "q2"});
        // 原 2 行 × 2 值列 = 4 行
        assertThat(r.rowCount()).isEqualTo(4);
        assertThat(r.columnNames()).containsExactly("name", "variable", "value");
        // 第 0 行:alice, q1, 100
        assertThat(r.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(r.getStringColumn("variable").get(0)).isEqualTo("q1");
        assertThat(((Number) r.getColumn("value").get(0)).doubleValue()).isEqualTo(100.0);
    }

    @Test
    void transpose_行列互换() {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "v", DType.DOUBLE),
                new Object[][]{{1L, 10.0}, {2L, 20.0}, {3L, 30.0}});
        DataFrame t = df.T();
        // 原 3 行 2 列 → 2 行 3 列(+ _index 列)
        assertThat(t.rowCount()).isEqualTo(2);  // id, v
        assertThat(t.columnCount()).isEqualTo(4);  // _index + 3 行
        assertThat(t.get(0, "_index")).isEqualTo("id");
        assertThat(t.get(1, "_index")).isEqualTo("v");
    }

    @Test
    void dropDuplicates_默认keepFirst() {
        DataFrame df = DataFrame.of(
                Schema.of("k", DType.STRING, "v", DType.LONG),
                new Object[][]{{"a", 1L}, {"a", 2L}, {"b", 3L}, {"a", 4L}});
        DataFrame r = df.dropDuplicates(new String[]{"k"}, "first");
        assertThat(r.rowCount()).isEqualTo(2);  // a(首次), b
        assertThat(r.getStringColumn("k").data()).containsExactly("a", "b");
        assertThat(r.getLongColumn("v").getLong(0)).isEqualTo(1L);  // 首次 a 的 v=1
    }

    @Test
    void dropDuplicates_keepLast() {
        DataFrame df = DataFrame.of(
                Schema.of("k", DType.STRING, "v", DType.LONG),
                new Object[][]{{"a", 1L}, {"a", 2L}, {"b", 3L}, {"a", 4L}});
        DataFrame r = df.dropDuplicates(new String[]{"k"}, "last");
        // keepLast 保留末次出现:idx2(b,3) + idx3(a,4);filter 后按原序
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getStringColumn("k").data()).containsExactly("b", "a");
        assertThat(r.getLongColumn("v").getLong(0)).isEqualTo(3L);  // b 行 v=3
        assertThat(r.getLongColumn("v").getLong(1)).isEqualTo(4L);  // 末次 a v=4
    }

    @Test
    void dropDuplicates_全列去重() {
        DataFrame df = DataFrame.of(
                Schema.of("k", DType.STRING),
                new Object[][]{{"a"}, {"a"}, {"b"}});
        DataFrame r = df.dropDuplicates();
        assertThat(r.rowCount()).isEqualTo(2);  // a, b
    }

    private DataFrame longDf() {
        return DataFrame.of(
                Schema.of("date", DType.STRING, "product", DType.STRING, "sales", DType.DOUBLE),
                new Object[][]{
                        {"d1", "A", 100.0},
                        {"d1", "B", 110.0},
                        {"d2", "A", 200.0},
                        {"d2", "B", 210.0}
                });
    }

    private DataFrame wideDf() {
        return DataFrame.of(
                Schema.of("name", DType.STRING, "q1", DType.DOUBLE, "q2", DType.DOUBLE),
                new Object[][]{
                        {"alice", 100.0, 110.0},
                        {"bob", 200.0, 210.0}
                });
    }
}
