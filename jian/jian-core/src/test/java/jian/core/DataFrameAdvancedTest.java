package jian.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : M2.1/M2.2 测试 —— sort / groupby / 列级算术 / nlargest / nsmallest
class DataFrameAdvancedTest {

    @Test
    void sortBy_单列升序() {
        DataFrame df = sample();
        DataFrame r = df.sortBy("score", true);
        // 80(carol) < 85(bob) < 90(alice)
        assertThat(r.getStringColumn("name").data()).containsExactly("carol", "bob", "alice");
    }

    @Test
    void sortBy_单列降序() {
        DataFrame df = sample();
        DataFrame r = df.sortBy("score", false);
        assertThat(r.getStringColumn("name").data()).containsExactly("alice", "bob", "carol");
    }

    @Test
    void sortBy_多列混合升降序() {
        DataFrame df = DataFrame.of(
                Schema.of("dept", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{
                        {"A", 90.0}, {"B", 80.0}, {"A", 85.0}, {"B", 80.0}
                });
        // dept 升序,score 降序
        DataFrame r = df.sortBy(new String[]{"dept", "score"}, new boolean[]{true, false});
        // A 组:90, 85;B 组:80, 80(同分保持原序)
        assertThat(r.getStringColumn("dept").data()).containsExactly("A", "A", "B", "B");
        assertThat(r.getDoubleColumn("score").getDouble(0)).isEqualTo(90.0);
        assertThat(r.getDoubleColumn("score").getDouble(1)).isEqualTo(85.0);
    }

    @Test
    void sortBy_缺失值放最后() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {null}, {3.0}});
        DataFrame r = df.sortBy("v", true);
        assertThat(r.getDoubleColumn("v").getDouble(0)).isEqualTo(1.0);
        assertThat(r.getDoubleColumn("v").getDouble(1)).isEqualTo(3.0);
        assertThat(Double.isNaN(r.getDoubleColumn("v").getDouble(2))).isTrue();  // NaN 最后
    }

    @Test
    void nlargest和nsmallest() {
        DataFrame df = sample();
        DataFrame top = df.nlargest(2, "score");
        assertThat(top.rowCount()).isEqualTo(2);
        assertThat(top.getStringColumn("name").get(0)).isEqualTo("alice");  // 90 最高

        DataFrame bot = df.nsmallest(2, "score");
        assertThat(bot.getStringColumn("name").get(0)).isEqualTo("carol");  // 80 最低
    }

    @Test
    void groupBy_单列单聚合() {
        DataFrame df = deptSample();
        DataFrame r = df.groupBy("dept").agg("salary", "mean");
        assertThat(r.rowCount()).isEqualTo(2);  // RD, PM
        // RD: (10000+12000)/2 = 11000;PM: 8000
        Map<String, Double> byDept = new java.util.HashMap<>();
        for (Object[] row : r.iterRows()) {
            byDept.put((String) row[0], (Double) row[1]);
        }
        assertThat(byDept.get("RD")).isCloseTo(11000.0, within(1e-10));
        assertThat(byDept.get("PM")).isEqualTo(8000.0);
    }

    @Test
    void groupBy_多列多聚合() {
        DataFrame df = deptSample();
        // 用 LinkedHashMap 保证列序(Map.of 不保证顺序)
        java.util.Map<String, String> spec = new java.util.LinkedHashMap<>();
        spec.put("salary", "sum");
        spec.put("name", "count");
        DataFrame r = df.groupBy("dept").agg(spec);
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.columnNames()).containsExactly("dept", "salary_sum", "name_count");
        // RD salary_sum = 22000,name_count = 2
        for (Object[] row : r.iterRows()) {
            if ("RD".equals(row[0])) {
                assertThat(((Number) row[1]).doubleValue()).isEqualTo(22000.0);  // salary_sum
                assertThat(((Number) row[2]).longValue()).isEqualTo(2L);          // name_count
            }
        }
    }

    @Test
    void groupBy_size() {
        DataFrame df = deptSample();
        DataFrame r = df.groupBy("dept").size();
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void groupBy_null键归NA组() {
        DataFrame df = DataFrame.of(
                Schema.of("dept", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{
                        {"A", 1.0}, {null, 5.0}, {"A", 3.0}});
        DataFrame r = df.groupBy("dept").agg("v", "mean");
        assertThat(r.rowCount()).isEqualTo(2);  // A 组 + null 组
        // 对齐 pandas(断言聚合值本身,不只查 rowCount —— 那会放过"分组对、聚合错";
        // 数据特意让两组均值不同以提升区分度):
        //   A 组 v=[1.0,3.0]→mean=2.0(skipna,跳 null);null 组 v=[5.0]→mean=5.0
        assertThat(r.getDoubleColumn("v_mean").data()).containsExactlyInAnyOrder(2.0, 5.0);
    }

    // === GroupBy.aggregate 各聚合函数的 NaN/空边界 ===
    // 因为每个聚合的 if(!isNull) + any/n==0/isEmpty 边界容易被漏测,
    // 所以下面两个测试逐函数覆盖,对齐 pandas skipna=True。

    /** 单组辅助:返回该组 fn 聚合的数值结果(double 列)。 */
    private double aggNum(DataFrame df, String fn) {
        return df.groupBy("g").agg("v", fn).getDoubleColumn("v_" + fn).getDouble(0);
    }
    /** 单组辅助:返回该组 fn 聚合的整数结果(count/nunique 走 Long 列)。 */
    private long aggLong(DataFrame df, String fn) {
        return df.groupBy("g").agg("v", fn).getLongColumn("v_" + fn).getLong(0);
    }

    @Test
    void groupBy_含NaN组各聚合skipna对齐pandas() {
        // A 组 v=[1.0, NaN, 3.0]:每个聚合都应跳过 NaN(skipna=True,对齐 pandas)
        DataFrame df = DataFrame.of(
                Schema.of("g", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"A", 1.0}, {"A", null}, {"A", 3.0}});
        assertThat(aggLong(df, "count")).isEqualTo(2L);        // 跳 NaN 计数
        assertThat(aggLong(df, "nunique")).isEqualTo(2L);      // 跳 NaN 去重 {1.0, 3.0}
        assertThat(aggNum(df, "sum")).isEqualTo(4.0);          // 1 + 3
        assertThat(aggNum(df, "mean")).isEqualTo(2.0);         // 4 / 2
        assertThat(aggNum(df, "min")).isEqualTo(1.0);
        assertThat(aggNum(df, "max")).isEqualTo(3.0);
        assertThat(aggNum(df, "median")).isEqualTo(2.0);       // [1,3] 中位数
        // var/std of [1,3]:var = ((1-2)²+(3-2)²)/(2-1) = 2.0;std = √2
        assertThat(Math.abs(aggNum(df, "var") - 2.0)).isLessThan(1e-9);
        assertThat(Math.abs(aggNum(df, "std") - Math.sqrt(2))).isLessThan(1e-9);
    }

    @Test
    void groupBy_全空组各聚合返NaN或零对齐pandas() {
        // A 组 v=[NaN, NaN](全空):覆盖 each 聚合的"无有效值"边界
        DataFrame df = DataFrame.of(
                Schema.of("g", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"A", null}, {"A", null}});
        assertThat(aggLong(df, "count")).isEqualTo(0L);        // 全空 → 0
        assertThat(aggLong(df, "nunique")).isEqualTo(0L);      // ∅
        assertThat(aggNum(df, "sum")).isEqualTo(0.0);          // 空和 = 0(累加器初值)
        assertThat(aggNum(df, "mean")).isNaN();                // n==0 → NaN
        assertThat(aggNum(df, "min")).isNaN();                 // any=false → NaN
        assertThat(aggNum(df, "max")).isNaN();
        assertThat(aggNum(df, "median")).isNaN();              // vals.isEmpty → NaN
        assertThat(aggNum(df, "var")).isNaN();
        assertThat(aggNum(df, "std")).isNaN();
    }

    @Test
    void groupBy_filter组级过滤() {
        DataFrame df = deptSample();
        // 保留 count >= 2 的组(只有 RD 满足)
        DataFrame r = df.groupBy("dept").filter("salary", "count", c -> c >= 2);
        assertThat(r.rowCount()).isEqualTo(2);  // RD 组 2 行保留,PM 组被丢
        assertThat(r.getStringColumn("name").data()).containsExactly("alice", "carol");
    }

    @Test
    void 列间加法_派生新列() {
        DataFrame df = DataFrame.of(
                Schema.of("price", DType.DOUBLE, "qty", DType.LONG),
                new Object[][]{{10.0, 2L}, {5.0, 3L}});
        DataFrame r = df.colAdd("total", "price", "qty");
        assertThat(r.columnNames()).contains("total");
        assertThat(r.getDoubleColumn("total").getDouble(0)).isEqualTo(12.0);
        assertThat(r.getDoubleColumn("total").getDouble(1)).isEqualTo(8.0);
    }

    @Test
    void 列间乘法_NaN传播() {
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.DOUBLE, "b", DType.DOUBLE),
                new Object[][]{{2.0, 3.0}, {null, 5.0}});
        DataFrame r = df.colMul("p", "a", "b");
        assertThat(r.getDoubleColumn("p").getDouble(0)).isEqualTo(6.0);
        assertThat(Double.isNaN(r.getDoubleColumn("p").getDouble(1))).isTrue();
    }

    @Test
    void 列乘标量() {
        DataFrame df = sample();
        DataFrame r = df.colMul("double_score", "score", 2.0);
        assertThat(r.getDoubleColumn("double_score").getDouble(0)).isEqualTo(180.0);
    }

    @Test
    void 算术_非数值列抛异常() {
        DataFrame df = sample();
        try {
            df.colAdd("x", "name", "name");
            org.assertj.core.api.Assertions.fail("应抛异常");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("数值列");
        }
    }

    @Test
    void 链式_groupBy后sort() {
        DataFrame df = deptSample();
        DataFrame r = df.groupBy("dept").agg("salary", "mean").sortBy("dept", true);
        assertThat(r.getStringColumn("dept").get(0)).isEqualTo("PM");
        assertThat(r.getStringColumn("dept").get(1)).isEqualTo("RD");
    }

    private DataFrame sample() {
        return DataFrame.of(
                Schema.of("name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{
                        {"alice", 90.0},
                        {"bob", 85.0},
                        {"carol", 80.0}
                });
    }

    private DataFrame deptSample() {
        return DataFrame.of(
                Schema.of("name", DType.STRING, "dept", DType.STRING, "salary", DType.DOUBLE),
                new Object[][]{
                        {"alice", "RD", 10000.0},
                        {"bob", "PM", 8000.0},
                        {"carol", "RD", 12000.0}
                });
    }
}
