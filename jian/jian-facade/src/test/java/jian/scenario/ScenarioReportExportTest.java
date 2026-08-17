package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import jian.io.excel.Excel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : ScenarioReportExportTest —— 真实场景测试(报表产出域,S21/S22)
// │  Why  : 定时多 sheet 报表与接口动态导出是企业侧最高频的两类产出形态,须进真实场景集
// │         (四轨红线:场景登记 scenarios.md,完整源码随 jar 分发到 META-INF/ai/scenarios-src/)
// │  Who  : mvn -pl jian-facade test;AI 速查见 jar 内 META-INF/ai/scenarios.md S21/S22 行
// │  When : mvn test(jian-facade 模块);全部走临时目录,不污染工作区
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioReportExportTest.java
// │  How  : 数据走向:(S21)同一份明细 → 三路加工(汇总/明细/排行)→ Excel.writer 多 sheet
// │         一次落盘 → sheetNames 枚举 + 逐 sheet 读回核对;(S22)明细 → query 过滤 →
// │         assign 脱敏(手机号中段打星)→ select 投影 → toCsv → readCsv 回读核对。
class ScenarioReportExportTest {

    @TempDir Path tmp;

    // S21 定时报表产出:一份明细出"汇总/明细/排行"三个 sheet,单文件分发
    @Test
    void S21_定时多sheet报表() throws Exception {
        DataFrame detail = DataFrame.of(Schema.of(
                        "门店", DType.STRING, "品类", DType.STRING, "销售额", DType.LONG),
                new Object[][]{
                        {"A店", "食品", 300L}, {"A店", "饮料", 100L}, {"A店", "食品", 400L},
                        {"B店", "饮料", 200L}, {"B店", "日用", 300L}, {"B店", "日用", 200L}});
        // 三路加工:汇总(品类合计)/ 明细(原样)/ 排行(单笔大额 Top3)
        DataFrame summary = detail.groupBy("品类").agg(java.util.Map.of("销售额", "sum"));
        DataFrame top = detail.sortBy("销售额", false).head(3);
        Path out = tmp.resolve("日报.xlsx");
        try (Excel.ExcelMultiWriter w = Excel.writer(out.toString())) {
            w.write(summary, "汇总").write(detail, "明细").write(top, "排行");
        }
        // sheet 枚举与顺序 = 写入顺序
        List<String> names = Excel.sheetNames(out.toString());
        assertThat(names).containsExactly("汇总", "明细", "排行");
        // 逐 sheet 读回核对:汇总 3 行(食品700/饮料300/日用500),明细 6 行守恒,排行首行 400
        DataFrame s = Excel.read(out.toString()).sheet("汇总").go();
        DataFrame d = Excel.read(out.toString()).sheet("明细").go();
        DataFrame t = Excel.read(out.toString()).sheet("排行").go();
        assertThat(s.rowCount()).isEqualTo(3);
        assertThat(d.rowCount()).isEqualTo(6);
        assertThat(t.rowCount()).isEqualTo(3);
        long sum = 0;
        for (Object v : s.getColumn("销售额_sum").toObjectArray())
            if (v instanceof Number n) sum += n.longValue();
        assertThat(sum).isEqualTo(1500L);
        assertThat(((Number) t.getColumn("销售额").get(0)).longValue()).isEqualTo(400L);
        // 行数守恒:排行是明细的子集,不丢行不重行
        assertThat(t.rowCount()).isLessThanOrEqualTo(d.rowCount());
        // SQL 对照版:汇总与排行两个 sheet 的数据改由 SQL 产出,与链式三路差分一致
        DataFrame sqlSummary = Jian.sql("SELECT 品类, sum(销售额) AS 合计 FROM ${t} GROUP BY 品类", detail);
        DataFrame sqlTop = Jian.sql("SELECT 门店, 品类, 销售额 FROM ${t} ORDER BY 销售额 DESC LIMIT 3", detail);
        assertThat(sqlSummary.rowCount()).isEqualTo(summary.rowCount()).isEqualTo(3);
        long sqlSum = 0;
        for (Object v : sqlSummary.getColumn("合计").toObjectArray())
            if (v instanceof Number n) sqlSum += n.longValue();
        assertThat(sqlSum).isEqualTo(1500L);
        for (int r = 0; r < sqlTop.rowCount(); r++)
            assertThat(sqlTop.getColumn("销售额").get(r)).isEqualTo(top.getColumn("销售额").get(r));
    }

    // S22 接口动态导出的数据加工管道:过滤 → 脱敏 → 投影 → CSV 落盘 → 回读核对
    @Test
    void S22_导出数据加工管道() throws Exception {
        DataFrame df = DataFrame.of(Schema.of(
                        "姓名", DType.STRING, "手机号", DType.STRING, "金额", DType.LONG),
                new Object[][]{
                        {"张三", "13812345678", 500L},
                        {"李四", "13998765432", 80L},
                        {"王五", "13711112222", 1200L}});
        // 管道:金额阈值过滤(常量可直写;用户可控阈值须 Jian.query+Params,见 SecurityAuditTest)
        DataFrame hits = df.query("金额 > 100");
        // 脱敏:保留前 3 后 4,中段打星(11 位 → 3+4+4)
        // 注意 lambda 必须引用过滤后的 hits(行号对齐),引用原 df 会串行取到被过滤行的数据
        DataFrame pipe = hits.assign("手机号脱敏", r -> {
                    String p = (String) hits.getColumn("手机号").get(r);
                    return p.substring(0, 3) + "****" + p.substring(7);
                })
                .select("姓名", "手机号脱敏", "金额");
        assertThat(pipe.rowCount()).isEqualTo(2);   // 李四(80)被过滤
        Path out = tmp.resolve("导出.csv");
        Jian.toCsv(pipe, out.toString());
        // 回读核对:行数、脱敏格式、金额总和 500+1200=1700
        DataFrame back = Jian.readCsv(out.toString());
        assertThat(back.rowCount()).isEqualTo(2);
        assertThat(back.getColumn("手机号脱敏").toObjectArray())
                .containsExactlyInAnyOrder("138****5678", "137****2222");
        long sum = 0;
        for (Object v : back.getColumn("金额").toObjectArray())
            if (v instanceof Number n) sum += n.longValue();
        assertThat(sum).isEqualTo(1700L);
        // 原始手机号不允许出现在导出件里(脱敏完备性)
        String raw = java.nio.file.Files.readString(out);
        assertThat(raw).doesNotContain("13812345678").doesNotContain("13711112222");
        // SQL 对照版:阈值过滤改用 SQL WHERE,再走同一脱敏 assign(与链式管道差分,逐行一致)
        DataFrame sqlHits = Jian.sql("SELECT 姓名, 手机号, 金额 FROM ${t} WHERE 金额 > 100", df);
        DataFrame sqlPipe = sqlHits.assign("手机号脱敏", r -> {
                    String p = (String) sqlHits.getColumn("手机号").get(r);
                    return p.substring(0, 3) + "****" + p.substring(7);
                })
                .select("姓名", "手机号脱敏", "金额");
        assertThat(sqlPipe.rowCount()).isEqualTo(pipe.rowCount()).isEqualTo(2);
        assertThat(sqlPipe.getColumn("手机号脱敏").toObjectArray())
                .containsExactlyElementsOf(java.util.Arrays.asList(pipe.getColumn("手机号脱敏").toObjectArray()));
    }
}
