package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : ScenarioSalesFinanceTest —— 真实业务场景测试(销售/财务域,S1/S2/S10/S12/S14)
// │  Why  : 按 ai-code-testing 方法论 + pandas 真实用例网络调研
// │         设计的端到端场景;数据全部 ≤10 行、预期结果全部可手算,每场景 ≥3 个独立断言
// │  Who  : mvn -pl jian-facade test;场景清单的 AI 版见 jar 内 META-INF/ai/scenarios.md
// │  When : mvn test(jian-facade 模块)
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioSalesFinanceTest.java
class ScenarioSalesFinanceTest {

    @TempDir Path tmp;

    private static final String[][] SALES = {
            {"A", "食品", "100"}, {"A", "饮料", "50"}, {"B", "食品", "200"}, {"B", "饮料", "150"},
            {"A", "食品", "300"}, {"B", "饮料", "50"}, {"A", "饮料", "50"}, {"B", "食品", "100"}};

    private static DataFrame sales() {
        Object[][] rows = new Object[SALES.length][];
        for (int i = 0; i < SALES.length; i++)
            rows[i] = new Object[]{SALES[i][0], SALES[i][1], Long.parseLong(SALES[i][2])};
        return DataFrame.of(Schema.of("门店", DType.STRING, "品类", DType.STRING, "销售额", DType.LONG), rows);
    }

    // S1 门店×品类销售月度汇总:连锁便利店月底汇总,找头部品类
    @Test
    void S1_门店品类销售汇总() throws Exception {
        Path csv = tmp.resolve("sales.csv");
        Jian.toCsv(sales(), csv.toString());
        DataFrame df = Jian.readCsv(csv.toString());

        DataFrame byCat = df.groupBy("品类").agg(Map.of("销售额", "sum"));
        // 品类总计:食品 = 100+200+300+100 = 700;饮料 = 50+150+50+50 = 300
        Map<Object, Double> catSum = new LinkedHashMap<>();
        for (int r = 0; r < byCat.rowCount(); r++)
            catSum.put(byCat.getColumn("品类").get(r), (Double) byCat.getColumn("销售额_sum").get(r));
        assertThat(catSum.get("食品")).isEqualTo(700.0);
        assertThat(catSum.get("饮料")).isEqualTo(300.0);

        DataFrame byStore = df.groupBy("门店").agg(Map.of("销售额", "sum"));
        assertThat(byStore.rowCount()).isEqualTo(2);
        // 按值查找 extract(不用 get(0) 假定 groupBy 首行必是 "A"):
        // 与行序解耦,任意分组顺序下都验证真值
        double totalA = extract(byStore, "A");
        assertThat(totalA).as("门店 A 销售额").isEqualTo(500.0);
        assertThat(totalA + extract(byStore, "B")).isEqualTo(1000.0);   // A=500 + B=500

        // 头部:按品类销售降序,食品第一
        DataFrame top = byCat.sortBy("销售额_sum", false);
        assertThat(top.getColumn("品类").get(0)).isEqualTo("食品");
    }

    private static double extract(DataFrame df, String store) {
        for (int r = 0; r < df.rowCount(); r++)
            if (df.getColumn("门店").get(r).equals(store))
                return (Double) df.getColumn("销售额_sum").get(r);
        return -1;
    }

    // S2 银行流水 vs 公司账对账:揪单边账 + 金额不符
    @Test
    void S2_银行对账() {
        DataFrame bank = DataFrame.of(Schema.of("交易号", DType.STRING, "金额", DType.LONG),
                new Object[][]{{"T001", 1000L}, {"T002", 2500L}, {"T003", 800L}, {"T004", 1200L}, {"T005", 300L}});
        DataFrame corp = DataFrame.of(Schema.of("交易号", DType.STRING, "金额", DType.LONG),
                new Object[][]{{"T001", 1000L}, {"T002", 2500L}, {"T003", 850L}, {"T005", 300L}, {"T006", 999L}});
        // 两侧总额差 = 5800 - 5649 = 151
        assertThat(bank.colSum("金额") - corp.colSum("金额")).isEqualTo(151.0);
        // outer 合并后差异行:T004(仅银行)/T006(仅公司)/T003(800≠850)共 3 行;
        // 对齐 pandas:重名列两边都加后缀 → 左表 金额_x、右表 金额_y
        DataFrame outer = bank.merge(corp, "outer", "交易号");
        DataFrame diff = outer.query("金额_x is null || 金额_y is null || 金额_x != 金额_y");
        assertThat(diff.rowCount()).isEqualTo(3);
        assertThat(diff.getColumn("交易号").toObjectArray()).containsExactlyInAnyOrder("T003", "T004", "T006");
    }

    // S10 支付重试重复订单去重(保最新),量化虚增
    @Test
    void S10_重复订单去重() {
        DataFrame df = DataFrame.of(Schema.of("订单号", DType.STRING, "时间", DType.STRING, "金额", DType.LONG),
                new Object[][]{{"O1", "10:00", 100L}, {"O2", "10:05", 200L}, {"O1", "10:06", 100L},
                        {"O3", "10:07", 300L}, {"O2", "10:08", 200L}, {"O4", "10:09", 150L},
                        {"O1", "10:10", 100L}, {"O5", "10:11", 250L}});
        assertThat(df.duplicated(new String[]{"订单号"}, "first")).hasSize(8);
        long dupCount = 0;
        for (boolean b : df.duplicated(new String[]{"订单号"}, "first")) if (b) dupCount++;
        assertThat(dupCount).isEqualTo(3);   // O1×2 + O2×1
        // 去重前 1400,保最新去重后 5 单 1000,虚增 400
        assertThat(df.colSum("金额")).isEqualTo(1400.0);
        DataFrame dedup = df.dropDuplicates(new String[]{"订单号"}, "last");
        assertThat(dedup.rowCount()).isEqualTo(5);
        assertThat(dedup.colSum("金额")).isEqualTo(1000.0);
        assertThat(df.colSum("金额") - dedup.colSum("金额")).isEqualTo(400.0);
    }

    // S12 全国总表按区域拆分导出(写后读回校验,IO 无损不变式)
    @Test
    void S12_区域拆分导出读回() throws Exception {
        DataFrame df = DataFrame.of(Schema.of("区域", DType.STRING, "经理", DType.STRING, "金额", DType.LONG),
                new Object[][]{{"华东", "A", 100L}, {"华东", "B", 200L}, {"华南", "C", 150L},
                        {"华北", "D", 300L}, {"华南", "E", 50L}, {"华北", "F", 120L}});
        int files = 0, rows = 0;
        double sum = 0;
        for (Object region : df.groupBy("区域").agg(Map.of("金额", "sum")).getColumn("区域").toObjectArray()) {
            DataFrame part = Jian.query(df, "区域 == ${r}", jian.dsl.Params.of("r", region));
            Path out = tmp.resolve(region + ".xlsx");
            Jian.toExcel(part, out.toString());
            DataFrame back = Jian.readExcel(out.toString());
            files++;
            rows += back.rowCount();
            sum += back.colSum("金额");
        }
        assertThat(files).isEqualTo(3);
        assertThat(rows).isEqualTo(6);          // 行守恒:6 行不多不少
        assertThat(sum).isEqualTo(920.0);       // 金额守恒:100+200+150+300+50+120
    }

    // S14 汇率按生效日就近匹配(merge_asof backward):01-31 必须取 7.3 而非 7.4
    @Test
    void S14_汇率就近折算() {
        DataFrame rates = DataFrame.of(Schema.of("日期", DType.STRING, "汇率", DType.DOUBLE),
                new Object[][]{{"01-01", 7.1}, {"01-10", 7.2}, {"01-20", 7.3}, {"02-01", 7.4}});
        DataFrame tx = DataFrame.of(Schema.of("日期", DType.STRING, "美元", DType.LONG),
                new Object[][]{{"01-05", 100L}, {"01-12", 200L}, {"01-25", 50L}, {"01-31", 80L}, {"02-03", 10L}});
        DataFrame m = tx.mergeAsof(rates, "日期");
        assertThat(m.rowCount()).isEqualTo(5);
        java.util.List<Object> got = java.util.Arrays.asList(m.getColumn("汇率").toObjectArray());
        // backward 语义:每笔交易取「最近一次已生效」汇率;01-31 在 01-20 与 02-01 之间 → 7.3
        assertThat(got).containsExactly(7.1, 7.2, 7.3, 7.3, 7.4);
        DataFrame cny = m.assign("人民币", r -> (Double) m.getColumn("汇率").get(r) * (Long) m.getColumn("美元").get(r));
        assertThat(cny.colSum("人民币")).isEqualTo(710 + 1440 + 365 + 584 + 74.0);   // 3173
    }

    // S13(财务同事的 SQL 视角):内存 DataFrame 直接 SQL 报表 + 只读防护
    @Test
    void S13_SQL直查内存表() {
        DataFrame df = sales();
        DataFrame r = Jian.sql(
                "SELECT 品类, sum(销售额) AS 合计 FROM ${t} GROUP BY 品类 HAVING 合计 > 400 ORDER BY 合计 DESC", df);
        assertThat(r.rowCount()).isEqualTo(1);              // 饮料 300 被 HAVING 过滤
        assertThat(r.getColumn("品类").get(0)).isEqualTo("食品");
        assertThat(r.getColumn("合计").get(0)).isEqualTo(700.0);
        assertThat(Jian.sql("SELECT count(*) AS n FROM ${t} WHERE 门店 = 'A'", df).getColumn("n").get(0))
                .isEqualTo(4L);
        // 只读防护:非 SELECT 语句被拒(L3 入口收窄)
        assertThatThrownBy(() -> Jian.sql("DROP TABLE ${t}", df))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
