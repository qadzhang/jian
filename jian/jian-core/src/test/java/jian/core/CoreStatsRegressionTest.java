package jian.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : CoreStatsRegressionTest —— 统计与 dtype 语义回归测试集:固化统计算子(cov/corr/分位/累积/
// │         pct_change/round/rank/mode/nunique)、类型转换(astype/insert 推断)、缺失填充(fillna/ffill/
// │         where/mask/interpolate)、类型保真与 ±0.0/NaN 等价语义
// │  Why  : 因为统计与类型语义的每个口径都以 pandas 实测为准(有偏/无偏、skipna、half-even、±0.0 归一),
// │         偏差会静默污染下游聚合,所以用精确断言逐口径固化(有意设计差异显式注明并引用 §10.16)
// │  Who  : jian-core 测试套件(surefire)执行
// │  When : 改动 DataFrameStats / DataFrameArith / DataFrameMissing / DataFrameConvert / Schema 相关行为后必须全绿
// │  Where: jian-core/src/test/java/jian/core/CoreStatsRegressionTest.java
// │  How  : 数据走向:固定小数据 → 算子/转换 → 断言精确值与 dtype(双重断言,值对 + 类型对)。
// │         关键变量:期望值(pandas 1.5.3 实测)、dtype(保真不降级 OBJECT)、NaN(跳过/传播/命中三种语义)。
// │         逻辑路线:正常值 → 精确值;边界(NaN/null/±0.0/大数)→ 语义锁定;非法输入 → 教学 IAE。
class CoreStatsRegressionTest {

    /** 列对构造辅助:df("a", arr, "b", arr) → DataFrame.ofColumns(保插入序)。 */
    private static DataFrame df(Object... colPairs) {
        Map<String, Object[]> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < colPairs.length; i += 2) m.put((String) colPairs[i], (Object[]) colPairs[i + 1]);
        return DataFrame.ofColumns(m);
    }

    // ======================== covMatrix / combineFirst ========================

    @Test
    void covMatrix_两列协方差() {
        DataFrame df = DataFrame.of(
            Schema.of("x", DType.DOUBLE, "y", DType.DOUBLE),
            new Object[][]{{1.0, 2.0}, {2.0, 4.0}, {3.0, 6.0}, {4.0, 8.0}});
        DataFrame m = df.covMatrix();
        assertThat(m.rowCount()).isEqualTo(2);  // 2 个数值列
        assertThat(m.columnCount()).isEqualTo(3);  // _index_ + x + y
        // 自协方差(x,x)= var(x) = 1.667(ddof=1)
        assertThat((double) m.get(0, "x")).isCloseTo(1.6667, within(1e-3));
    }

    @Test
    void combineFirst_用other填空() {
        DataFrame self = DataFrame.of(
            Schema.of("v", DType.OBJECT),
            new Object[][]{{1}, {null}, {3}});
        DataFrame other = DataFrame.of(
            Schema.of("v", DType.OBJECT),
            new Object[][]{{10}, {20}, {30}});
        DataFrame r = self.combineFirst(other);
        // self 第 2 行缺失 → 用 other 的 20 填
        assertThat(r.get(0, "v")).isEqualTo(1);   // 非缺失保留
        assertThat(r.get(1, "v")).isEqualTo(20);  // 缺失用 other
        assertThat(r.get(2, "v")).isEqualTo(3);   // 非缺失保留
    }

    // ======================== 峰度 / pct_change ========================

    @Test
    void colKurt_严格断言_先非NaN再近值() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}});
        double k = df.colKurt("v");
        assertThat(k).isNotNaN();  // 先钉死非 NaN(再验数值,防止弱断言放过 NaN)
        // [1,2,3,4] 无偏 G2 = -1.2(无偏口径,对齐 pandas)
        assertThat(k).isCloseTo(-1.2, within(0.01));
    }

    @Test
    void pctChange_负数前值符号对齐pandas() {
        // pandas 例:prev=-1, cur=3 → (3-(-1))/(-1) = -4(分母带符号,不做 |prev|)
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{-1.0}, {3.0}});
        DoubleColumn p = df.colPctChange("v", 1, "p");
        assertThat(p.getDouble(1)).isCloseTo(-4.0, within(1e-9));
    }

    @Test
    void pctChange_前值为零返NaN() {
        // 设计差异声明(doc/00-overview.md §10):pandas 返 ±inf,jian 返 NaN 不污染后续聚合
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{0.0}, {5.0}});
        DoubleColumn p = df.colPctChange("v", 1, "p");
        assertThat(p.getDouble(1)).isNaN();
    }

    // ======================== fillna / ffill / where / mask / interpolate(缺失与 dtype 保真)========================

    @Test
    void fillna_DATETIME保留dtype() {
        DataFrame df = DataFrame.of(Schema.of("ts", DType.DATETIME),
            new Object[][]{{null}, {LocalDateTime.of(2026, 1, 2, 0, 0)}});
        DataFrame r = df.fillna(LocalDateTime.of(2026, 1, 1, 0, 0));
        // 填充值按原列 dtype 落列,保留 DATETIME 不降级 OBJECT
        assertThat(r.dtypes().get(0)).isEqualTo(DType.DATETIME);
        assertThat(r.get(0, 0)).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    void fillna_DATE保留dtype() {
        DataFrame df = DataFrame.of(Schema.of("d", DType.DATE),
            new Object[][]{{null}, {LocalDate.of(2026, 2, 1)}});
        DataFrame r = df.fillna(LocalDate.of(2026, 1, 1));
        assertThat(r.dtypes().get(0)).isEqualTo(DType.DATE);
        assertThat(r.get(0, 0)).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void fillna_类型不匹配抛IAE() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.INT),
            new Object[][]{{null}, {5}});
        // 值类型与列 dtype 不符时抛明确 IAE(不静默填 0)
        assertThatThrownBy(() -> df.fillna("hello"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不匹配");
    }

    @Test
    void fillna按列字典() {
        DataFrame d = df("a", new Object[]{null, 1.0}, "b", new Object[]{2.0, null});
        DataFrame r = d.fillna(Map.of("a", 100.0, "b", 200.0));
        assertThat(r.get(0, 0)).isEqualTo(100.0);
        assertThat(r.get(0, 1)).isEqualTo(2.0);
        assertThat(r.get(1, 0)).isEqualTo(1.0);
        assertThat(r.get(1, 1)).isEqualTo(200.0);
        // 未知列名快速失败
        assertThatThrownBy(() -> d.fillna(Map.of("不存在", 1.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void ffill各dtype保真_不降级OBJECT() {
        // 因为 ffill 后 getLongColumn 等类型化读取必须可用(降级 OBJECT 会破坏下游),
        // 所以各 dtype 填充后必须保真
        DataFrame fl = DataFrame.of(Schema.of("id", DType.LONG), new Object[][]{{1L}, {null}, {3L}});
        DataFrame fr = fl.ffill();
        assertThat(fr.getColumn("id").dtype()).as("ffill LONG 保真").isEqualTo(DType.LONG);
        assertThat(fr.getLongColumn("id").getLong(2)).as("ffill 后类型化读取可用").isEqualTo(3L);
        assertThat(fl.bfill().getColumn("id").dtype()).as("bfill LONG 保真").isEqualTo(DType.LONG);
        assertThat(fl.pad().getColumn("id").dtype()).as("pad 别名保真").isEqualTo(DType.LONG);
        // INT / BOOL / DATE / DATETIME / CATEGORY(pandas:原 dtype 全保留)
        assertThat(DataFrame.of(Schema.of("v", DType.INT), new Object[][]{{1}, {null}, {3}})
                .ffill().getColumn("v").dtype()).isEqualTo(DType.INT);
        assertThat(DataFrame.of(Schema.of("v", DType.BOOL), new Object[][]{{true}, {null}})
                .ffill().getColumn("v").dtype()).isEqualTo(DType.BOOL);
        assertThat(DataFrame.of(Schema.of("v", DType.DATE), new Object[][]{{LocalDate.of(2026, 1, 1)}, {null}})
                .ffill().getColumn("v").dtype()).isEqualTo(DType.DATE);
        assertThat(DataFrame.of(Schema.of("v", DType.DATETIME), new Object[][]{{LocalDateTime.of(2026, 1, 1, 1, 0)}, {null}})
                .ffill().getColumn("v").dtype()).isEqualTo(DType.DATETIME);
        assertThat(DataFrame.of(Schema.of("v", DType.CATEGORY), new Object[][]{{"x"}, {null}})
                .ffill().getColumn("v").dtype()).isEqualTo(DType.CATEGORY);
        // 无缺失列同样不降级
        assertThat(DataFrame.of(Schema.of("id", DType.LONG), new Object[][]{{1L}, {2L}})
                .ffill().getColumn("id").dtype()).as("无缺失 LONG 不降级").isEqualTo(DType.LONG);
        // 值语义抽查:ffill 填充值正确
        assertThat(fr.getColumn("id").get(1)).isEqualTo(1L);
    }

    @Test
    void where与mask日期类别列保真() {
        DataFrame d = DataFrame.of(Schema.of("d", DType.DATE),
                new Object[][]{{LocalDate.of(2026, 1, 1)}, {LocalDate.of(2026, 1, 2)}});
        assertThat(d.where(new boolean[]{true, false}, null).getColumn("d").dtype())
                .as("where DATE 保真").isEqualTo(DType.DATE);
        DataFrame t = DataFrame.of(Schema.of("t", DType.DATETIME),
                new Object[][]{{LocalDateTime.of(2026, 1, 1, 1, 0)}, {LocalDateTime.of(2026, 1, 2, 1, 0)}});
        assertThat(t.mask(new boolean[]{false, true}, null).getColumn("t").dtype())
                .as("mask DATETIME 保真").isEqualTo(DType.DATETIME);
        DataFrame c = DataFrame.of(Schema.of("c", DType.CATEGORY), new Object[][]{{"x"}, {"y"}});
        assertThat(c.mask(new boolean[]{false, true}, null).getColumn("c").dtype())
                .as("mask CATEGORY 保真").isEqualTo(DType.CATEGORY);
        // 类型不符的 other 优雅降级 OBJECT(不抛 CCE)
        assertThat(d.where(new boolean[]{true, false}, "2020-01-01").getColumn("d").dtype())
                .as("DATE 列填字符串 other → 回退 OBJECT(不抛异常)").isEqualTo(DType.OBJECT);
    }

    /** LONG/INT 列无缺失时原列直通(不转 DOUBLE);有缺失时保持转 DOUBLE 插值(pandas 同)。 */
    @Test
    void interpolate无缺失整型列直通() {
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.LONG, "v", DType.DOUBLE),
                new Object[][]{{1L, 1.0}, {2L, null}, {3L, 3.0}});
        DataFrame r = df.interpolate();
        assertThat(r.getColumn("a").dtype()).as("无缺失的 LONG 列直通(不无条件转 DOUBLE)")
                .isEqualTo(DType.LONG);
        assertThat(r.getDoubleColumn("v").getDouble(1)).isEqualTo(2.0);   // v 插值

        // 有缺失的 LONG 列:转 DOUBLE 插值(pandas 同)
        DataFrame df2 = DataFrame.of(Schema.of("a", DType.LONG),
                new Object[][]{{1L}, {null}, {3L}});
        DataFrame r2 = df2.interpolate();
        assertThat(r2.getColumn("a").dtype()).isEqualTo(DType.DOUBLE);
        assertThat(r2.getDoubleColumn("a").getDouble(1)).isEqualTo(2.0);
    }

    // ======================== isin(跨类型 / ±0.0 / NaN)========================

    @Test
    void isin_跨类型_Long列_Double值_数值比较生效() {
        // LONG 列 + Double 值列表 —— 走数值比较(doubleValue()==)
        DataFrame df = DataFrame.of(Schema.of("k", DType.LONG),
            new Object[][]{{1L}, {2L}, {3L}});
        boolean[] mask = df.isin(1.0, 2.0);  // Double 值
        // doubleValue 比较:Long 1 == Double 1.0 → true
        assertThat(mask[0]).isTrue();   // 1L == 1.0
        assertThat(mask[1]).isTrue();   // 2L == 2.0
        assertThat(mask[2]).isFalse();  // 3L ≠ 1.0/2.0
    }

    @Test
    void isin_跨类型_Double列_Long值_数值比较生效() {
        DataFrame df = DataFrame.of(Schema.of("k", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}});
        boolean[] mask = df.isin(1L, 2L);  // Long 值
        assertThat(mask[0]).isTrue();
        assertThat(mask[1]).isTrue();
        assertThat(mask[2]).isFalse();
    }

    @Test
    void isin_零值边界_正零与负零等价() {
        DataFrame df = DataFrame.of(Schema.of("k", DType.DOUBLE),
            new Object[][]{{0.0}, {-0.0}});
        boolean[] mask = df.isin(0.0);  // +0.0 vs -0.0
        assertThat(mask[0]).isTrue();
        assertThat(mask[1]).isTrue();  // -0.0 == +0.0(数值比较)
    }

    @Test
    void isin含NaN值时NaN行命中() {
        // pandas:Series([1.0,nan]).isin([nan]) → [False, True];isin([nan,3]) 对 nan 行 True
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {Double.NaN}, {3.0}});
        assertThat(df.colIsin("v", new Object[]{Double.NaN}))
                .as("colIsin([NaN]):NaN 行命中").containsExactly(false, true, false);
        assertThat(df.colIsin("v", new Object[]{Double.NaN, 3.0}))
                .as("colIsin([NaN,3])").containsExactly(false, true, true);
        assertThat(df.isin(new Object[]{Double.NaN, 3.0}))
                .as("行级 isin([NaN,3]) 同语义").containsExactly(false, true, true);
        // NaN 不命中任何其它值(pandas 一致)
        assertThat(df.colIsin("v", new Object[]{1.0}))
                .as("普通值不命中 NaN 行").containsExactly(true, false, false);
    }

    // ======================== 累积类(cummax/cummin/cumprod)与 GroupBy 多聚合等价 ========================

    @Test
    void colCummax_精确值验证() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{3.0}, {1.0}, {5.0}, {2.0}, {Double.NaN}, {1.0}});
        DoubleColumn r = df.colCummax("v", "cm");
        assertThat(r.getDouble(0)).isEqualTo(3.0);
        assertThat(r.getDouble(1)).isEqualTo(3.0);  // max(3,1)=3
        assertThat(r.getDouble(2)).isEqualTo(5.0);  // max(3,5)=5
        assertThat(r.getDouble(3)).isEqualTo(5.0);  // max(5,2)=5
        assertThat(Double.isNaN(r.getDouble(4))).isTrue();  // NaN 保持
        assertThat(r.getDouble(5)).isEqualTo(5.0);  // NaN 后继续 max(last_valid=5, 1)=5
    }

    @Test
    void colCummin_精确值验证() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{3.0}, {1.0}, {5.0}});
        DoubleColumn r = df.colCummin("v", "cm");
        assertThat(r.getDouble(0)).isEqualTo(3.0);
        assertThat(r.getDouble(1)).isEqualTo(1.0);
        assertThat(r.getDouble(2)).isEqualTo(1.0);  // min(1,5)=1
    }

    @Test
    void colCumprod_精确值验证() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{1.0}, {2.0}, {3.0}, {4.0}});
        DoubleColumn r = df.colCumprod("v", "cp");
        assertThat(r.getDouble(0)).isEqualTo(1.0);
        assertThat(r.getDouble(1)).isEqualTo(2.0);
        assertThat(r.getDouble(2)).isEqualTo(6.0);
        assertThat(r.getDouble(3)).isEqualTo(24.0);
    }

    @Test
    void groupBy_fastPath与genericPath_多聚合等价() {
        // LONG key(走 fast path)
        DataFrame longDf = DataFrame.of(
            Schema.of("k", DType.LONG, "v", DType.DOUBLE),
            new Object[][]{{1L, 10.0}, {1L, 20.0}, {2L, 30.0}, {2L, 40.0}});
        // STRING key(走 generic path)
        DataFrame strDf = DataFrame.of(
            Schema.of("k", DType.STRING, "v", DType.DOUBLE),
            new Object[][]{{"1", 10.0}, {"1", 20.0}, {"2", 30.0}, {"2", 40.0}});

        // count
        double longCount = longDf.groupBy("k").agg("v", "count").getDoubleColumn("v_count").getDouble(0);
        double strCount = strDf.groupBy("k").agg("v", "count").getDoubleColumn("v_count").getDouble(0);
        assertThat(longCount).isEqualTo(strCount);  // 两路径 count 等价

        // mean
        double longMean = longDf.groupBy("k").agg("v", "mean").getDoubleColumn("v_mean").getDouble(0);
        double strMean = strDf.groupBy("k").agg("v", "mean").getDoubleColumn("v_mean").getDouble(0);
        assertThat(longMean).isCloseTo(strMean, within(1e-9));

        // min
        double longMin = longDf.groupBy("k").agg("v", "min").getDoubleColumn("v_min").getDouble(0);
        double strMin = strDf.groupBy("k").agg("v", "min").getDoubleColumn("v_min").getDouble(0);
        assertThat(longMin).isEqualTo(strMin);

        // max
        double longMax = longDf.groupBy("k").agg("v", "max").getDoubleColumn("v_max").getDouble(0);
        double strMax = strDf.groupBy("k").agg("v", "max").getDoubleColumn("v_max").getDouble(0);
        assertThat(longMax).isEqualTo(strMax);
    }

    /** BOOL 列 sum = true 计数(LONG);BOOL/DATE 的 first/last 保留原类型(不 toString 化)。 */
    @Test
    void groupByAgg对BOOL与DATE列类型正确() {
        java.time.LocalDate d1 = java.time.LocalDate.of(2026, 1, 1);
        java.time.LocalDate d2 = java.time.LocalDate.of(2026, 2, 1);
        DataFrame df = DataFrame.of(
                Schema.of("g", DType.STRING, "flag", DType.BOOL, "d", DType.DATE),
                new Object[][]{
                        {"A", true, d1}, {"A", false, d2}, {"A", true, d1},
                        {"B", false, d2}});
        DataFrame sum = df.groupBy("g").agg(Map.of("flag", "sum"));
        // BOOL sum:A 组 [true,false,true] → true 计数 2(pandas 语义,不做字符串拼接)
        assertThat(sum.getColumn("flag_sum").dtype()).isEqualTo(DType.LONG);
        assertThat(sum.getLongColumn("flag_sum").data()).containsExactly(2L, 0L);

        DataFrame first = df.groupBy("g").agg(Map.of("flag", "first", "d", "first"));
        // BOOL first 保留 Boolean(不 toString 化)
        assertThat(first.getColumn("flag_first").dtype()).isEqualTo(DType.BOOL);
        assertThat(first.getColumn("flag_first").get(0)).isEqualTo(Boolean.TRUE);
        // DATE first 保留 LocalDate(不 toString 化)
        assertThat(first.getColumn("d_first").dtype()).isEqualTo(DType.DATE);
        assertThat(first.getColumn("d_first").get(0)).isEqualTo(d1);
        // last 同口径
        DataFrame last = df.groupBy("g").agg(Map.of("d", "last"));
        assertThat(last.getColumn("d_last").dtype()).isEqualTo(DType.DATE);
        assertThat(last.getColumn("d_last").get(0)).isEqualTo(d1);   // A 组非空 last = 第 3 行(d1)
    }

    /** 并列众数取首次出现的值(结果确定,不依赖 HashMap 迭代序)。 */
    @Test
    void mode并列众数取最先出现() {
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{{"b"}, {"a"}, {"a"}, {"b"}});
        // b/a 各 2 次,b 先出现(下标 0)→ mode = "b"(与自身 javadoc"取第一个"一致)
        assertThat(DataFrameArith.mode(df.getColumn("s"))).isEqualTo("b");
    }

    /** pandas 语义:分组字符串列 sum 为拼接(如 'xy')。 */
    @Test
    void groupBy字符串sum拼接() {
        // pandas: pd.DataFrame({'g':['a','a'],'s':['x','y']}).groupby('g').sum() → 'xy'
        DataFrame d = df("g", new Object[]{"a", "a", "b"}, "s", new Object[]{"x", "y", "z"});
        DataFrame r = d.groupBy("g").agg(Map.of("s", "sum"));
        List<String> vals = r.getStringColumn("s_sum") != null
                ? List.of(r.getStringColumn("s_sum").data())
                : List.of();
        assertThat(vals).containsExactlyInAnyOrder("xy", "z");
    }

    // ======================== 相关与分位(corr / cov / quantile / percentile / rank)========================

    /** 并列值取平均秩(对齐 pandas/scipy;min 秩口径对 [1,1,3,2] 会给 0.7746)。 */
    @Test
    void spearman并列值取平均秩() {
        // x=[1,1,3,2], y=[1,2,3,4]:pandas 1.5.3 实测 0.7378647873726218
        DataFrame df = DataFrame.of(Schema.of("x", DType.DOUBLE, "y", DType.DOUBLE),
                new Object[][]{{1.0, 1.0}, {1.0, 2.0}, {3.0, 3.0}, {2.0, 4.0}});
        double rho = DataFrameStats.corr(df.getColumn("x"), df.getColumn("y"), "spearman");
        assertThat(rho).isCloseTo(0.7379, within(1e-3));

        // ties 向量 [1,1,2]/[1,2,3]:平均秩与 min 秩同为 0.866(pandas 实测)
        DataFrame df2 = DataFrame.of(Schema.of("x", DType.DOUBLE, "y", DType.DOUBLE),
                new Object[][]{{1.0, 1.0}, {1.0, 2.0}, {2.0, 3.0}});
        assertThat(DataFrameStats.corr(df2.getColumn("x"), df2.getColumn("y"), "spearman"))
                .isCloseTo(0.8660, within(1e-3));
    }

    /** 含 NaN 跳过语义:上游配对过滤后按剩余对计算。 */
    @Test
    void spearman含NaN跳过语义不变() {
        // [1,1,2,NaN] vs [1,2,3,9] → 配对剩 (1,1),(1,2),(2,3) → 0.866(pandas 实测)
        DataFrame df = DataFrame.of(Schema.of("x", DType.DOUBLE, "y", DType.DOUBLE),
                new Object[][]{{1.0, 1.0}, {1.0, 2.0}, {2.0, 3.0}, {null, 9.0}});
        assertThat(DataFrameStats.corr(df.getColumn("x"), df.getColumn("y"), "spearman"))
                .isCloseTo(0.8660, within(1e-3));
    }

    @Test
    void corr对N1和常量列返回NaN() {
        // pandas 实测:N=1 corr=NaN;全常量列 corr=NaN(相关系数无定义)
        DataFrame n1 = df("a", new Object[]{1.0}, "b", new Object[]{2.0});
        assertThat(n1.colCorr("a", "b")).isNaN();
        DataFrame constCol = df("a", new Object[]{1.0, 2.0, 3.0}, "b", new Object[]{5.0, 5.0, 5.0});
        assertThat(constCol.colCorr("a", "b")).isNaN();
        // 两点相关 pandas 可算(N≥2 即可算)
        DataFrame two = df("a", new Object[]{1.0, 2.0}, "b", new Object[]{3.0, 5.0});
        assertThat(two.colCorr("a", "b")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    /** corr 的 NaN 处理按同下标配对(逐对删除,对齐 pandas)。 */
    @Test
    void corr错位NaN同下标配对() {
        // 各自 skipNaN 后对齐会把 [1,3] vs [1,2] 错位配对算出 1.0;pandas 逐对删除后 NaN(仅 1 对)
        DataFrame d = df("a", new Object[]{1.0, Double.NaN, 3.0}, "b", new Object[]{1.0, 2.0, Double.NaN});
        double r = d.colCorr("a", "b");
        assertThat(r).isNaN();   // 同下标仅 (1,1) 一对 → 无定义
        // 错位不等长场景:逐对删除后可算(不抛"长度不一致")
        DataFrame d2 = df("a", new Object[]{1.0, Double.NaN, 3.0}, "b", new Object[]{1.0, 2.0, 3.0});
        assertThat(d2.colCorr("a", "b")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    /** cov 的 NaN 处理同样按同下标配对(逐对删除)。 */
    @Test
    void cov同下标配对() {
        DataFrame d2 = df("a", new Object[]{1.0, Double.NaN, 3.0}, "b", new Object[]{1.0, 2.0, 3.0});
        // 同下标 (1,1),(3,3):cov((1,3),(1,3)) = 2.0(ddof=1:((−1)(−1)+(1)(1))/1 = 2)
        double c = d2.colCov("a", "b");
        assertThat(c).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    /** corr 对角线走 corr(x,x) 自然计算——常数列 NaN、正常列 1.0(pandas 1.5.3 实测口径);cov 对角线=方差。 */
    @Test
    void corrMatrix对角线对齐pandas_常数列NaN正常列1() {
        DataFrame df = DataFrame.of(
                Schema.of("a", DType.DOUBLE, "b", DType.DOUBLE),
                new Object[][]{{1.0, 1.0}, {1.0, 2.0}, {1.0, 3.0}});
        DataFrame m = df.corrMatrix();
        // 常量列 a 的对角线:零方差 0/0 → NaN(pandas 实测同,非 1.0)
        assertThat((Double) m.get(0, "a")).as("零方差列对角线 NaN(对齐 pandas)").isNaN();
        // 正常列 b 的对角线:corr(b,b) = 1.0
        assertThat((Double) m.get(1, "b")).isEqualTo(1.0);
        // 非对角:常量列与任何列相关无定义 → NaN(pandas 同)
        assertThat(((Double) m.get(0, "b"))).isNaN();
        // cov 对角线 = 方差(不受影响)
        DataFrame cv = df.covMatrix();
        assertThat((Double) cv.get(1, "b")).isEqualTo(1.0);   // b 列样本方差 ddof=1:((−1)²+0+1²)/2=1
    }

    @Test
    void quantile的q为NaN抛IAE() {
        // 因为 NaN 与任何数比较均为 false(q<0||q>1 拦不住 NaN),所以须显式拒绝 NaN 的 q
        DataFrame d = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{1.0}, {2.0}});
        assertThatThrownBy(() -> d.colQuantile("v", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** percentile(col, NaN) 抛 IAE(与 quantile 同口径;NaN 与范围比较均为 false,须显式拒绝)。 */
    @Test
    void percentileNaN的q被拒绝() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {2.0}, {3.0}});
        assertThatThrownBy(() -> df.getSeries("v").percentile(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("percentile q 范围 [0,1]")
                .hasMessageContaining("NaN");
    }

    /** 字符串列 rank 走字典序(对齐 pandas)。 */
    @Test
    void 字符串列rank字典序对齐pandas() {
        // pandas: pd.Series(['a','c','b']).rank() → [1.0, 3.0, 2.0]
        DataFrame d = df("s", new Object[]{"a", "c", "b"});
        jian.core.DoubleColumn r = d.colRank("s", "average", null);
        assertThat(r.data()[0]).isEqualTo(1.0);
        assertThat(r.data()[1]).isEqualTo(3.0);
        assertThat(r.data()[2]).isEqualTo(2.0);
        // ties average
        DataFrame t = df("s", new Object[]{"b", "a", "b"});
        jian.core.DoubleColumn r2 = t.colRank("s", "average", null);
        assertThat(r2.data()).containsExactly(2.5, 1.0, 2.5);
    }

    // ======================== 算子新列名兜底 ========================

    @Test
    void 九算子null新列名兜底() {
        DataFrame d = df("v", new Object[]{1.0, 3.0});
        assertThat(d.colCumsum("v", null).name()).isEqualTo("v_cumsum");
        assertThat(d.colDiff("v", 1, null).name()).isEqualTo("v_diff");
        assertThat(d.colPctChange("v", 1, null).name()).isEqualTo("v_pct_change");
        assertThat(d.colClip("v", 0, 2, null).name()).isEqualTo("v_clip");
        assertThat(d.colRound("v", 1, null).name()).isEqualTo("v_round");
        assertThat(d.colRank("v", "average", null).name()).isEqualTo("v_rank");
        assertThat(d.colCummax("v", null).name()).isEqualTo("v_cummax");
        assertThat(d.colCummin("v", null).name()).isEqualTo("v_cummin");
        assertThat(d.colCumprod("v", null).name()).isEqualTo("v_cumprod");
        // 显式列名优先
        assertThat(d.colCumsum("v", "自定义").name()).isEqualTo("自定义");
    }

    @Test
    void 算术结果列命名不落null() {
        DataFrame d = df("a", new Object[]{1, 2}, "b", new Object[]{3, 4});
        // 公共 API 走 newCol 参数命名;内部 DoubleColumn(null) 有 requireNonNull 防护 + 有意义命名
        DataFrame r1 = d.colAdd("a加b", "a", "b");
        assertThat(r1.columnNames()).containsExactly("a", "b", "a加b");
        DataFrame r2 = d.colMul("a乘2", "a", 2.0);
        assertThat(r2.columnNames()).containsExactly("a", "b", "a乘2");
    }

    // ======================== round(银行家舍入)========================

    @Test
    void round银行家舍入对齐pandas() {
        // 精确 .5 边界:half-even(非 half-up:2.5→2、-3.5→-4)
        assertThat(round1(2.5, 0)).as("2.5 → 2.0(偶)").isEqualTo(2.0);
        assertThat(round1(0.5, 0)).as("0.5 → 0.0(偶)").isEqualTo(0.0);
        assertThat(round1(-3.5, 0)).as("-3.5 → -4.0(偶,向远离零)").isEqualTo(-4.0);
        assertThat(round1(125, -1)).as("125@-1 → 120(12.5 → 12)").isEqualTo(120.0);
        assertThat(round1(0.125, 2)).as("0.125@2 → 0.12(偶)").isEqualTo(0.12);
        // 大数:Math.round 饱和 Long.MAX(9.22e18)彻底错误;rint 返回 double 保真
        assertThat(round1(1e300, 0)).as("1e300 不饱和").isEqualTo(1e300);
        // NaN 保留
        DataFrame nanDf = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{Double.NaN}});
        assertThat(nanDf.colRound("v", 0, null).getDouble(0)).isNaN();
    }

    private static double round1(double v, int decimals) {
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{v}});
        return df.colRound("v", decimals, null).getDouble(0);
    }

    // ======================== ±0.0 数值等价去重(nunique / valueCounts / is_unique 六入口)========================

    @Test
    void 正负零去重等价_六入口对齐pandas() {
        // pandas:nunique([0,-0,1])=2;value_counts → {0.0:2, 1.0:1};is_unique([0,-0])=False
        Column c = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{0.0}, {-0.0}, {1.0}}).getColumn("v");
        assertThat(DataFrameStats.nunique(c)).as("①DataFrameStats.nunique").isEqualTo(2);
        assertThat(DataFrameArith.nunique(c)).as("②DataFrameArith.nunique").isEqualTo(2);
        DataFrame g = DataFrame.of(Schema.of("k", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"a", 0.0}, {"a", -0.0}, {"a", 1.0}});
        assertThat(((Number) g.groupBy("k").agg(java.util.Map.of("v", "nunique")).getColumn("v_nunique").get(0)).longValue())
                .as("③groupBy nunique").isEqualTo(2L);
        DataFrame p = DataFrame.of(Schema.of("i", DType.STRING, "c", DType.STRING, "v", DType.DOUBLE),
                new Object[][]{{"r", "x", 0.0}, {"r", "x", -0.0}});
        // pivotTable 输出列 = index 列 + 各 pivot 键列(此处键 "x"),非 {col}_{fn}
        assertThat(((Number) p.pivotTable("i", "c", "v", "nunique").getColumn("x").get(0)).longValue())
                .as("④pivotTable nunique").isEqualTo(1L);
        assertThat(DataFrameArith.valueCounts(c).keySet())
                .as("⑤valueCounts ±0.0 合并单键").containsExactly(0.0, 1.0);
        assertThat(DataFrameArith.valueCounts(c).get(0.0)).as("合并后计数=2").isEqualTo(2);
        DataFrame two = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{0.0}, {-0.0}});
        assertThat(Series.of(two.getColumn("v")).is_unique())
                .as("⑥is_unique([0,-0])=False").isFalse();
    }

    /** pandas 1.5.3 实测:NaN 视为重复保留一份(duplicated 第二个 NaN = True)。 */
    @Test
    void dropDuplicates对NaN语义与pandas一致() {
        DataFrame d = df("v", new Object[]{Double.NaN, 1.0, Double.NaN, 2.0});
        assertThat(d.dropDuplicates(new String[]{"v"}, "first").rowCount()).isEqualTo(3);
        assertThat(d.duplicated(new String[]{"v"}, "first")).containsExactly(false, false, true, false);
    }

    // ======================== astype / insert 类型转换与推断 ========================

    @Test
    void astype_STRING_to_LONG_非法字符串抛IAE() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.STRING),
            new Object[][]{{"abc"}});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> df.astype("v", DType.LONG))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void astype_STRING_to_LONG_部分非法抛IAE() {
        DataFrame df = DataFrame.of(Schema.of("v", DType.STRING),
            new Object[][]{{"123"}, {"abc"}});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> df.astype("v", DType.LONG))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** 解析失败抛带列名/值的 IAE(对齐 pandas ValueError 带值,不给裸 NumberFormatException)。 */
    @Test
    void astype字符串转数值失败抛教学IAE() {
        DataFrame s = DataFrame.of(Schema.of("v", DType.STRING), new Object[][]{{"abc"}});
        assertThatThrownBy(() -> s.astype("v", DType.LONG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abc")
                .hasMessageContaining("LONG");
    }

    /** 非空非 "true"/"1" 字符串 → false 是有意差异(pandas astype(bool) 非空串恒 True,§10.16 已声明)。 */
    @Test
    void astypeBool有意差异锁定() {
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{{"yes"}, {"false"}, {"true"}, {""}, {"1"}});
        DataFrame r = df.astype("s", DType.BOOL);
        BoolColumn bc = (BoolColumn) r.getColumn("s");
        // jian 口径:仅 "true"/"1"(不区分大小写)为 true;""/"false"/"yes" 全 false
        assertThat(bc.get(0)).as("\"yes\"→false(pandas 为 True,§10.16 声明的有意差异)").isEqualTo(false);
        assertThat(bc.get(1)).as("\"false\"→false").isEqualTo(false);
        assertThat(bc.get(2)).as("\"true\"→true").isEqualTo(true);
        assertThat(bc.get(3)).as("空串→false").isEqualTo(false);
        assertThat(bc.get(4)).as("\"1\"→true").isEqualTo(true);
    }

    /** DOUBLE 字符串解析失败抛带列名/行号/值的 IAE(不给裸 NumberFormatException)。 */
    @Test
    void astypeDouble解析失败抛教学型IAE() {
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{{"abc"}, {"1.5"}});
        assertThatThrownBy(() -> df.astype("s", DType.DOUBLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("astype DOUBLE")
                .hasMessageContaining("'s'")
                .hasMessageContaining("第 0")
                .hasMessageContaining("abc");
        // 合法路径不受影响
        DataFrame ok = DataFrame.of(Schema.of("s", DType.STRING), new Object[][]{{"1.5"}});
        assertThat(ok.astype("s", DType.DOUBLE).getDoubleColumn("s").getDouble(0)).isEqualTo(1.5);
    }

    /** insert 的列推断支持 BOOL/DATE/DATETIME(与 Schema.infer 口径一致,缺失不把类型打回 OBJECT)。 */
    @Test
    void insert推断BOOL与DATETIME与DATE列() {
        DataFrame base = DataFrame.of(Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {2.0}});
        // Boolean[] → BOOL
        DataFrame b = base.insert(0, "flag", new Object[]{true, false});
        assertThat(b.getColumn("flag").dtype()).isEqualTo(DType.BOOL);
        assertThat(b.getColumn("flag").get(0)).isEqualTo(Boolean.TRUE);
        // LocalDateTime[] → DATETIME
        DataFrame dtm = base.insert(0, "ts", new Object[]{
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 2, 0, 0)});
        assertThat(dtm.getColumn("ts").dtype()).isEqualTo(DType.DATETIME);
        // LocalDate[] → DATE
        DataFrame d = base.insert(0, "d", new Object[]{
                java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 1, 2)});
        assertThat(d.getColumn("d").dtype()).isEqualTo(DType.DATE);
        // {true, null} → BOOL + nullMask(缺失不把类型打回 OBJECT)
        DataFrame bn = base.insert(0, "flag", new Object[]{true, null});
        assertThat(bn.getColumn("flag").dtype()).isEqualTo(DType.BOOL);
        assertThat(bn.getColumn("flag").isNull(1)).isTrue();
    }

    // ======================== Schema 推断边界 ========================

    /** 超出 long 范围的整数字符串归 STRING 不崩溃(对齐 pandas read_csv 超 int64 → object)。 */
    @Test
    void 超大整数串归STRING不崩溃() {
        String big = "123456789012345678901234567890";
        DType t = Schema.infer(List.of("x"), new Object[][]{{big}}).dtypeAt(0);
        assertThat(t).isEqualTo(DType.STRING);   // 对齐 pandas read_csv 超 int64 → object
        // 边界内仍数值
        assertThat(Schema.infer(List.of("x"), new Object[][]{{"9223372036854775807"}}).dtypeAt(0).isNumeric()).isTrue();
    }
}
