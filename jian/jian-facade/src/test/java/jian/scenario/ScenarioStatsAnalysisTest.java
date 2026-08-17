package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DataFrameStats;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : ScenarioStatsAnalysisTest —— 真实场景测试(统计分析域,S38~S44)
// │  Why  : 分组对比/相关性/透视排名/留存队列/漏斗/增长趋势/嵌套拍平是分析与运营侧
// │         七类通用统计形态,须进真实场景集(四轨红线:场景登记 scenarios.md,
// │         完整源码随 jar 分发到 META-INF/ai/scenarios-src/)
// │  Who  : mvn -pl jian-facade test;AI 速查见 jar 内 META-INF/ai/scenarios.md S38~S44 行
// │  When : mvn test(jian-facade 模块);纯内存数据,无外部依赖
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioStatsAnalysisTest.java
// │  How  : 数据走向:小样本 → groupBy.agg(分组统计)/colCorr(相关)/pivotTable(透视)/
// │         assign(行级派生:留存率/转化率/环比)/explode(嵌套拆行)→ 逐条断言。
// │         期望值全部可手算(注释里给算式)。
class ScenarioStatsAnalysisTest {

    // S38 分组统计与组间对比:均值±标准差 + 双样本 t 统计量
    @Test
    void S38_分组统计与组间对比() {
        DataFrame df = DataFrame.of(Schema.of("组", DType.STRING, "值", DType.LONG),
                new Object[][]{
                        {"对照", 10L}, {"对照", 12L}, {"对照", 14L},
                        {"处理", 16L}, {"处理", 18L}, {"处理", 20L}});
        DataFrame agg = df.groupBy("组").agg(java.util.Map.of("值", "mean"));
        DataFrame std = df.groupBy("组").agg(java.util.Map.of("值", "std"));
        double mA = ((Number) rowOf(agg, "对照", "值_mean")).doubleValue();   // 12
        double mB = ((Number) rowOf(agg, "处理", "值_mean")).doubleValue();   // 18
        double sA = ((Number) rowOf(std, "对照", "值_std")).doubleValue();    // 样本 std = 2
        double sB = ((Number) rowOf(std, "处理", "值_std")).doubleValue();    // 2
        assertThat(mA).isEqualTo(12.0);
        assertThat(mB).isEqualTo(18.0);
        assertThat(sA).isCloseTo(2.0, within(1e-12));
        assertThat(sB).isCloseTo(2.0, within(1e-12));
        // 双样本 t = (18-12) / √(2²/3 + 2²/3) = 6/1.63299 ≈ 3.6742(组间差异显著)
        double t = (mB - mA) / Math.sqrt(sA * sA / 3 + sB * sB / 3);
        assertThat(t).isCloseTo(3.674235, within(1e-4));
        // SQL 对照版:均值/标准差各一条 GROUP BY(同源列多聚合会覆盖,分两条;与 groupBy.agg 差分)
        DataFrame sqlMean = Jian.sql("SELECT 组, mean(值) AS m FROM ${t} GROUP BY 组", df);
        DataFrame sqlStd = Jian.sql("SELECT 组, std(值) AS s FROM ${t} GROUP BY 组", df);
        assertThat(((Number) rowOf(sqlMean, "对照", "m")).doubleValue()).isEqualTo(12.0);
        assertThat(((Number) rowOf(sqlMean, "处理", "m")).doubleValue()).isEqualTo(18.0);
        assertThat(((Number) rowOf(sqlStd, "对照", "s")).doubleValue()).isCloseTo(2.0, within(1e-12));
        assertThat(((Number) rowOf(sqlStd, "处理", "s")).doubleValue()).isCloseTo(2.0, within(1e-12));
    }

    // S39 指标相关性分析:找与目标最相关的因素
    @Test
    void S39_指标相关性分析() {
        DataFrame df = DataFrame.of(Schema.of(
                        "x", DType.LONG, "y", DType.LONG, "z", DType.LONG),
                new Object[][]{
                        {1L, 2L, 3L}, {2L, 4L, 1L}, {3L, 6L, 4L}, {4L, 8L, 2L}, {5L, 10L, 5L}});
        assertThat(df.colCorr("x", "y")).isCloseTo(1.0, within(1e-9));    // 完全线性
        // r(x,z):cov=5,var(x)=var(z)=10 → 0.5(手算:Σ(x-3)(z-3)=0+2+0-1+4=5)
        assertThat(df.colCorr("x", "z")).isCloseTo(0.5, within(1e-9));
        // 因素排序:y(|1.0|) 强于 z(|0.5|)
        assertThat(Math.abs(df.colCorr("x", "y"))).isGreaterThan(Math.abs(df.colCorr("x", "z")));
        // SQL 对照版:表达式列(xy/xz/xx/yy/zz)+ 全局聚合 sum,手拼 Pearson —— 与 colCorr 差分。
        //   r = (n·Σxy − Σx·Σy) / √((n·Σxx − (Σx)²)·(n·Σyy − (Σy)²));n 直接取行数
        DataFrame prods = Jian.sql("""
                SELECT x * y AS xy, x * z AS xz, x * x AS xx, y * y AS yy, z * z AS zz, x, y, z FROM ${t}
                """, df);
        DataFrame sums = Jian.sql("""
                SELECT sum(xy) AS sxy, sum(xz) AS sxz, sum(xx) AS sxx,
                       sum(yy) AS syy, sum(zz) AS szz, sum(x) AS sx, sum(y) AS sy, sum(z) AS sz FROM ${t}
                """, prods);
        double n = prods.rowCount();
        double sx = ((Number) sums.getColumn("sx").get(0)).doubleValue();
        double sy = ((Number) sums.getColumn("sy").get(0)).doubleValue();
        double sz = ((Number) sums.getColumn("sz").get(0)).doubleValue();
        double rxy = (n * ((Number) sums.getColumn("sxy").get(0)).doubleValue() - sx * sy)
                / Math.sqrt((n * ((Number) sums.getColumn("sxx").get(0)).doubleValue() - sx * sx)
                        * (n * ((Number) sums.getColumn("syy").get(0)).doubleValue() - sy * sy));
        double rxz = (n * ((Number) sums.getColumn("sxz").get(0)).doubleValue() - sx * sz)
                / Math.sqrt((n * ((Number) sums.getColumn("sxx").get(0)).doubleValue() - sx * sx)
                        * (n * ((Number) sums.getColumn("szz").get(0)).doubleValue() - sz * sz));
        assertThat(rxy).isCloseTo(1.0, within(1e-9));
        assertThat(rxz).isCloseTo(0.5, within(1e-9));
    }

    // S40 透视到宽表再对行合计排名:门店×月份 → 月合计 → 名次
    @Test
    void S40_透视与排名() {
        DataFrame df = DataFrame.of(Schema.of(
                        "门店", DType.STRING, "月份", DType.STRING, "销售额", DType.LONG),
                new Object[][]{
                        {"A", "1月", 200L}, {"A", "2月", 300L},
                        {"B", "1月", 250L}, {"B", "2月", 200L}});
        DataFrame wide = df.pivotTable("门店", "月份", "销售额", "sum");
        assertThat(wide.rowCount()).isEqualTo(2);
        // 行级派生月合计:A = 200+300 = 500;B = 250+200 = 450
        DataFrame withTotal = wide.assign("月合计", r ->
                ((Number) wide.getColumn("1月").get(r)).doubleValue()
                        + ((Number) wide.getColumn("2月").get(r)).doubleValue());
        // rank(min 法,升序):450→1,500→2
        Object[] rank = DataFrameStats.rank(withTotal.getColumn("月合计"), "min", "名次")
                .toObjectArray();
        assertThat(((Number) withTotal.getColumn("月合计").get(0)).doubleValue()).isEqualTo(500.0);
        assertThat(((Number) withTotal.getColumn("月合计").get(1)).doubleValue()).isEqualTo(450.0);
        assertThat(rank).containsExactly(2.0, 1.0);
        // SQL 对照版:透视 = 条件列(assign 预置按月拆列)+ GROUP BY 聚合 + 表达式合计列,
        //   与 pivotTable+assign 差分(fn(CASE ...) 聚合暂不支持,条件拆列由 assign 承担)
        DataFrame withInd = df.assign("一月额", r ->
                        "1月".equals(df.getColumn("月份").get(r))
                                ? ((Number) df.getColumn("销售额").get(r)).doubleValue() : 0.0)
                .assign("二月额", r ->
                        "2月".equals(df.getColumn("月份").get(r))
                                ? ((Number) df.getColumn("销售额").get(r)).doubleValue() : 0.0);
        DataFrame sqlPivot = Jian.sql("""
                SELECT 门店, sum(一月额) AS m1, sum(二月额) AS m2
                FROM ${t} GROUP BY 门店 ORDER BY 门店
                """, withInd);
        DataFrame sqlTotal = Jian.sql("SELECT 门店, m1 + m2 AS total FROM ${t}", sqlPivot);
        assertThat(sqlTotal.rowCount()).isEqualTo(2);
        assertThat(((Number) sqlTotal.getColumn("total").get(0)).doubleValue()).isEqualTo(500.0);
        assertThat(((Number) sqlTotal.getColumn("total").get(1)).doubleValue()).isEqualTo(450.0);
    }

    // S41 留存队列分析:按注册月透视后算 D1/D7 留存率
    @Test
    void S41_留存队列分析() {
        DataFrame df = DataFrame.of(Schema.of(
                        "注册月", DType.STRING, "第N日", DType.STRING, "活跃数", DType.LONG),
                new Object[][]{
                        {"一月", "0", 100L}, {"一月", "1", 60L}, {"一月", "7", 30L},
                        {"二月", "0", 50L}, {"二月", "1", 35L}, {"二月", "7", 12L}});
        DataFrame wide = df.pivotTable("注册月", "第N日", "活跃数");
        DataFrame withRate = wide.assign("D1留存", r ->
                        ((Number) wide.getColumn("1").get(r)).doubleValue()
                                / ((Number) wide.getColumn("0").get(r)).doubleValue())
                .assign("D7留存", r ->
                        ((Number) wide.getColumn("7").get(r)).doubleValue()
                                / ((Number) wide.getColumn("0").get(r)).doubleValue());
        // 一月:D1 = 60/100 = 0.6,D7 = 30/100 = 0.3;二月:D1 = 35/50 = 0.7,D7 = 12/50 = 0.24
        double[] d1 = new double[2], d7 = new double[2];
        for (int r = 0; r < 2; r++) {
            d1[r] = ((Number) withRate.getColumn("D1留存").get(r)).doubleValue();
            d7[r] = ((Number) withRate.getColumn("D7留存").get(r)).doubleValue();
        }
        assertThat(d1).containsExactly(0.6, 0.7);
        assertThat(d7[0]).isCloseTo(0.3, within(1e-12));
        assertThat(d7[1]).isCloseTo(0.24, within(1e-12));
        // SQL 对照版:队列透视 = 条件列(assign 按 N 日拆列)+ GROUP BY max,与 pivotTable 版差分
        DataFrame withInd = df.assign("活跃0", r ->
                        "0".equals(df.getColumn("第N日").get(r))
                                ? ((Number) df.getColumn("活跃数").get(r)).doubleValue() : 0.0)
                .assign("活跃1", r ->
                        "1".equals(df.getColumn("第N日").get(r))
                                ? ((Number) df.getColumn("活跃数").get(r)).doubleValue() : 0.0)
                .assign("活跃7", r ->
                        "7".equals(df.getColumn("第N日").get(r))
                                ? ((Number) df.getColumn("活跃数").get(r)).doubleValue() : 0.0);
        DataFrame sqlWide = Jian.sql("""
                SELECT 注册月, max(活跃0) AS d0, max(活跃1) AS d1, max(活跃7) AS d7
                FROM ${t} GROUP BY 注册月 ORDER BY 注册月
                """, withInd);
        DataFrame sqlRate = sqlWide.assign("D1留存", r ->
                ((Number) sqlWide.getColumn("d1").get(r)).doubleValue()
                        / ((Number) sqlWide.getColumn("d0").get(r)).doubleValue());
        double[] sqlD1 = new double[2];
        for (int r = 0; r < 2; r++)
            sqlD1[r] = ((Number) sqlRate.getColumn("D1留存").get(r)).doubleValue();
        assertThat(sqlD1).containsExactly(0.6, 0.7);
    }

    // S42 漏斗转化分析:逐层计算相对上一层的转化率
    @Test
    void S42_漏斗转化分析() {
        DataFrame df = DataFrame.of(Schema.of("环节", DType.STRING, "人数", DType.LONG),
                new Object[][]{
                        {"曝光", 1000L}, {"点击", 300L}, {"加购", 120L}, {"下单", 60L}, {"支付", 36L}});
        // 首环节无上层 → NaN(DOUBLE 列缺失语义,§3.5),边界 toObjectArray 转 null
        DataFrame withRate = df.assign("转化率", r ->
                r == 0 ? Double.NaN : ((Number) df.getColumn("人数").get(r)).doubleValue()
                        / ((Number) df.getColumn("人数").get(r - 1)).doubleValue());
        Object[] rate = withRate.getColumn("转化率").toObjectArray();
        assertThat(rate[0]).isNull();   // NaN 经 IO 边界转 null
        assertThat(((Number) rate[1]).doubleValue()).isCloseTo(0.3, within(1e-12));   // 300/1000
        assertThat(((Number) rate[2]).doubleValue()).isCloseTo(0.4, within(1e-12));   // 120/300
        assertThat(((Number) rate[3]).doubleValue()).isCloseTo(0.5, within(1e-12));   // 60/120
        assertThat(((Number) rate[4]).doubleValue()).isCloseTo(0.6, within(1e-12));   // 36/60
        // 全链路 = 36/1000 = 3.6%
        double overall = ((Number) df.getColumn("人数").get(4)).doubleValue() / 1000;
        assertThat(overall).isCloseTo(0.036, within(1e-12));
        // SQL 对照版:全链路 = 两组条件列(assign 预置)的全局聚合相除,与逐层链式差分
        DataFrame withInd = df.assign("支付额", r ->
                        "支付".equals(df.getColumn("环节").get(r))
                                ? ((Number) df.getColumn("人数").get(r)).doubleValue() : 0.0)
                .assign("曝光额", r ->
                        "曝光".equals(df.getColumn("环节").get(r))
                                ? ((Number) df.getColumn("人数").get(r)).doubleValue() : 0.0);
        DataFrame chain = Jian.sql("""
                SELECT sum(支付额) AS pay, sum(曝光额) AS imp FROM ${t}
                """, withInd);
        double sqlOverall = ((Number) chain.getColumn("pay").get(0)).doubleValue()
                / ((Number) chain.getColumn("imp").get(0)).doubleValue();
        assertThat(sqlOverall).isCloseTo(0.036, within(1e-12));
    }

    // S43 增长趋势追踪:累计曲线 + 逐期环比
    @Test
    void S43_增长趋势追踪() {
        DataFrame df = DataFrame.of(Schema.of("日", DType.LONG, "日增", DType.LONG),
                new Object[][]{{1L, 10L}, {2L, 20L}, {3L, 15L}, {4L, 25L}});
        // 累计:10 → 30 → 45 → 70
        Object[] cum = df.colCumsum("日增", "累计").toObjectArray();
        assertThat(java.util.Arrays.stream(cum).mapToLong(v -> ((Number) v).longValue()).toArray())
                .containsExactly(10, 30, 45, 70);
        // 环比(本日增/前日增):首日缺失(NaN,§3.5),其后 2.0 / 0.75 / 5/3
        DataFrame withRate = df.assign("环比", r ->
                r == 0 ? Double.NaN : ((Number) df.getColumn("日增").get(r)).doubleValue()
                        / ((Number) df.getColumn("日增").get(r - 1)).doubleValue());
        Object[] rate = withRate.getColumn("环比").toObjectArray();
        assertThat(rate[0]).isNull();
        assertThat(((Number) rate[1]).doubleValue()).isEqualTo(2.0);
        assertThat(((Number) rate[2]).doubleValue()).isCloseTo(0.75, within(1e-12));
        assertThat(((Number) rate[3]).doubleValue()).isCloseTo(5.0 / 3, within(1e-12));
        // SQL 对照版:全列总和交给全局聚合,必须等于累计末位(交叉验证)
        DataFrame total = Jian.sql("SELECT sum(日增) AS total FROM ${t}", df);
        assertThat(((Number) total.getColumn("total").get(0)).longValue())
                .isEqualTo(70L);   // 与 colCumsum 末位一致
    }

    // S44 嵌套结构拍平:文章→关键词列表 explode 成行,再聚合计数
    @Test
    void S44_嵌套结构拍平() {
        DataFrame df = DataFrame.of(Schema.of("文章", DType.STRING, "关键词", DType.OBJECT),
                new Object[][]{
                        {"a1", java.util.List.of("AI", "云")},
                        {"a2", java.util.List.of("AI", "Java")},
                        {"a3", java.util.List.of("AI", "云", "Java")}});
        DataFrame flat = df.explode("关键词");
        assertThat(flat.rowCount()).isEqualTo(7);   // 2+2+3 拆行
        DataFrame cnt = flat.groupBy("关键词").agg(java.util.Map.of("文章", "count"));
        // AI 3 次 / 云 2 次 / Java 2 次
        assertThat(rowOf(cnt, "AI", "文章_count")).isEqualTo(3L);
        assertThat(rowOf(cnt, "云", "文章_count")).isEqualTo(2L);
        assertThat(rowOf(cnt, "Java", "文章_count")).isEqualTo(2L);
        // 计数守恒:拆行不丢关键词
        long total = 0;
        for (Object v : cnt.getColumn("文章_count").toObjectArray())
            total += ((Number) v).longValue();
        assertThat(total).isEqualTo(7L);
        // SQL 对照版:拆行后的计数交给 GROUP BY(与 groupBy.agg 差分)
        DataFrame sqlCnt = Jian.sql("SELECT 关键词, count(文章) AS c FROM ${t} GROUP BY 关键词", flat);
        assertThat(rowOf(sqlCnt, "AI", "c")).isEqualTo(3L);
        assertThat(rowOf(sqlCnt, "云", "c")).isEqualTo(2L);
        assertThat(rowOf(sqlCnt, "Java", "c")).isEqualTo(2L);
    }

    /** 按首列键取目标列(小工具,组/关键词场景复用)。 */
    private static Object rowOf(DataFrame df, Object key, String valCol) {
        String keyCol = df.columnNames().get(0);
        for (int r = 0; r < df.rowCount(); r++)
            if (df.getColumn(keyCol).get(r).equals(key))
                return df.getColumn(valCol).get(r);
        throw new AssertionError("键不存在: " + key);
    }
}
