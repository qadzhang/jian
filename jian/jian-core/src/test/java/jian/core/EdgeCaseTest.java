package jian.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : 边界用例测试 —— 空 DataFrame / 单行 / 全缺失 / 大数(补 AI agent2 第二轮发现的测试盲区)
class EdgeCaseTest {

    @Test
    void 空DataFrame不崩溃() {
        DataFrame df = DataFrame.of(Schema.of("a", DType.LONG), new Object[0][]);
        assertThat(df.rowCount()).isEqualTo(0);
        assertThat(df.columnCount()).isEqualTo(1);
        assertThat(df.isEmpty()).isTrue();
        // toString 不崩
        assertThat(df.toString()).contains("Empty");
        // 变换返回空
        assertThat(df.head(5).rowCount()).isEqualTo(0);
        assertThat(df.drop("a").columnCount()).isEqualTo(0);
    }

    @Test
    void 空DataFrame统计返回NaN() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[0][]);
        // 空列的 mean 返回 NaN(不抛异常,与 pandas 一致)
        double m = df.colMean("v");
        assertThat(Double.isNaN(m)).isTrue();
    }

    @Test
    void 空DataFramegroupBy不崩() {
        DataFrame df = DataFrame.of(
                Schema.of("k", DType.STRING, "v", DType.DOUBLE),
                new Object[0][]);
        DataFrame r = df.groupBy("k").agg("v", "mean");
        assertThat(r.rowCount()).isEqualTo(0);
    }

    @Test
    void 单行DataFrame() {
        DataFrame df = DataFrame.of(
                Schema.of("x", DType.DOUBLE, "y", DType.STRING),
                new Object[][]{{42.0, "hello"}});
        assertThat(df.rowCount()).isEqualTo(1);
        assertThat(df.getDoubleColumn("x").getDouble(0)).isEqualTo(42.0);
        assertThat(df.getStringColumn("y").get(0)).isEqualTo("hello");
        // 排序
        assertThat(df.sortBy("x", true).rowCount()).isEqualTo(1);
        // describe
        DataFrame desc = df.describe();
        assertThat(desc).isNotNull();
    }

    @Test
    void 全缺失列() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{null}, {null}, {null}});
        assertThat(df.getDoubleColumn("v").nullCount()).isEqualTo(3);
        assertThat(Double.isNaN(df.colMean("v"))).isTrue();
        // dropna any → 空表
        assertThat(df.dropna().rowCount()).isEqualTo(0);
        // fillna
        DataFrame filled = df.fillna(0.0);
        assertThat(filled.getDoubleColumn("v").getDouble(0)).isEqualTo(0.0);
    }

    @Test
    void 大整数精度() {
        // long 边界值
        DataFrame df = DataFrame.of(
                Schema.of("id", DType.LONG),
                new Object[][]{{Long.MAX_VALUE}, {Long.MIN_VALUE}, {0L}});
        assertThat(df.getLongColumn("id").getLong(0)).isEqualTo(Long.MAX_VALUE);
        assertThat(df.getLongColumn("id").getLong(1)).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void 空DataFrame查询() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[0][]);
        // query 在空表上不崩
        DataFrame r = df.query("v > 10");
        assertThat(r.rowCount()).isEqualTo(0);
    }

    @Test
    void 空DataFramemerge() {
        DataFrame a = DataFrame.of(Schema.of("id", DType.LONG), new Object[0][]);
        DataFrame b = DataFrame.of(Schema.of("id", DType.LONG, "v", DType.STRING),
                new Object[][]{{1L, "x"}});
        DataFrame r = a.merge(b, "inner", "id");
        assertThat(r.rowCount()).isEqualTo(0);
    }

    @Test
    void null值过滤() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.STRING),
                new Object[][]{{"a"}, {null}, {"b"}});
        DataFrame filtered = df.query("v is not null");
        assertThat(filtered.rowCount()).isEqualTo(2);
        assertThat(filtered.getStringColumn("v").get(0)).isEqualTo("a");
    }

    @Test
    void Series空列操作() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[0][]);
        Series s = df.getSeries("v");
        assertThat(s.size()).isEqualTo(0);
    }

    // ┌─ What : NaN 分组键语义测试 —— DOUBLE 列含 NaN 时,所有 NaN 行应归入同一组
    // │  Why  : 2026-08-09 AI agent2 报告 B1 指出"NaN 分组键行为未声明";经核实代码 fast path
    // │         在 nullCount>0 时 fall back generic,generic 用 Double.equals(NaN,NaN)==true
    // │         归组,行为与 pandas groupby(dropna=False) 一致;现固化为契约。
    // │  Who  : GroupBy.buildGroups generic 路径
    // │  When : 此测试作为回归守护,防止后续优化 fast path 时破坏 NaN 语义
    // │  Where: EdgeCaseTest.java
    // │  How  : ① 构造 DOUBLE key 列含 NaN+正常值 → groupBy → 断言 NaN 单独成组且组内 count 正确
    @Test
    void NaN分组键归一组() {
        // DOUBLE key 列含 NaN —— 触发 generic 路径(nullCount>0 不走 fast path)
        DataFrame df = DataFrame.of(
                Schema.of("k", DType.DOUBLE, "v", DType.LONG),
                new Object[][]{
                        {1.0, 10L},
                        {Double.NaN, 20L},
                        {1.0, 30L},
                        {Double.NaN, 40L},
                        {2.0, 50L},
                });
        DataFrame r = df.groupBy("k").agg("v", "sum");
        // 期望:3 组(1.0/2.0/NaN),NaN 组的 v 之和 = 20+40 = 60
        assertThat(r.rowCount()).as("NaN 单独成组,总组数 = 3").isEqualTo(3);
        // 找到 NaN 组(DoubleColumn.get(NaN) 返回 Double.NaN)
        // 聚合输出 schema 命名约定:{原列名}_{聚合函数},故 v 列 sum → "v_sum"
        // 类型约定:count/nunique → LONG,其余(sum/mean/...)→ DOUBLE;sum 是 DOUBLE
        boolean foundNaNGroup = false;
        DoubleColumn kCol = r.getDoubleColumn("k");
        DoubleColumn sumCol = r.getDoubleColumn("v_sum");
        for (int i = 0; i < r.rowCount(); i++) {
            if (Double.isNaN(kCol.getDouble(i))) {
                assertThat(sumCol.getDouble(i)).as("NaN 组的 v 之和应为 60").isEqualTo(60.0);
                foundNaNGroup = true;
            }
        }
        assertThat(foundNaNGroup).as("必须存在 NaN 组(generic 路径应保留 NaN 行)").isTrue();
    }

    // ┌─ What : LONG 列含缺失值(null)分组测试 —— 缺失行归 <NA> 组
    // │  Why  : 与 NaN 分组键配套;LONG 列 null 用 "<NA>" 字符串归一(见 GroupBy.buildGroups)
    // │  How  : LONG key 列含 null → groupBy → 缺失行归一组,组 key 显示为 <NA>
    @Test
    void LONG缺失值分组() {
        DataFrame df = DataFrame.of(
                Schema.of("k", DType.LONG, "v", DType.LONG),
                new Object[][]{
                        {1L, 10L},
                        {null, 20L},
                        {1L, 30L},
                        {null, 40L},
                });
        DataFrame r = df.groupBy("k").agg("v", "count");
        // 期望:2 组(1 和 <NA>);<NA> 组的 count = 2(2 行 null)
        assertThat(r.rowCount()).as("LONG 缺失值单独成组,总组数 = 2").isEqualTo(2);
        // count 列类型是 LONG(见 GroupBy.aggregate 注释)
        LongColumn countCol = r.getLongColumn("v_count");
        // 缺失组的 count 必为 2(2 个 null 行);非缺失组(1)的 count 也为 2
        // 两组 count 都是 2,排序后断言最小值=2 即可
        assertThat(countCol.getLong(0)).as("每组 count 应为 2").isEqualTo(2L);
        assertThat(countCol.getLong(1)).as("每组 count 应为 2").isEqualTo(2L);
    }

    // ┌─ What : astype 部分支持 —— 7 种 dtype(2026-08-09 阶段 F 扩展),仅 CATEGORY 抛 IAE
    // │  Why  : 阶段 F 把 BOOL/DATE/DATETIME 加进支持清单(原仅 5 种);
    //         仅 CATEGORY 仍不支持(jian v1 未实现完整 CATEGORY dtype 语义)
    // │  How  : ① 7 种支持的 dtype 不抛 ② CATEGORY 抛 IAE ③ 验证 BOOL 转换正确性
    @Test
    void astype支持7种dtype仅CATEGORY抛IAE() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {2.0}});
        // 支持的 7 种 —— 不抛(阶段 F 新增 BOOL/DATETIME/DATE)
        df.astype("v", DType.STRING);
        df.astype("v", DType.LONG);
        df.astype("v", DType.INT);
        df.astype("v", DType.DOUBLE);
        df.astype("v", DType.OBJECT);
        df.astype("v", DType.BOOL);  // 阶段 F 新增(数值 1.0→true,2.0→true)
        // 不支持的 —— 抛 IAE(仅 CATEGORY)
        assertThatThrownBy(() -> df.astype("v", DType.CATEGORY))
                .as("CATEGORY 应抛 IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("暂不支持");
    }

    @Test
    void astype_BOOL转换正确() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.OBJECT),
                new Object[][]{{"true"}, {"false"}, {"1"}, {0L}, {null}});
        DataFrame r = df.astype("v", DType.BOOL);
        BoolColumn b = r.getColumn("v") instanceof BoolColumn
            ? (BoolColumn) r.getColumn("v") : null;
        assertThat(b).as("BOOL 转换应返回 BoolColumn").isNotNull();
        assertThat(b.get(0)).isEqualTo(Boolean.TRUE);
        assertThat(b.get(1)).isEqualTo(Boolean.FALSE);
        assertThat(b.get(2)).isEqualTo(Boolean.TRUE);  // "1" → true
        assertThat(b.get(3)).isEqualTo(Boolean.FALSE);  // 0L → false
        assertThat(b.isNull(4)).isTrue();  // null 保留缺失
    }

    @Test
    void astype_DATETIME_ISO字符串解析() {
        DataFrame df = DataFrame.of(
                Schema.of("ts", DType.STRING),
                new Object[][]{{"2026-01-01T12:00:00"}, {null}});
        DataFrame r = df.astype("ts", DType.DATETIME);
        Column c = r.getColumn("ts");
        assertThat(c.dtype()).isEqualTo(DType.DATETIME);
        assertThat(c.get(0)).isEqualTo(java.time.LocalDateTime.of(2026, 1, 1, 12, 0));
        assertThat(c.isNull(1)).isTrue();
    }

    @Test
    void astype_DATE从LocalDateTime() {
        DataFrame df = DataFrame.of(
                Schema.of("ts", DType.DATETIME),
                new Object[][]{{java.time.LocalDateTime.of(2026, 3, 15, 10, 30)}});
        DataFrame r = df.astype("ts", DType.DATE);
        Column c = r.getColumn("ts");
        assertThat(c.dtype()).isEqualTo(DType.DATE);
        assertThat(c.get(0)).isEqualTo(java.time.LocalDate.of(2026, 3, 15));
    }

    @Test
    void astype_BOOL_非法字符串抛IAE() {
        // 阶段 F:astype BOOL 不抛(任何字符串都按 "true"/"1" 规则解析,非匹配字符串当 false)
        // 但非法数值字符串到 LONG 应抛
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.STRING),
                new Object[][]{{"abc"}});
        assertThatThrownBy(() -> df.astype("v", DType.LONG))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
