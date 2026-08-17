package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import jian.core.Window;
import jian.io.sql.Sql;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : ScenarioTimeSeriesTest —— 真实场景测试(时序处理域,S34/S35/S36/S37)
// │  Why  : 高频降采样落库/z-score 异常识别/周期规律透视/滚动窗口指标是时序数据四类
// │         通用加工,须进真实场景集(四轨红线:场景登记 scenarios.md,
// │         完整源码随 jar 分发到 META-INF/ai/scenarios-src/)
// │  Who  : mvn -pl jian-facade test;AI 速查见 jar 内 META-INF/ai/scenarios.md S34~S37 行
// │  When : mvn test(jian-facade 模块);S34 用 H2 in-memory,其余纯内存
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioTimeSeriesTest.java
// │  How  : 数据走向:(S34)分钟级原始点 → resample(5min).mean() 降采样 → toSql 落库 →
// │         readSqlTable 回读;(S35)先算全列 mean/std,再 assign z 分数 → query(|z|>3);
// │         (S36)小时×星期透视 pivotTable;(S37)Series 滚动窗口 Window.Rolling.mean。
class ScenarioTimeSeriesTest {

    // S34 高频数据降采样落库:分钟级 → 5 分钟桶,聚合后写入时序表
    @Test
    void S34_高频降采样落库() throws Exception {
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 10, 0);
        Object[][] rows = new Object[10][];
        for (int i = 0; i < 10; i++) rows[i] = new Object[]{t0.plusMinutes(i), (long) (i + 1)};
        DataFrame raw = DataFrame.of(Schema.of("时间", DType.DATETIME, "VAL", DType.LONG), rows);
        // 5 分钟桶:10:00 桶 = mean(1..5) = 3;10:05 桶 = mean(6..10) = 8
        DataFrame fiveMin = raw.resample("时间", "5min").mean();
        assertThat(fiveMin.rowCount()).isEqualTo(2);
        double[] bucketMeans = new double[2];
        for (int r = 0; r < 2; r++)
            bucketMeans[r] = ((Number) fiveMin.getColumn("VAL_mean").get(r)).doubleValue();
        assertThat(bucketMeans).containsExactly(3.0, 8.0);
        // 计数守恒:桶内计数和 = 原始点数 10
        DataFrame cnt = raw.resample("时间", "5min").count();
        long total = 0;
        for (Object v : cnt.getColumn("VAL_count").toObjectArray())
            if (v instanceof Number n) total += n.longValue();
        assertThat(total).isEqualTo(10L);
        // 落库 → 回读:降采样表 2 行不丢
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:s34;DB_CLOSE_DELAY=-1")) {
            Jian.toSql(fiveMin, conn, "ts_5min", Sql.Mode.CREATE_OR_REPLACE);
            DataFrame back = Jian.readSqlTable(conn, "ts_5min");
            assertThat(back.rowCount()).isEqualTo(2);
        }
        // SQL 对照版:桶键由 assign 派生(时间截断到 5 分钟边界),聚合交给 GROUP BY(与 resample 差分)
        DataFrame withBucket = raw.assign("五分桶", r -> {
            java.time.LocalDateTime t = (java.time.LocalDateTime) raw.getColumn("时间").get(r);
            return t.minusMinutes(t.getMinute() % 5);
        });
        DataFrame sqlBucket = Jian.sql(
                "SELECT 五分桶, mean(VAL) AS m FROM ${t} GROUP BY 五分桶 ORDER BY 五分桶", withBucket);
        assertThat(sqlBucket.rowCount()).isEqualTo(2);
        double[] sqlMeans = new double[2];
        for (int r = 0; r < 2; r++)
            sqlMeans[r] = ((Number) sqlBucket.getColumn("m").get(r)).doubleValue();
        assertThat(sqlMeans).containsExactly(3.0, 8.0);   // 与 resample 逐桶相等
    }

    // S35 时序异常点识别:z 分数超 3 的样本即异常(单点尖峰)
    @Test
    void S35_时序异常识别() {
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 10, 0);
        Object[][] rows = new Object[20][];
        for (int i = 0; i < 19; i++) rows[i] = new Object[]{t0.plusMinutes(i), 10L};
        rows[19] = new Object[]{t0.plusMinutes(19), 300L};   // 末尾尖峰
        DataFrame df = DataFrame.of(Schema.of("时间", DType.DATETIME, "值", DType.LONG), rows);
        // 手算:mean = (19*10+300)/20 = 24.5;样本方差 = [19*(10-24.5)² + (300-24.5)²]/19 = 4205
        // std = √4205 ≈ 64.8460;z(尖峰) = 275.5/64.8460 ≈ 4.2485 > 3;z(正常点) ≈ -0.22
        double mean = df.colMean("值");
        double std = df.colStd("值");
        assertThat(mean).isEqualTo(24.5);
        assertThat(std).isCloseTo(Math.sqrt(4205), org.assertj.core.data.Offset.offset(1e-9));
        DataFrame withZ = df.assign("z", r ->
                (((Number) df.getColumn("值").get(r)).doubleValue() - mean) / std);
        DataFrame anomalies = withZ.query("z > 3");
        assertThat(anomalies.rowCount()).isEqualTo(1);
        assertThat(anomalies.getColumn("时间").get(0)).isEqualTo(t0.plusMinutes(19));
        // SQL 对照版:均值/标准差各一条全局聚合(同源列多聚合会覆盖),z 阈值交给 WHERE
        DataFrame sqlMean = Jian.sql("SELECT mean(值) AS m FROM ${t}", df);
        DataFrame sqlStd = Jian.sql("SELECT std(值) AS s FROM ${t}", df);
        assertThat(((Number) sqlMean.getColumn("m").get(0)).doubleValue()).isEqualTo(24.5);
        assertThat(((Number) sqlStd.getColumn("s").get(0)).doubleValue())
                .isCloseTo(Math.sqrt(4205), org.assertj.core.data.Offset.offset(1e-9));
        DataFrame sqlOut = Jian.sql("SELECT 时间 FROM ${t} WHERE z > 3", withZ);
        assertThat(sqlOut.rowCount()).isEqualTo(1);
        assertThat(sqlOut.getColumn("时间").get(0)).isEqualTo(t0.plusMinutes(19));
    }

    // S36 周期规律透视:客流按 小时×星期 透视,看早晚高峰形态
    @Test
    void S36_周期规律透视() {
        DataFrame df = DataFrame.of(Schema.of(
                        "小时", DType.LONG, "星期", DType.STRING, "客流", DType.LONG),
                new Object[][]{
                        {8L, "一", 90L}, {8L, "二", 100L},
                        {20L, "一", 60L}, {20L, "二", 70L}});
        DataFrame wide = df.pivotTable("小时", "星期", "客流");
        assertThat(wide.rowCount()).isEqualTo(2);   // 两个小时粒度
        // 8 点档:一 90 / 二 100;20 点档:一 60 / 二 70(逐行按小时定位断言)
        for (int r = 0; r < wide.rowCount(); r++) {
            long hour = ((Number) wide.getColumn("小时").get(r)).longValue();
            double mon = ((Number) wide.getColumn("一").get(r)).doubleValue();
            double tue = ((Number) wide.getColumn("二").get(r)).doubleValue();
            if (hour == 8L) { assertThat(mon).isEqualTo(90.0); assertThat(tue).isEqualTo(100.0); }
            else { assertThat(mon).isEqualTo(60.0); assertThat(tue).isEqualTo(70.0); }
        }
        // 早高峰(8 点)两天都高于晚高峰(20 点)
        double m8 = cell(wide, 8L, "一"), m20 = cell(wide, 20L, "一");
        assertThat(m8).isGreaterThan(m20);
        // SQL 对照版:透视 = 条件列(assign 预置按星期拆列)+ GROUP BY 聚合,与 pivotTable 差分。
        // 口径:fn(CASE ...) 聚合暂不支持,条件拆列由 assign 承担,聚合仍归 SQL
        DataFrame withInd = df.assign("一客流", r ->
                        "一".equals(df.getColumn("星期").get(r))
                                ? ((Number) df.getColumn("客流").get(r)).doubleValue() : 0.0)
                .assign("二客流", r ->
                        "二".equals(df.getColumn("星期").get(r))
                                ? ((Number) df.getColumn("客流").get(r)).doubleValue() : 0.0);
        DataFrame sqlPivot = Jian.sql("""
                SELECT 小时, sum(一客流) AS c1, sum(二客流) AS c2
                FROM ${t} GROUP BY 小时 ORDER BY 小时
                """, withInd);
        assertThat(sqlPivot.rowCount()).isEqualTo(2);
        assertThat(((Number) sqlPivot.getColumn("c1").get(0)).doubleValue()).isEqualTo(90.0);
        assertThat(((Number) sqlPivot.getColumn("c2").get(0)).doubleValue()).isEqualTo(100.0);
        assertThat(((Number) sqlPivot.getColumn("c1").get(1)).doubleValue()).isEqualTo(60.0);
        assertThat(((Number) sqlPivot.getColumn("c2").get(1)).doubleValue()).isEqualTo(70.0);
    }

    // S37 滚动窗口指标:3 点移动平均,前 window-1 位为缺失(minPeriods=window)
    @Test
    void S37_滚动窗口指标() {
        DataFrame df = DataFrame.of(Schema.of("t", DType.LONG, "值", DType.LONG),
                new Object[][]{{1L, 1L}, {2L, 2L}, {3L, 3L}, {4L, 4L}, {5L, 5L}});
        Window.Rolling roll = new Window.Rolling(df.getSeries("值"), 3);
        double[] sma = roll.mean();
        assertThat(sma).hasSize(5);
        assertThat(sma[0]).isNaN();   // 窗口未满
        assertThat(sma[1]).isNaN();
        assertThat(sma[2]).isEqualTo(2.0);   // mean(1,2,3)
        assertThat(sma[3]).isEqualTo(3.0);   // mean(2,3,4)
        assertThat(sma[4]).isEqualTo(4.0);   // mean(3,4,5)
        // 滚动求和口径同步可用:末位 = 3+4+5 = 12
        assertThat(new Window.Rolling(df.getSeries("值"), 3).sum()[4]).isEqualTo(12.0);
        // SQL 对照版:全列总和交给全局聚合;滚动末位窗口和 = 总和刨去窗外前两值(交叉验证)
        DataFrame total = Jian.sql("SELECT sum(值) AS total FROM ${t}", df);
        double all = ((Number) total.getColumn("total").get(0)).doubleValue();
        assertThat(all).isEqualTo(15.0);
        assertThat(all - 1 - 2).isEqualTo(12.0);   // 与 Rolling.sum 末位一致
    }

    /** 透视表按索引键取单元格(小工具)。 */
    private static double cell(DataFrame wide, long hour, String col) {
        for (int r = 0; r < wide.rowCount(); r++)
            if (((Number) wide.getColumn("小时").get(r)).longValue() == hour)
                return ((Number) wide.getColumn(col).get(r)).doubleValue();
        throw new AssertionError("小时不存在: " + hour);
    }
}
