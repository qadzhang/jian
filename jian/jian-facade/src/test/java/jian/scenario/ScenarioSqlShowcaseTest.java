package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : ScenarioSqlShowcaseTest —— df.sql()/Jian.sql() 优势展示场景(双写法差分,SP1-SP4)
// │  Why  : 场景套件评审发现 15 个场景里 SQL 只出现在 S13(可用性验证),没有展示 SQL 相对
// │         链式 API 的真实优势。本类每个场景【同一任务写两遍】——链式版(多步中间变量)
// │         vs SQL 版(一条语句),断言两版结果逐行相等:既是优势展示,也是差分测试
// │         (ai-code-testing:双实现同输入必同输出,任一侧回归都会被另一方抓住)
// │  Who  : mvn -pl jian-facade test;AI 速查表见 jar 内 META-INF/ai/scenarios.md 的 SP 区
// │  When : mvn test(jian-facade 模块)
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioSqlShowcaseTest.java
class ScenarioSqlShowcaseTest {

    private static DataFrame sales() {
        return DataFrame.of(Schema.of("门店", DType.STRING, "品类", DType.STRING, "销售额", DType.LONG),
                new Object[][]{{"A", "食品", 100L}, {"A", "饮料", 50L}, {"B", "食品", 200L}, {"B", "饮料", 150L},
                        {"A", "食品", 300L}, {"B", "饮料", 50L}, {"A", "饮料", 50L}, {"B", "食品", 100L}});
    }

    private static DataFrame emp() {
        return DataFrame.of(Schema.of("姓名", DType.STRING, "部门ID", DType.LONG, "薪资", DType.LONG),
                new Object[][]{{"张", 1L, 12000L}, {"李", 2L, 8000L}, {"王", 3L, 9500L},
                        {"赵", 2L, 9000L}, {"钱", 9L, 7000L}});
    }

    private static DataFrame dept() {
        return DataFrame.of(Schema.of("部门ID", DType.LONG, "部门名", DType.STRING),
                new Object[][]{{1L, "研发"}, {2L, "市场"}, {3L, "财务"}});
    }

    // SP1 一条 SQL == 四步链式(groupBy→agg→sortBy→head)
    @Test
    void SP1_一条SQL顶四步链式() {
        DataFrame df = sales();
        // 链式版:4 步、3 个中间变量
        DataFrame chained = df.groupBy("品类").agg(Map.of("销售额", "sum"))
                .sortBy("销售额_sum", false).head(1);
        // SQL 版:一句话(HAVING 同步演示聚合后过滤)
        DataFrame sql = Jian.sql("""
                SELECT 品类, sum(销售额) AS 合计 FROM ${t}
                GROUP BY 品类 HAVING 合计 > 400 ORDER BY 合计 DESC LIMIT 1
                """, df);
        // 差分:品类与合计值逐项相等(食品 700)
        assertThat(sql.getColumn("品类").get(0)).isEqualTo(chained.getColumn("品类").get(0));
        assertThat(sql.getColumn("合计").get(0)).isEqualTo(chained.getColumn("销售额_sum").get(0));
        assertThat(sql.rowCount()).isEqualTo(chained.rowCount()).isEqualTo(1);
    }

    // SP2 复杂 WHERE(AND + IN + 比较)== query + filter 组合
    @Test
    void SP2_复杂条件过滤() {
        DataFrame df = sales();
        DataFrame chained = df.query("门店 in ('A', 'B') and 销售额 > 100").select("门店", "品类");
        DataFrame sql = Jian.sql(
                "SELECT 门店, 品类 FROM ${t} WHERE 门店 IN ('A','B') AND 销售额 > 100", df);
        assertThat(sql.rowCount()).isEqualTo(chained.rowCount()).isEqualTo(3);   // 200/300/150
        assertThat(sql.getColumn("品类").toObjectArray())
                .containsExactlyElementsOf(java.util.Arrays.asList(
                        chained.getColumn("品类").toObjectArray()));
    }

    // SP3 JOIN + CASE WHEN 分层 == merge + assign 条件打分
    @Test
    void SP3_JOIN加CASE_WHEN分层() {
        // 链式版:merge(inner) + assign 三元打标
        DataFrame merged = emp().merge(dept(), "inner", "部门ID");
        DataFrame chained = merged.assign("薪档", r ->
                (Long) merged.getColumn("薪资").get(r) >= 10000 ? "高" : "常规");
        // SQL 版:JOIN + CASE WHEN 一条
        DataFrame sql = Jian.sql("""
                SELECT 姓名, 部门名, CASE WHEN 薪资 >= 10000 THEN '高' ELSE '常规' END AS 薪档
                FROM ${e} JOIN ${d} ON e.部门ID = d.部门ID
                """, emp(), dept());
        assertThat(sql.rowCount()).isEqualTo(chained.rowCount()).isEqualTo(4);   // 钱的部门 9 无匹配
        // 差分:逐行 (姓名, 薪档) 相等
        for (int r = 0; r < sql.rowCount(); r++) {
            assertThat(sql.getColumn("姓名").get(r)).isEqualTo(chained.getColumn("姓名").get(r));
            assertThat(sql.getColumn("薪档").get(r)).isEqualTo(chained.getColumn("薪档").get(r));
        }
        assertThat(sql.getColumn("薪档").toObjectArray()).containsExactly("高", "常规", "常规", "常规");
    }

    // SP4 CTE 多步管道 == 链式中间变量(两步:先过滤再聚合)
    @Test
    void SP4_CTE管道() {
        DataFrame df = sales();
        // 链式版:两个中间变量
        DataFrame step1 = df.query("销售额 >= 100");
        DataFrame chained = step1.groupBy("品类").agg(Map.of("销售额", "count"));
        // SQL 版:WITH 一步到位
        DataFrame sql = Jian.sql("""
                WITH 大单 AS (SELECT * FROM ${t} WHERE 销售额 >= 100)
                SELECT 品类, count(*) AS 单数 FROM ${大单} GROUP BY 品类
                """, df);
        assertThat(sql.rowCount()).isEqualTo(chained.rowCount()).isEqualTo(2);
        // 食品大单 4 笔(100/200/300/100>=100)、饮料大单 1 笔(150)
        Map<Object, Object> byCat = new java.util.LinkedHashMap<>();
        for (int r = 0; r < sql.rowCount(); r++)
            byCat.put(sql.getColumn("品类").get(r), sql.getColumn("单数").get(r));
        assertThat(byCat.get("食品")).isEqualTo(4L);
        assertThat(byCat.get("饮料")).isEqualTo(1L);
    }
}
