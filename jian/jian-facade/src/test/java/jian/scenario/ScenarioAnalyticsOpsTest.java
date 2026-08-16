package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : ScenarioAnalyticsOpsTest —— 真实业务场景测试(分析/运营域,S4/S5/S6/S7/S15)
// │  Why  : 同 ScenarioSalesFinanceTest(网络调研 + 可手算断言 + 每场景 ≥3 独立断言)
// │  Who  : mvn -pl jian-facade test;场景清单 AI 版见 META-INF/ai/scenarios.md
// │  When : mvn test(jian-facade 模块)
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioAnalyticsOpsTest.java
class ScenarioAnalyticsOpsTest {

    // S4 API 网关日志按小时重采样:每小时请求数 + 平均耗时
    @Test
    void S4_日志按小时重采样() {
        java.time.LocalDateTime t = java.time.LocalDateTime.of(2026, 1, 1, 10, 0);
        DataFrame df = DataFrame.of(Schema.of("时间", DType.DATETIME, "耗时ms", DType.LONG),
                new Object[][]{{t, 100L}, {t.plusMinutes(10), 200L}, {t.plusMinutes(50), 150L},
                        {t.plusHours(1).plusMinutes(5), 300L}, {t.plusHours(1).plusMinutes(30), 100L},
                        {t.plusHours(3), 250L}});
        // Resampler 输出固定 _bucket_ 时间列 + <列>_<fn> 聚合列(空桶 12 点自动补网格)
        DataFrame hourly = df.resample("时间", "1h").mean();
        double meanSum = 0;
        for (Object v : hourly.getColumn("耗时ms_mean").toObjectArray())
            if (v instanceof Number n) meanSum += n.doubleValue();
        // 10 点 mean=(100+200+150)/3=150;11 点=(300+100)/2=200;13 点=250
        assertThat(meanSum).isEqualTo(150 + 200 + 250.0);
        DataFrame cnt = df.resample("时间", "1h").count();
        long total = 0;
        for (Object v : cnt.getColumn("耗时ms_count").toObjectArray())
            if (v instanceof Number n) total += n.longValue();
        assertThat(total).isEqualTo(6L);   // 计数守恒:6 条日志一条不丢
    }

    // S5 RFM 客户分层(R 距今天数预计算列 + groupBy 聚合 + 规则打分)
    @Test
    void S5_RFM分层() {
        DataFrame df = DataFrame.of(Schema.of(
                        "客户", DType.STRING, "距今天数", DType.LONG, "金额", DType.LONG),
                new Object[][]{
                        {"C1", 2L, 200L}, {"C1", 27L, 300L}, {"C2", 42L, 100L}, {"C3", 4L, 500L},
                        {"C4", 214L, 50L}, {"C5", 1L, 400L}, {"C5", 17L, 100L}});
        DataFrame rfm = df.groupBy("客户").agg(Map.of("距今天数", "min", "金额", "sum"));
        // R(最近购买=min 距今天数) F(次数) M(总额):C1=(2,2,500) C2=(42,1,100) C3=(4,1,500) C4=(214,1,50) C5=(1,2,500)
        Map<String, long[]> m = new LinkedHashMap<>();
        for (int r = 0; r < rfm.rowCount(); r++) {
            String c = (String) rfm.getColumn("客户").get(r);
            long rec = ((Number) rfm.getColumn("距今天数_min").get(r)).longValue();
            long mon = ((Number) rfm.getColumn("金额_sum").get(r)).longValue();
            long freq = Jian.query(df, "客户 == ${c}", jian.dsl.Params.of("c", c)).rowCount();
            m.put(c, new long[]{rec, freq, mon});
        }
        assertThat(m.get("C1")).containsExactly(2, 2, 500);
        assertThat(m.get("C5")).containsExactly(1, 2, 500);
        assertThat(m.get("C4")).containsExactly(214, 1, 50);
        // 规则打分(R≤7→5, F≥2→5, M≥400→5, 否则 1):C1=555 C5=555 C4=111
        int high = 0, churn = 0;
        for (long[] v : m.values()) {
            int score = (v[0] <= 7 ? 5 : 1) * 100 + (v[1] >= 2 ? 5 : 1) * 10 + (v[2] >= 400 ? 5 : 1);
            if (score == 555) high++;
            if (score == 111) churn++;
        }
        assertThat(high).isEqualTo(2);   // C1、C5 高价值
        assertThat(churn).isEqualTo(2);  // C2、C4 流失(42天/1次/100元规则下同为 111)
    }

    // S6 落地页 A/B 测试:转化率 / 相对提升 / 样本标准差
    @Test
    void S6_AB测试() {
        DataFrame df = DataFrame.of(Schema.of(
                        "组别", DType.STRING, "用户", DType.STRING, "转化", DType.LONG, "停留分钟", DType.LONG),
                new Object[][]{
                        {"A", "a1", 0L, 1L}, {"A", "a2", 1L, 2L}, {"A", "a3", 1L, 3L}, {"A", "a4", 0L, 4L}, {"A", "a5", 1L, 5L},
                        {"B", "b1", 1L, 2L}, {"B", "b2", 1L, 3L}, {"B", "b3", 0L, 2L}, {"B", "b4", 1L, 3L}, {"B", "b5", 1L, 3L}});
        DataFrame agg = df.groupBy("组别").agg(Map.of("转化", "sum", "用户", "count"));
        double convA = rate(agg, "A"), convB = rate(agg, "B");
        assertThat(convA).isEqualTo(0.6);   // 3/5
        assertThat(convB).isEqualTo(0.8);   // 4/5
        assertThat((convB - convA) / convA).isCloseTo(1.0 / 3, within(1e-9));   // 相对提升 33.33%
        // A 组停留 mean=3,样本 std=√2.5≈1.5811
        DataFrame a = df.query("组别 == 'A'");
        assertThat(a.colMean("停留分钟")).isEqualTo(3.0);
        assertThat(a.colStd("停留分钟")).isCloseTo(1.5811, within(1e-3));
    }

    private static double rate(DataFrame agg, String group) {
        for (int r = 0; r < agg.rowCount(); r++)
            if (agg.getColumn("组别").get(r).equals(group))
                return ((Number) agg.getColumn("转化_sum").get(r)).doubleValue()
                        / ((Number) agg.getColumn("用户_count").get(r)).doubleValue();
        return -1;
    }

    // S7 仓库安全库存预警:低库存过滤 + 可售天数排序
    @Test
    void S7_库存预警() {
        DataFrame df = DataFrame.of(Schema.of(
                        "SKU", DType.STRING, "现库存", DType.LONG, "安全库存", DType.LONG, "日均销量", DType.LONG),
                new Object[][]{{"S1", 120L, 100L, 30L}, {"S2", 40L, 50L, 20L}, {"S3", 10L, 30L, 25L},
                        {"S4", 200L, 80L, 60L}, {"S5", 90L, 60L, 10L}, {"S6", 45L, 70L, 15L},
                        {"S7", 160L, 90L, 45L}, {"S8", 25L, 20L, 5L}});
        // 常量表达式可直写;用户可控值一律 Jian.query + Params(见 SecurityAuditTest)
        DataFrame alert = df.query("现库存 < 安全库存");
        assertThat(alert.rowCount()).isEqualTo(3);
        assertThat(alert.getColumn("SKU").toObjectArray()).containsExactlyInAnyOrder("S2", "S3", "S6");
        // 可售天数 = 现库存/日均:S3 = 0.4 < S2 = 2.0 < S6 = 3.0 → 补货优先级 S3 → S2 → S6
        DataFrame days = alert.assign("可售天数", r ->
                (Long) alert.getColumn("现库存").get(r) * 1.0 / (Long) alert.getColumn("日均销量").get(r));
        assertThat(days.getColumn("可售天数").toObjectArray()).containsExactlyInAnyOrder(0.4, 2.0, 3.0);
        assertThat(days.sortBy("可售天数", true).getColumn("SKU").toObjectArray())
                .containsExactly("S3", "S2", "S6");
    }

    // S15 用户行为月报:corr / cumsum / diff(首行 NA 的 §3.5 契约)
    @Test
    void S15_行为统计月报() {
        DataFrame df = DataFrame.of(Schema.of(
                        "用户", DType.STRING, "登录", DType.LONG, "消费", DType.LONG, "退单", DType.LONG),
                new Object[][]{{"u1", 1L, 10L, 6L}, {"u2", 2L, 20L, 5L}, {"u3", 3L, 30L, 4L},
                        {"u4", 4L, 40L, 3L}, {"u5", 5L, 50L, 2L}, {"u6", 6L, 60L, 1L}});
        // 完全正/负相关
        assertThat(df.colCorr("登录", "消费")).isCloseTo(1.0, within(1e-9));
        assertThat(df.colCorr("登录", "退单")).isCloseTo(-1.0, within(1e-9));
        // 消费累计 [10,30,60,100,150,210](colCumsum 返回独立 DoubleColumn)
        assertThat(java.util.Arrays.stream(df.colCumsum("消费", "累计消费").toObjectArray())
                .mapToLong(v -> ((Number) v).longValue()).toArray())
                .containsExactly(10, 30, 60, 100, 150, 210);
        // diff 首行为缺失(§3.5 契约),其后恒为 10
        Object[] delta = df.colDiff("消费", 1, "日增量").toObjectArray();
        assertThat(delta[0]).isNull();
        for (int r = 1; r < delta.length; r++)
            assertThat(((Number) delta[r]).longValue()).isEqualTo(10);
        // 描述统计:min=10 max=60
        assertThat(df.colMin("消费")).isEqualTo(10.0);
        assertThat(df.colMax("消费")).isEqualTo(60.0);
    }
}
