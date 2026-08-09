package jian.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : 阶段 C 重塑合并测试 —— pivot / explode / join / merge_asof / addScalarAllColumns / duplicated
// │  Why  : §3.16 路线图重塑合并类方法,A 级断言
// │  Who  : 阶段 C 落地回归测试
// │  When : 2026-08-09 阶段 C
// │  Where: jian-core/src/test/java/jian/core/StageCTest.java
class StageCTest {

    // ======================== pivot(简单透视,无聚合)========================

    @Test
    void pivot_简单长转宽() {
        DataFrame df = DataFrame.of(
            Schema.of("date", DType.STRING, "city", DType.STRING, "temp", DType.DOUBLE),
            new Object[][]{
                {"2026-01", "BJ", 5.0},
                {"2026-01", "SH", 10.0},
                {"2026-02", "BJ", 8.0},
                {"2026-02", "SH", 12.0}});
        DataFrame wide = df.pivot("date", "city", "temp");
        // 行数 = 2 个 date,列数 = 1 (date) + 2 个 city = 3
        assertThat(wide.rowCount()).isEqualTo(2);
        assertThat(wide.columnCount()).isEqualTo(3);
        assertThat(wide.columnNames()).contains("date", "BJ", "SH");
        // 验证:date=2026-01 的 BJ=5.0,SH=10.0
        assertThat(wide.get(0, "BJ")).isEqualTo(5.0);
        assertThat(wide.get(0, "SH")).isEqualTo(10.0);
        assertThat(wide.get(1, "BJ")).isEqualTo(8.0);
    }

    @Test
    void pivot_重复键抛IAE() {
        DataFrame df = DataFrame.of(
            Schema.of("k", DType.STRING, "c", DType.STRING, "v", DType.DOUBLE),
            new Object[][]{
                {"a", "x", 1.0},
                {"a", "x", 2.0}});  // 同 (a,x) 重复
        assertThatThrownBy(() -> df.pivot("k", "c", "v"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("重复");
    }

    @Test
    void pivot_缺失组合填null() {
        DataFrame df = DataFrame.of(
            Schema.of("k", DType.STRING, "c", DType.STRING, "v", DType.LONG),
            new Object[][]{
                {"a", "x", 1L},
                {"b", "y", 2L}});  // 缺 (a,y) 和 (b,x)
        DataFrame wide = df.pivot("k", "c", "v");
        assertThat(wide.rowCount()).isEqualTo(2);
        assertThat(wide.get(0, "y")).isNull();  // (a,y) 缺失
        assertThat(wide.get(1, "x")).isNull();  // (b,x) 缺失
    }

    // ======================== explode(列展平)========================

    @Test
    void explode_List列展平() {
        DataFrame df = DataFrame.of(
            Schema.of("id", DType.LONG, "tags", DType.OBJECT),
            new Object[][]{
                {1L, List.of("a", "b", "c")},
                {2L, List.of("x")}});
        DataFrame r = df.explode("tags");
        assertThat(r.rowCount()).isEqualTo(4);  // 3 + 1
        // 验证:id 列复制,tags 列展平
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(1L);
        assertThat(r.get(0, "tags")).isEqualTo("a");
        assertThat(r.get(2, "tags")).isEqualTo("c");
        assertThat(r.get(3, "tags")).isEqualTo("x");
    }

    @Test
    void explode_空List保留行() {
        DataFrame df = DataFrame.of(
            Schema.of("id", DType.LONG, "tags", DType.OBJECT),
            new Object[][]{
                {1L, List.of("a")},
                {2L, List.of()}});
        DataFrame r = df.explode("tags");
        assertThat(r.rowCount()).isEqualTo(2);  // 空列表保留 1 行(用 null)
    }

    @Test
    void explode_null保留行() {
        DataFrame df = DataFrame.of(
            Schema.of("id", DType.LONG, "tags", DType.OBJECT),
            new Object[][]{
                {1L, null}});
        DataFrame r = df.explode("tags");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.get(0, "tags")).isNull();
    }

    @Test
    void explode_非List单值当1元素() {
        DataFrame df = DataFrame.of(
            Schema.of("id", DType.LONG, "v", DType.OBJECT),
            new Object[][]{
                {1L, "hello"}});
        DataFrame r = df.explode("v");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.get(0, "v")).isEqualTo("hello");
    }

    // ======================== join(索引 join,简化等价 merge left)========================

    @Test
    void join_左连接() {
        DataFrame left = DataFrame.of(
            Schema.of("id", DType.LONG, "name", DType.STRING),
            new Object[][]{{1L, "alice"}, {2L, "bob"}, {3L, "carol"}});
        DataFrame right = DataFrame.of(
            Schema.of("id", DType.LONG, "age", DType.LONG),
            new Object[][]{{1L, 20L}, {2L, 30L}});
        DataFrame r = left.join(right, "id", "left");
        // 左连接:左表行数保留(3);右表无匹配的 age 为 null
        assertThat(r.rowCount()).isEqualTo(3);
        // carol(id=3)的 age 应为 null
        // 找到 id=3 的行
        boolean found3 = false;
        for (int i = 0; i < r.rowCount(); i++) {
            if (r.getLongColumn("id").getLong(i) == 3L) {
                found3 = true;
                assertThat(r.get(i, "age") == null).isTrue();
            }
        }
        assertThat(found3).isTrue();
    }

    @Test
    void join_inner连接() {
        DataFrame left = DataFrame.of(
            Schema.of("id", DType.LONG, "n", DType.STRING),
            new Object[][]{{1L, "a"}, {2L, "b"}, {3L, "c"}});
        DataFrame right = DataFrame.of(
            Schema.of("id", DType.LONG, "v", DType.LONG),
            new Object[][]{{1L, 100L}, {2L, 200L}});
        DataFrame r = left.join(right, "id", "inner");
        assertThat(r.rowCount()).isEqualTo(2);  // 只保留两表都有的 id
    }

    // ======================== merge_asof(按最近键对齐)========================

    @Test
    void mergeAsof_数值键backward() {
        DataFrame left = DataFrame.of(
            Schema.of("ts", DType.LONG, "lv", DType.STRING),
            new Object[][]{{10L, "a"}, {20L, "b"}, {30L, "c"}});
        DataFrame right = DataFrame.of(
            Schema.of("ts", DType.LONG, "rv", DType.STRING),
            new Object[][]{{5L, "x"}, {15L, "y"}, {25L, "z"}});
        DataFrame r = left.mergeAsof(right, "ts");
        // left.ts=10 → 找 ≤10 的最后 right.ts → 5(rv=x)
        // left.ts=20 → 找 ≤20 → 15(rv=y)
        // left.ts=30 → 找 ≤30 → 25(rv=z)
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.get(0, "rv")).isEqualTo("x");
        assertThat(r.get(1, "rv")).isEqualTo("y");
        assertThat(r.get(2, "rv")).isEqualTo("z");
    }

    @Test
    void mergeAsof_左键早于一切右键填null() {
        DataFrame left = DataFrame.of(
            Schema.of("ts", DType.LONG, "lv", DType.STRING),
            new Object[][]{{1L, "a"}});
        DataFrame right = DataFrame.of(
            Schema.of("ts", DType.LONG, "rv", DType.STRING),
            new Object[][]{{5L, "x"}});
        DataFrame r = left.mergeAsof(right, "ts");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.get(0, "rv")).isNull();  // 1 < 5,无匹配
    }

    @Test
    void mergeAsof_精确匹配也命中() {
        DataFrame left = DataFrame.of(
            Schema.of("ts", DType.LONG, "lv", DType.STRING),
            new Object[][]{{10L, "a"}});
        DataFrame right = DataFrame.of(
            Schema.of("ts", DType.LONG, "rv", DType.STRING),
            new Object[][]{{5L, "x"}, {10L, "y"}});
        DataFrame r = left.mergeAsof(right, "ts");
        // left.ts=10 → 找 ≤10 → 10(rv=y,精确匹配也命中)
        assertThat(r.get(0, "rv")).isEqualTo("y");
    }

    // ======================== add/sub/mul/div ScalarAllColumns(整 DF 二元)========================

    @Test
    void addScalarAllColumns_数值列加标量() {
        DataFrame df = DataFrame.of(
            Schema.of("a", DType.DOUBLE, "b", DType.LONG, "name", DType.STRING),
            new Object[][]{{1.0, 10L, "x"}, {2.0, 20L, "y"}});
        DataFrame r = df.addScalarAllColumns(100.0);
        // 列数 = 原 3 列 + 2 个数值新列 = 5
        assertThat(r.columnCount()).isEqualTo(5);
        assertThat(r.columnNames()).contains("a_add", "b_add");
        // 字符串列不应有 _add
        assertThat(r.columnNames()).doesNotContain("name_add");
        // 验证值
        assertThat(r.getDoubleColumn("a_add").getDouble(0)).isEqualTo(101.0);
        assertThat(r.getDoubleColumn("b_add").getDouble(1)).isEqualTo(120.0);
    }

    @Test
    void mulScalarAllColumns_乘标量() {
        DataFrame df = DataFrame.of(
            Schema.of("a", DType.DOUBLE),
            new Object[][]{{2.0}, {3.0}});
        DataFrame r = df.mulScalarAllColumns(10.0);
        assertThat(r.getDoubleColumn("a_mul").getDouble(0)).isEqualTo(20.0);
        assertThat(r.getDoubleColumn("a_mul").getDouble(1)).isEqualTo(30.0);
    }

    @Test
    void divScalarAllColumns_NaN保持NaN() {
        DataFrame df = DataFrame.of(
            Schema.of("a", DType.DOUBLE),
            new Object[][]{{4.0}, {Double.NaN}});
        DataFrame r = df.divScalarAllColumns(2.0);
        assertThat(r.getDoubleColumn("a_div").getDouble(0)).isEqualTo(2.0);
        assertThat(Double.isNaN(r.getDoubleColumn("a_div").getDouble(1))).isTrue();
    }

    // ======================== stack / unstack(L5 实现)========================

    @Test
    void stack_列转行() {
        DataFrame df = DataFrame.of(
            Schema.of("id", DType.LONG, "q1", DType.DOUBLE, "q2", DType.DOUBLE),
            new Object[][]{{1L, 100.0, 200.0}});
        DataFrame r = df.stack(new String[]{"id"}, new String[]{"q1", "q2"});
        assertThat(r.rowCount()).isEqualTo(2);  // 1 行 × 2 值列
        assertThat(r.columnNames()).contains("id", "variable", "value");
        // 第一行:id=1, variable=q1, value=100
        assertThat(r.getLongColumn("id").getLong(0)).isEqualTo(1L);
        assertThat(r.getColumn("variable").get(0)).isEqualTo("q1");
        assertThat(r.getDoubleColumn("value").getDouble(0)).isEqualTo(100.0);
    }

    @Test
    void unstack_行转列() {
        DataFrame df = DataFrame.of(
            Schema.of("id", DType.LONG, "variable", DType.STRING, "value", DType.DOUBLE),
            new Object[][]{
                {1L, "q1", 100.0},
                {1L, "q2", 200.0}});
        DataFrame r = df.unstack("id", "variable", "value");
        // 行:id 唯一值 = 1 → 1 行
        // 列:id + q1 + q2
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.columnNames()).contains("id", "q1", "q2");
        assertThat(r.get(0, "q1")).isEqualTo(100.0);
        assertThat(r.get(0, "q2")).isEqualTo(200.0);
    }
}
