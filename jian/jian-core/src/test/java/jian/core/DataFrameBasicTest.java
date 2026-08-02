package jian.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : DataFrame 基础(M1.1)测试 —— 构造/属性/列访问/选择/过滤/切片/toString
// │  Why  : 验证多 dtype 列正确分发、整数精度、缺失值、链式选择/过滤、repr 截断
class DataFrameBasicTest {

    @Test
    void schema构造_各dtype正确分发() {
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE, "vip", DType.BOOL),
                new Object[][]{
                        {1L, "alice", 90.5, true},
                        {2L, "bob", 85.0, false},
                        {3L, "carol", null, null}
                });
        assertThat(df.rowCount()).isEqualTo(3);
        assertThat(df.columnCount()).isEqualTo(4);
        assertThat(df.columnNames()).containsExactly("id", "name", "score", "vip");
        assertThat(df.dtypes()).containsExactly(DType.LONG, DType.STRING, DType.DOUBLE, DType.BOOL);
        assertThat(df.shape()).containsExactly(3, 4);
    }

    @Test
    void 整数列保留精度_大ID不丢() {
        long bigId = 9_000_000_000_000_000_001L;
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG),
                new Object[][]{{bigId}, {bigId + 1}});
        assertThat(df.getLongColumn("id").getLong(0)).isEqualTo(bigId);
        assertThat(df.getLongColumn("id").getLong(1)).isEqualTo(bigId + 1);
    }

    @Test
    void 缺失值正确表示() {
        DataFrame df = DataFrame.of(
                Schema.of("score", DType.DOUBLE, "name", DType.STRING),
                new Object[][]{
                        {90.5, "alice"},
                        {null, null},
                        {85.0, "bob"}
                });
        // DOUBLE 缺失 = NaN
        assertThat(Double.isNaN(df.getDoubleColumn("score").getDouble(1))).isTrue();
        assertThat(df.getDoubleColumn("score").nullCount()).isEqualTo(1);
        // STRING 缺失 = null
        assertThat(df.getStringColumn("name").get(1)).isNull();
        assertThat(df.getStringColumn("name").nullCount()).isEqualTo(1);
    }

    @Test
    void 列式Map构造_自动类型推断() {
        Map<String, Object[]> cols = new LinkedHashMap<>();
        cols.put("id", new Object[]{1, 2, 3});           // 推断为 INT
        cols.put("price", new Object[]{1.5, 2.5, 3.5});  // DOUBLE
        cols.put("name", new Object[]{"a", "b", "c"});   // STRING
        DataFrame df = DataFrame.ofColumns(cols);
        assertThat(df.dtypes()).containsExactly(DType.INT, DType.DOUBLE, DType.STRING);
        assertThat(df.rowCount()).isEqualTo(3);
    }

    @Test
    void select选列子集() {
        DataFrame df = sample();
        DataFrame sub = df.select("name", "score");
        assertThat(sub.columnNames()).containsExactly("name", "score");
        assertThat(sub.rowCount()).isEqualTo(3);
    }

    @Test
    void drop丢弃指定列() {
        DataFrame df = sample();
        DataFrame sub = df.drop("vip");
        assertThat(sub.columnNames()).containsExactly("id", "name", "score");
    }

    @Test
    void filter按掩码过滤行() {
        DataFrame df = sample();
        // 手动构造 mask:score > 86
        boolean[] m = new boolean[df.rowCount()];
        for (int i = 0; i < m.length; i++) m[i] = df.getDoubleColumn("score").getDouble(i) > 86;
        DataFrame filtered = df.filter(m);
        assertThat(filtered.rowCount()).isEqualTo(2);  // alice(90.5), bob(87.0)
        assertThat(filtered.getStringColumn("name").data())
                .containsExactly("alice", "bob");
    }

    @Test
    void head和tail() {
        DataFrame df = sample();
        DataFrame h = df.head(2);
        assertThat(h.rowCount()).isEqualTo(2);
        assertThat(h.getStringColumn("name").get(0)).isEqualTo("alice");
        assertThat(h.getStringColumn("name").get(1)).isEqualTo("bob");

        DataFrame t = df.tail(1);
        assertThat(t.rowCount()).isEqualTo(1);
        assertThat(t.getStringColumn("name").get(0)).isEqualTo("carol");
    }

    @Test
    void slice行切片() {
        DataFrame df = sample();
        DataFrame s = df.slice(1, 3);
        assertThat(s.rowCount()).isEqualTo(2);
        assertThat(s.getStringColumn("name").get(0)).isEqualTo("bob");
    }

    @Test
    void 取值与行迭代() {
        DataFrame df = sample();
        assertThat(df.get(0, "name")).isEqualTo("alice");
        assertThat(df.get(1, 0)).isEqualTo(2L);

        Object[] firstRow = df.getRow(0);
        assertThat(firstRow).containsExactly(1L, "alice", 90.5, true);

        // iterRows
        int count = 0;
        for (Object[] row : df.iterRows()) {
            assertThat(row).hasSize(4);
            count++;
        }
        assertThat(count).isEqualTo(3);
    }

    @Test
    void 列不存在抛异常带提示() {
        DataFrame df = sample();
        assertThatThrownBy(() -> df.getColumn("notExist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在")
                .hasMessageContaining("现有列");
    }

    @Test
    void 类型不匹配取列抛异常() {
        DataFrame df = sample();
        assertThatThrownBy(() -> df.getStringColumn("id"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是 STRING");
    }

    @Test
    void toString含维度摘要() {
        DataFrame df = sample();
        String s = df.toString();
        assertThat(s).contains("3 rows × 4 columns");
        assertThat(s).contains("alice");
    }

    @Test
    void toString大表截断() {
        // 100 行,只显示头尾
        Object[][] rows = new Object[100][];
        for (int i = 0; i < 100; i++) rows[i] = new Object[]{"name" + i, (double) i};
        DataFrame df = DataFrame.of(
                Schema.of("name", DType.STRING, "idx", DType.DOUBLE), rows);
        String s = df.toString(10, 20);
        assertThat(s).contains("...");
        assertThat(s).contains("100 rows × 2 columns");
    }

    @Test
    void schema列名重复抛异常() {
        assertThatThrownBy(() ->
                Schema.of("a", DType.INT, "a", DType.DOUBLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列名重复");
    }

    @Test
    void 链式调用_selectFilterHead() {
        DataFrame df = sample();
        DataFrame r = df
                .drop("vip")
                .select("name", "score")
                .head(2);
        assertThat(r.columnNames()).containsExactly("name", "score");
        assertThat(r.rowCount()).isEqualTo(2);
    }

    private DataFrame sample() {
        return DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE, "vip", DType.BOOL),
                new Object[][]{
                        {1L, "alice", 90.5, true},
                        {2L, "bob", 87.0, false},
                        {3L, "carol", 76.5, true}
                });
    }
}
