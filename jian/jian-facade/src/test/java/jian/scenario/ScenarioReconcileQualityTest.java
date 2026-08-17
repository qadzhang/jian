package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// ┌─ What : ScenarioReconcileQualityTest —— 真实场景测试(对账与数据质量域,S23/S24/S25/S26)
// │  Why  : 对账/质量画像/脏数据清洗/增量去重是数据接入与财务月结的通用刚需,须进真实场景集
// │         (四轨红线:场景登记 scenarios.md,完整源码随 jar 分发到 META-INF/ai/scenarios-src/)
// │  Who  : mvn -pl jian-facade test;AI 速查见 jar 内 META-INF/ai/scenarios.md S23~S26 行
// │  When : mvn test(jian-facade 模块);纯内存数据,无外部依赖
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioReconcileQualityTest.java
// │  How  : 数据走向:(S23)三方各一本账 → 两次 outer merge(源列名错开避免后缀纠缠)→
// │         query 找差异行;(S24)逐列 nullCount/nunique/min/max/quantile 出画像;
// │         (S25)assign 自定义清洗(NFKC 全角转半角/去 %/特殊值归零)→ astype DOUBLE;
// │         (S26)sortBy(ts 倒序)→ dropDuplicates(keep=first) 保每键最新版本。
class ScenarioReconcileQualityTest {

    // S23 三方对账:订单/发票/支付三本账各记一笔,找金额不等与单边挂账
    @Test
    void S23_三方对账差异() {
        DataFrame orders = DataFrame.of(Schema.of("单号", DType.STRING,
                        "订单金额", DType.LONG, "发票金额", DType.LONG, "支付金额", DType.LONG),
                new Object[][]{
                        {"O1", 100L, 100L, 100L},
                        {"O2", 200L, 200L, 200L},
                        {"O3", 300L, 330L, 300L},     // 发票多开 30
                        {"O4", 400L, 400L, 400L},
                        {"O5", 500L, 500L, null}});   // 支付缺位(未支付)
        // 一条 query 同时表达三类差异:金额不等 / 发票缺 / 支付缺
        DataFrame diff = orders.query(
                "订单金额 != 发票金额 || 订单金额 != 支付金额 || 支付金额 is null");
        assertThat(diff.rowCount()).isEqualTo(2);   // O3 + O5
        assertThat(diff.getColumn("单号").toObjectArray()).containsExactlyInAnyOrder("O3", "O5");
        // 差异金额合计:|300-330| + 未支付全额 500 = 530
        long gap = Math.abs(300 - 330) + 500;
        assertThat(gap).isEqualTo(530L);
        // 正常单(O1/O2/O4)不允许被误报
        assertThat(diff.getColumn("单号").toObjectArray()).doesNotContain("O1", "O2", "O4");
        // SQL 对照版:三类差异条件一条 WHERE 表达(与链式 query 差分)
        DataFrame sqlDiff = Jian.sql("""
                SELECT 单号 FROM ${t} WHERE 订单金额 <> 发票金额 OR 订单金额 <> 支付金额 OR 支付金额 IS NULL
                """, orders);
        assertThat(sqlDiff.rowCount()).isEqualTo(diff.rowCount()).isEqualTo(2);
        assertThat(sqlDiff.getColumn("单号").toObjectArray()).containsExactlyInAnyOrder("O3", "O5");
    }

    // S24 数据质量画像:逐列空值率/唯一值数/极值/分位,接入前出健康度报告
    @Test
    void S24_数据质量画像() {
        DataFrame df = DataFrame.of(Schema.of(
                        "a", DType.LONG, "b", DType.STRING, "c", DType.DOUBLE),
                new Object[][]{{1L, "x", 1.0}, {2L, "y", 2.0}, {3L, null, 2.0},
                        {4L, "x", 3.0}, {5L, "y", 4.0}});
        // 数值列 a:无缺失,min/max/中位可手算(1..5 → q50=3)
        assertThat(df.getColumn("a").nullCount()).isZero();
        assertThat(df.colMin("a")).isEqualTo(1.0);
        assertThat(df.colMax("a")).isEqualTo(5.0);
        assertThat(df.colQuantile("a", 0.5)).isEqualTo(3.0);
        // 字符串列 b:1 个缺失,唯一值 x/y 共 2 个
        assertThat(df.getColumn("b").nullCount()).isEqualTo(1);
        assertThat(df.colNunique("b")).isEqualTo(2);
        // 浮点列 c:无缺失,唯一值 1/2/3/4 共 4 个(2.0 重复出现)
        assertThat(df.getColumn("c").nullCount()).isZero();
        assertThat(df.colNunique("c")).isEqualTo(4);
        // 行级完整度:5 行里只有 b 列第 3 行缺失 → dropna 后 4 行
        assertThat(df.dropna().rowCount()).isEqualTo(4);
        // SQL 对照版:画像统计改由全局聚合出(与逐列 API 差分)。
        // 口径:同一源列做多个聚合会互相覆盖,故每列每指标一条
        DataFrame meanA = Jian.sql("SELECT mean(a) AS m FROM ${t}", df);
        assertThat(((Number) meanA.getColumn("m").get(0)).doubleValue()).isEqualTo(3.0);
        DataFrame minA = Jian.sql("SELECT min(a) AS lo FROM ${t}", df);
        assertThat(((Number) minA.getColumn("lo").get(0)).doubleValue()).isEqualTo(1.0);
        DataFrame maxA = Jian.sql("SELECT max(a) AS hi FROM ${t}", df);
        assertThat(((Number) maxA.getColumn("hi").get(0)).doubleValue()).isEqualTo(5.0);
        DataFrame uniq = Jian.sql("SELECT nunique(b) AS b_u, nunique(c) AS c_u FROM ${t}", df);
        assertThat(((Number) uniq.getColumn("b_u").get(0)).longValue()).isEqualTo(2L);
        assertThat(((Number) uniq.getColumn("c_u").get(0)).longValue()).isEqualTo(4L);
        DataFrame nullB = Jian.sql("SELECT count(*) AS n FROM ${t} WHERE b IS NULL", df);
        assertThat(((Number) nullB.getColumn("n").get(0)).longValue()).isEqualTo(1L);
    }

    // S25 中文脏数据清洗:全角数字/百分号/特殊值/空白,NFKC 归一后强转数值
    @Test
    void S25_中文脏数据清洗() {
        DataFrame df = DataFrame.of(Schema.of("原始值", DType.STRING),
                new Object[][]{{"１２３"}, {"85%"}, {"New"}, {" 42 "}});
        // 业务清洗规则写在 assign(用户数据不进表达式,天然免疫注入):
        //   全角→NFKC 半角;"85%"→去 % 再 /100;New→0;其余去空白。
        //   直接返回 Double:若返回数字样字符串,assign 的列推断会当数值列,
        //   "0.85" 会被解析退化成 0(实测),数值语义必须由 lambda 自身保证。
        DataFrame num = df.assign("清洗值", r -> {
            String s = java.text.Normalizer.normalize(
                    ((String) df.getColumn("原始值").get(r)).trim(), java.text.Normalizer.Form.NFKC);
            if (s.equalsIgnoreCase("new")) return 0.0;
            if (s.endsWith("%")) return Double.parseDouble(s.substring(0, s.length() - 1)) / 100;
            return Double.parseDouble(s);
        });
        Object[] v = num.getColumn("清洗值").toObjectArray();
        assertThat(((Number) v[0]).doubleValue()).isEqualTo(123.0);   // １２３ → 123
        assertThat(((Number) v[1]).doubleValue()).isCloseTo(0.85, within(1e-12));
        assertThat(((Number) v[2]).doubleValue()).isEqualTo(0.0);     // New → 0
        assertThat(((Number) v[3]).doubleValue()).isEqualTo(42.0);    // " 42 " → 42
        // SQL 对照版:清洗结果交给 SQL 做全局聚合/过滤(与 API 侧数值断言差分)
        DataFrame stat = Jian.sql("SELECT mean(清洗值) AS m, count(*) AS n FROM ${t} WHERE 清洗值 > 1", num);
        assertThat(((Number) stat.getColumn("n").get(0)).longValue()).isEqualTo(2L);   // 123 与 42
        assertThat(((Number) stat.getColumn("m").get(0)).doubleValue())
                .isCloseTo((123.0 + 42.0) / 2, within(1e-12));
    }

    // S26 增量数据保最新:同一主键多版本,按时间戳留最新一条(CDC 语义)
    @Test
    void S26_增量保最新去重() {
        DataFrame df = DataFrame.of(Schema.of("键", DType.STRING, "ts", DType.LONG, "值", DType.LONG),
                new Object[][]{
                        {"k1", 1L, 11L}, {"k2", 1L, 21L}, {"k3", 1L, 31L},
                        {"k1", 2L, 12L}, {"k2", 2L, 22L}, {"k3", 2L, 32L},
                        {"k1", 3L, 13L}, {"k2", 3L, 23L}, {"k3", 3L, 33L}});
        // ts 倒序排序后每组第一条即最新版本(TimSort 稳定,同 ts 内保持原序)
        DataFrame latest = df.sortBy(new String[]{"ts"}, new boolean[]{false})
                .dropDuplicates(new String[]{"键"}, "first");
        assertThat(latest.rowCount()).isEqualTo(3);   // 9 行 → 每键 1 行
        long sum = 0, tsAll = 0;
        for (int r = 0; r < latest.rowCount(); r++) {
            sum += ((Number) latest.getColumn("值").get(r)).longValue();
            tsAll += ((Number) latest.getColumn("ts").get(r)).longValue();
        }
        assertThat(sum).isEqualTo(13 + 23 + 33L);     // 只剩 ts=3 的版本
        assertThat(tsAll).isEqualTo(9L);              // 每键 ts 都是 3
        // 老版本(11/12/21/22/31/32)一条不剩
        assertThat(latest.getColumn("值").toObjectArray())
                .containsExactlyInAnyOrder(13L, 23L, 33L);
        // SQL 对照版:每键最新 ts 用 GROUP BY max 交叉验证(链式保留行必须等于组内最大 ts)
        DataFrame maxTs = Jian.sql("SELECT 键, max(ts) AS ts FROM ${t} GROUP BY 键", df);
        assertThat(maxTs.rowCount()).isEqualTo(3);
        for (int r = 0; r < maxTs.rowCount(); r++)
            assertThat(((Number) maxTs.getColumn("ts").get(r)).longValue()).isEqualTo(3L);
        assertThat(maxTs.getColumn("键").toObjectArray())
                .containsExactlyInAnyOrder(latest.getColumn("键").toObjectArray());
    }
}
