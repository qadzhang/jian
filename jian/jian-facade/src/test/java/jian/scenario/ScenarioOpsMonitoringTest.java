package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : ScenarioOpsMonitoringTest —— 真实场景测试(日志与监控域,S27~S33)
// │  Why  : 访问日志 TopN/错误风暴/性能分位/指标周报/慢查询/安全日志/SLA 月报是运维侧
// │         七类通用报表需求,须进真实场景集(四轨红线:场景登记 scenarios.md,
// │         完整源码随 jar 分发到 META-INF/ai/scenarios-src/)
// │  Who  : mvn -pl jian-facade test;AI 速查见 jar 内 META-INF/ai/scenarios.md S27~S33 行
// │  When : mvn test(jian-facade 模块);数据为日志解析后的结构化小样本
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioOpsMonitoringTest.java
// │  How  : 数据走向:结构化日志行 → query(条件过滤)→ groupBy(维度).agg(计数/求和/
// │         均值/极值)→ sortBy+head(TopN)/colQuantile(分位数)→ 报表。
// │         每场景一个 @Test,期望值全部可手算(注释里给算式)。
class ScenarioOpsMonitoringTest {

    // S27 访问日志 TopN:哪个客户端 IP 请求最多、耗带宽最大
    @Test
    void S27_访问日志TopN() {
        DataFrame df = DataFrame.of(Schema.of(
                        "IP", DType.STRING, "URI", DType.STRING, "bytes", DType.LONG),
                new Object[][]{
                        {"1.1.1.1", "/a", 100L}, {"1.1.1.1", "/b", 200L}, {"1.1.1.1", "/a", 300L},
                        {"2.2.2.2", "/a", 50L}, {"2.2.2.2", "/b", 70L}, {"3.3.3.3", "/c", 10L}});
        DataFrame top = df.groupBy("IP").agg(java.util.Map.of("URI", "count", "bytes", "sum"))
                .sortBy("URI_count", false).head(2);
        assertThat(top.rowCount()).isEqualTo(2);
        assertThat(top.getColumn("IP").toObjectArray()).containsExactly("1.1.1.1", "2.2.2.2");
        assertThat(((Number) top.getColumn("URI_count").get(0)).longValue()).isEqualTo(3L);
        assertThat(((Number) top.getColumn("bytes_sum").get(0)).longValue())
                .isEqualTo(100 + 200 + 300L);   // 1.1.1.1 带宽 600
        // SQL 对照版:GROUP BY + ORDER BY + LIMIT 一条(与链式 TopN 差分)
        DataFrame sqlTop = Jian.sql("""
                SELECT IP, count(URI) AS cnt, sum(bytes) AS bytes_sum FROM ${t}
                GROUP BY IP ORDER BY cnt DESC LIMIT 2
                """, df);
        assertThat(sqlTop.getColumn("IP").toObjectArray())
                .containsExactlyElementsOf(java.util.Arrays.asList(top.getColumn("IP").toObjectArray()));
        assertThat(((Number) sqlTop.getColumn("cnt").get(0)).longValue()).isEqualTo(3L);
        assertThat(((Number) sqlTop.getColumn("bytes_sum").get(0)).longValue()).isEqualTo(600L);
    }

    // S28 错误风暴监控:发布后集中报错的路径排行
    @Test
    void S28_错误风暴统计() {
        DataFrame df = DataFrame.of(Schema.of(
                        "路径", DType.STRING, "状态", DType.LONG),
                new Object[][]{
                        {"/api/pay", 404L}, {"/api/pay", 500L}, {"/api/user", 200L},
                        {"/api/old", 404L}, {"/api/user", 301L}});
        DataFrame errs = df.query("状态 >= 400")
                .groupBy("路径").agg(java.util.Map.of("状态", "count"))
                .sortBy("状态_count", false);
        assertThat(errs.rowCount()).isEqualTo(2);   // /api/pay 与 /api/old
        assertThat(errs.getColumn("路径").toObjectArray()).containsExactly("/api/pay", "/api/old");
        assertThat(((Number) errs.getColumn("状态_count").get(0)).longValue()).isEqualTo(2L);
        // 3xx(301)不是错误,不允许进风暴榜
        assertThat(errs.getColumn("路径").toObjectArray()).doesNotContain("/api/user");
        // SQL 对照版:WHERE + GROUP BY + ORDER BY 一条(与链式差分)
        DataFrame sqlErr = Jian.sql("""
                SELECT 路径, count(状态) AS cnt FROM ${t} WHERE 状态 >= 400
                GROUP BY 路径 ORDER BY cnt DESC
                """, df);
        assertThat(sqlErr.getColumn("路径").toObjectArray()).containsExactly("/api/pay", "/api/old");
        assertThat(((Number) sqlErr.getColumn("cnt").get(0)).longValue()).isEqualTo(2L);
    }

    // S29 性能分位数与版本对比:发版前后 p95 对比,量化性能回归
    @Test
    void S29_性能分位版本对比() {
        DataFrame df = DataFrame.of(Schema.of("版本", DType.STRING, "耗时", DType.LONG),
                new Object[][]{
                        {"v1", 100L}, {"v1", 200L}, {"v1", 300L}, {"v1", 400L},
                        {"v2", 120L}, {"v2", 220L}, {"v2", 320L}, {"v2", 420L}});
        // p95(线性插值,对齐 pandas/numpy):(n-1)*q = 2.85 → v1: 300+0.85*100 = 385
        double p95v1 = df.query("版本 == 'v1'").colQuantile("耗时", 0.95);
        double p95v2 = df.query("版本 == 'v2'").colQuantile("耗时", 0.95);
        assertThat(p95v1).isCloseTo(385.0, within(1e-9));
        assertThat(p95v2).isCloseTo(405.0, within(1e-9));   // 浮点插值带尾差,容差断言
        assertThat(p95v2 - p95v1).isCloseTo(20.0, within(1e-9));   // 回归 +20ms
        // 均值侧同向:v1=250 / v2=270
        DataFrame mean = df.groupBy("版本").agg(java.util.Map.of("耗时", "mean"));
        for (int r = 0; r < mean.rowCount(); r++) {
            String v = (String) mean.getColumn("版本").get(r);
            double m = ((Number) mean.getColumn("耗时_mean").get(r)).doubleValue();
            assertThat(m).isEqualTo("v1".equals(v) ? 250.0 : 270.0);
        }
        // SQL 对照版:均值/标准差各一条 GROUP BY(同源列多聚合会覆盖,分两条;与链式差分)
        //   手算:v1 std = √(50000/3) ≈ 129.099
        DataFrame sqlMean = Jian.sql("SELECT 版本, mean(耗时) AS m FROM ${t} GROUP BY 版本", df);
        DataFrame sqlStd = Jian.sql("SELECT 版本, std(耗时) AS s FROM ${t} GROUP BY 版本", df);
        for (int r = 0; r < sqlMean.rowCount(); r++) {
            String v = (String) sqlMean.getColumn("版本").get(r);
            assertThat(((Number) sqlMean.getColumn("m").get(r)).doubleValue())
                    .isEqualTo("v1".equals(v) ? 250.0 : 270.0);
            assertThat(((Number) sqlStd.getColumn("s").get(r)).doubleValue())
                    .isCloseTo(129.099, within(1e-2));
        }
    }

    // S30 时序指标周期报表:周会上要的均值/峰值/谷值
    @Test
    void S30_指标周期报表() {
        DataFrame df = DataFrame.of(Schema.of(
                        "指标", DType.STRING, "值", DType.LONG),
                new Object[][]{
                        {"cpu", 40L}, {"cpu", 60L}, {"cpu", 80L},
                        {"mem", 50L}, {"mem", 55L}, {"mem", 60L}});
        DataFrame rpt = df.groupBy("指标").agg(java.util.Map.of("值", "mean"));
        // 极值口径单独聚合(max/min 两次),与均值表并列成周期报表
        DataFrame mx = df.groupBy("指标").agg(java.util.Map.of("值", "max"));
        DataFrame mn = df.groupBy("指标").agg(java.util.Map.of("值", "min"));
        assertThat(lookup(rpt, "指标", "cpu", "值_mean")).isEqualTo(60.0);   // (40+60+80)/3
        assertThat(lookup(rpt, "指标", "mem", "值_mean")).isEqualTo(55.0);
        assertThat(lookup(mx, "指标", "cpu", "值_max")).isEqualTo(80.0);
        assertThat(lookup(mn, "指标", "cpu", "值_min")).isEqualTo(40.0);
        assertThat(lookup(mx, "指标", "mem", "值_max")).isEqualTo(60.0);
        // SQL 对照版:均值/极值各一条 GROUP BY(同源列多聚合会覆盖,分三条;与三次链式聚合差分)
        DataFrame sqlMean = Jian.sql("SELECT 指标, mean(值) AS m FROM ${t} GROUP BY 指标", df);
        DataFrame sqlMax = Jian.sql("SELECT 指标, max(值) AS mx FROM ${t} GROUP BY 指标", df);
        DataFrame sqlMin = Jian.sql("SELECT 指标, min(值) AS mn FROM ${t} GROUP BY 指标", df);
        assertThat(((Number) lookup(sqlMean, "指标", "cpu", "m")).doubleValue()).isEqualTo(60.0);
        assertThat(((Number) lookup(sqlMax, "指标", "cpu", "mx")).doubleValue()).isEqualTo(80.0);
        assertThat(((Number) lookup(sqlMin, "指标", "cpu", "mn")).doubleValue()).isEqualTo(40.0);
        assertThat(((Number) lookup(sqlMean, "指标", "mem", "m")).doubleValue()).isEqualTo(55.0);
    }

    // S31 慢查询排行:按平均耗时定位最慢 SQL 指纹
    @Test
    void S31_慢查询排行() {
        DataFrame df = DataFrame.of(Schema.of(
                        "指纹", DType.STRING, "平均ms", DType.LONG, "次数", DType.LONG),
                new Object[][]{
                        {"Q1", 1200L, 3L}, {"Q2", 850L, 10L}, {"Q3", 430L, 25L}, {"Q4", 910L, 6L}});
        DataFrame top = df.sortBy("平均ms", false).head(3);
        assertThat(top.getColumn("指纹").toObjectArray()).containsExactly("Q1", "Q4", "Q2");
        assertThat(((Number) top.getColumn("平均ms").get(0)).longValue()).isEqualTo(1200L);
        // 快查询 Q3 不进 Top3
        assertThat(top.getColumn("指纹").toObjectArray()).doesNotContain("Q3");
        // SQL 对照版:ORDER BY DESC + LIMIT 一条(与链式 sortBy+head 差分,逐行一致)
        DataFrame sqlSlow = Jian.sql("SELECT 指纹, 平均ms FROM ${t} ORDER BY 平均ms DESC LIMIT 3", df);
        assertThat(sqlSlow.getColumn("指纹").toObjectArray())
                .containsExactlyElementsOf(java.util.Arrays.asList(top.getColumn("指纹").toObjectArray()));
    }

    // S32 安全日志统计:暴力破解来源 IP 排行
    @Test
    void S32_安全日志统计() {
        DataFrame df = DataFrame.of(Schema.of(
                        "IP", DType.STRING, "结果", DType.STRING, "用户", DType.STRING),
                new Object[][]{
                        {"1.1.1.1", "FAILED", "root"}, {"1.1.1.1", "FAILED", "admin"},
                        {"2.2.2.2", "SUCCESS", "deploy"}, {"1.1.1.1", "FAILED", "test"},
                        {"1.1.1.1", "FAILED", "oracle"}});
        DataFrame top = df.query("结果 == 'FAILED'")
                .groupBy("IP").agg(java.util.Map.of("用户", "count"))
                .sortBy("用户_count", false);
        assertThat(top.rowCount()).isEqualTo(1);   // 只有 1.1.1.1 有失败记录
        assertThat(top.getColumn("IP").toObjectArray()).containsExactly("1.1.1.1");
        assertThat(((Number) top.getColumn("用户_count").get(0)).longValue()).isEqualTo(4L);
        // SQL 对照版:WHERE + GROUP BY + ORDER BY 一条(与链式差分)
        DataFrame sqlBad = Jian.sql("""
                SELECT IP, count(用户) AS c FROM ${t} WHERE 结果 = 'FAILED' GROUP BY IP ORDER BY c DESC
                """, df);
        assertThat(sqlBad.getColumn("IP").toObjectArray()).containsExactly("1.1.1.1");
        assertThat(((Number) sqlBad.getColumn("c").get(0)).longValue()).isEqualTo(4L);
    }

    // S33 可用性 SLA 月报:月度可用率与中断次数
    @Test
    void S33_可用性SLA月报() {
        DataFrame df = DataFrame.of(Schema.of(
                        "月", DType.STRING, "状态", DType.STRING),
                new Object[][]{
                        {"1月", "UP"}, {"1月", "UP"}, {"1月", "UP"}, {"1月", "UP"}, {"1月", "DOWN"},
                        {"2月", "UP"}, {"2月", "UP"}, {"2月", "UP"}, {"2月", "UP"}, {"2月", "UP"}});
        // 正常位标记列(1/0),按月聚合 mean 即可用率、count 即采样点数
        DataFrame df2 = df.assign("正常", r -> "UP".equals(df.getColumn("状态").get(r)) ? 1L : 0L);
        DataFrame sla = df2.groupBy("月").agg(java.util.Map.of("正常", "mean", "状态", "count"));
        assertThat(((Number) lookup(sla, "月", "1月", "正常_mean")).doubleValue())
                .isCloseTo(0.8, within(1e-12));                        // 4/5
        assertThat(((Number) lookup(sla, "月", "2月", "正常_mean")).doubleValue())
                .isEqualTo(1.0);                                       // 5/5
        assertThat(((Number) lookup(sla, "月", "1月", "状态_count")).longValue()).isEqualTo(5L);
        assertThat(((Number) lookup(sla, "月", "2月", "状态_count")).longValue()).isEqualTo(5L);
        // SQL 对照版:可用率与采样点一条 GROUP BY 出(与 assign+groupBy 差分)
        DataFrame sqlSla = Jian.sql(
                "SELECT 月, mean(正常) AS uptime, count(*) AS n FROM ${t} GROUP BY 月", df2);
        assertThat(((Number) lookup(sqlSla, "月", "1月", "uptime")).doubleValue())
                .isCloseTo(0.8, within(1e-12));
        assertThat(((Number) lookup(sqlSla, "月", "2月", "uptime")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) lookup(sqlSla, "月", "1月", "n")).longValue()).isEqualTo(5L);
    }

    /** 按键列取目标列的值(小工具,月报/指标报表场景复用)。 */
    private static Object lookup(DataFrame df, String keyCol, String key, String valCol) {
        for (int r = 0; r < df.rowCount(); r++)
            if (df.getColumn(keyCol).get(r).equals(key))
                return df.getColumn(valCol).get(r);
        throw new AssertionError("键不存在: " + key);
    }
}
