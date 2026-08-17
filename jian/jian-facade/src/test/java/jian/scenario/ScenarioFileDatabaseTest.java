package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import jian.export.Styler;
import jian.io.sql.Sql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : ScenarioFileDatabaseTest —— 真实场景测试(文件⇄数据库域,S17/S18/S19/S20)
// │  Why  : 表格文件与数据库的双向搬运是最通用的企业需求(导入校验入库/库分析后带色导出/
// │         迁移一致性核对/多源取数合并),必须进真实场景集(四轨红线:场景须登记 scenarios.md
// │         且完整源码随 jar 分发到 META-INF/ai/scenarios-src/)
// │  Who  : mvn -pl jian-facade test;AI 速查见 jar 内 META-INF/ai/scenarios.md S17~S20 行
// │  When : mvn test(jian-facade 模块);数据库用 H2 in-memory,无需外部环境
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioFileDatabaseTest.java
// │  How  : 数据走向:内存构造的小表 →(S17)先落 xlsx 再 readExcel 模拟真实上传 →
// │         assign 规则校验分流 → 合法行 toSql 入 H2 / 非法行 toExcel 错误回执;
// │         (S18)H2 表 readSqlTable → groupBy 分析 → Styler 条件着色 → toExcel → POI 读回;
// │         (S19)源/目标两表 readSqlTable → merge(outer) → query 找差异行;
// │         (S20)两表各 readSqlTable → merge(inner) → groupBy 即席汇总。
// │         标识符口径:Sql.write/readTable 按需加引号 —— 简单 ASCII 不加引号(走库默认
// │         折叠),中文列名/表名以库引号符包裹并双写转义,严格按输入保真往返
// │         ("AA_a啊" 这类大小写+中文混合名原样建列);注入元字符同样被引号化为字面量。
// │         每场景 ≥3 独立断言,期望值全部可手算(注释里给算式);每场景附
// │         『SQL 对照版』段:同一数据加工改写为一条 Jian.sql,与链式版差分断言一致。
class ScenarioFileDatabaseTest {

    @TempDir Path tmp;

    /** H2 in-memory 连接(每场景独立库名,DB_CLOSE_DELAY=-1 保持连接期存活)。 */
    private static Connection h2(String name) throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }

    // S17 表格文件批量导入数据库:先落盘再读回(模拟真实上传),规则校验分流,错误行出回执
    @Test
    void S17_表格导入数据库含校验分流() throws Exception {
        // 税号带字母前缀,防止 Excel 类型推断把纯数字税号当 LONG(真实税号亦含校验位字母)
        DataFrame upload = DataFrame.of(Schema.of(
                        "编码", DType.STRING, "名称", DType.STRING, "税号", DType.STRING, "余额", DType.LONG),
                new Object[][]{
                        {"V001", "甲", "T1101101101101101X", 1000L},
                        {"V002", "乙", "T2202202202202202X", 2000L},
                        {"V003", "丙", null, 3000L},              // 税号缺失 → 非法
                        {"V004", "丁", "T1234", 4000L},           // 税号长度≠18 → 非法
                        {"V005", "戊", "T3303303303303303X", 5000L},
                        {"V006", "己", "T4404404404404404X", 6000L}});
        // 真实路径:上传件是磁盘上的 xlsx,不是内存对象
        Path input = tmp.resolve("供应商上传.xlsx");
        Jian.toExcel(upload, input.toString());
        DataFrame df = Jian.readExcel(input.toString());
        assertThat(df.rowCount()).isEqualTo(6);
        // 规则校验列:税号非空且 18 位为 OK,否则给原因(业务规则在 assign 里,用户输入不进表达式)
        DataFrame checked = df.assign("校验", r -> {
            Object t = df.getColumn("税号").get(r);
            if (t == null) return "税号缺失";
            return ((String) t).length() == 18 ? "OK" : "税号长度非18";
        });
        DataFrame valid = checked.query("校验 == 'OK'");
        DataFrame reject = checked.query("校验 != 'OK'");
        assertThat(valid.rowCount()).isEqualTo(4);      // V001/V002/V005/V006
        assertThat(reject.rowCount()).isEqualTo(2);     // V003/V004
        // 合法行入库(H2 in-memory,中文列名经引号包裹保真)+ 读回核对:行数与余额总和守恒
        try (Connection conn = h2("s17")) {
            Jian.toSql(valid.select("编码", "名称", "税号", "余额"), conn, "suppliers", Sql.Mode.CREATE_OR_REPLACE);
            DataFrame back = Jian.readSqlTable(conn, "suppliers");
            assertThat(back.rowCount()).isEqualTo(4);
            assertThat(back.columnNames()).containsExactly("编码", "名称", "税号", "余额");   // 中文列名保真
            long sum = 0;
            for (Object v : back.getColumn("余额").toObjectArray())
                if (v instanceof Number n) sum += n.longValue();
            assertThat(sum).isEqualTo(1000 + 2000 + 5000 + 6000L);   // = 14000
        }
        // 非法行生成错误回执退回上传方,读回核对
        Path receipt = tmp.resolve("错误回执.xlsx");
        Jian.toExcel(reject.select("编码", "名称", "校验"), receipt.toString());
        DataFrame back2 = Jian.readExcel(receipt.toString());
        assertThat(back2.rowCount()).isEqualTo(2);
        assertThat(back2.getColumn("编码").toObjectArray()).containsExactlyInAnyOrder("V003", "V004");
        assertThat(back2.getColumn("校验").toObjectArray())
                .containsExactlyInAnyOrder("税号缺失", "税号长度非18");
        // SQL 对照版:合法/非法分流与计数改用一条 SQL(与链式 query 差分,逐行一致)
        DataFrame sqlValid = Jian.sql("SELECT 编码, 名称, 税号, 余额 FROM ${t} WHERE 校验 = 'OK'", checked);
        assertThat(sqlValid.rowCount()).isEqualTo(valid.rowCount()).isEqualTo(4);
        assertThat(sqlValid.getColumn("编码").toObjectArray())
                .containsExactlyElementsOf(java.util.Arrays.asList(valid.getColumn("编码").toObjectArray()));
        DataFrame rejectCnt = Jian.sql("SELECT count(*) AS n FROM ${t} WHERE 校验 != 'OK'", checked);
        assertThat(((Number) rejectCnt.getColumn("n").get(0)).longValue()).isEqualTo(2L);
    }

    // S18 数据库数据统计分析后导出带行列颜色的 Excel(条件整行着色/千分位/加粗,POI 读回验证)
    @Test
    void S18_库分析后导出带色Excel() throws Exception {
        DataFrame sales = DataFrame.of(Schema.of(
                        "门店", DType.STRING, "品类", DType.STRING, "销售额", DType.LONG),
                new Object[][]{
                        {"A店", "食品", 300L}, {"A店", "饮料", 100L}, {"A店", "食品", 400L},
                        {"B店", "饮料", 200L}, {"B店", "日用", 300L}, {"B店", "日用", 200L}});
        try (Connection conn = h2("s18")) {
            Jian.toSql(sales, conn, "sales", Sql.Mode.CREATE_OR_REPLACE);
            DataFrame fromDb = Jian.readSqlTable(conn, "sales");
            assertThat(fromDb.rowCount()).isEqualTo(6);
            assertThat(fromDb.columnNames()).containsExactly("门店", "品类", "销售额");   // 中文列名保真
            // 库里取数 → 即席分析:品类汇总(食品 300+400=700 / 饮料 100+200=300 / 日用 500)
            DataFrame sum = fromDb.groupBy("品类").agg(java.util.Map.of("销售额", "sum"));
            // 样式规则:滞销品类(合计<350)整行红底;合计千分位原生格式;头部品类(>600)加粗
            Path out = tmp.resolve("品类汇总报表.xlsx");
            Styler.of(sum)
                    .rowBackgroundIf("销售额_sum", "#ffcccc", v -> ((Number) v).doubleValue() < 350)
                    .format("#,##0", "销售额_sum")
                    .boldIf("销售额_sum", v -> ((Number) v).doubleValue() > 600)
                    .toExcel(out.toString());
            // POI 读回逐项验证(真实文件):饮料行(row2)红底、食品行(row1)加粗、数值可求和 1500
            try (org.apache.poi.ss.usermodel.Workbook wb =
                         org.apache.poi.ss.usermodel.WorkbookFactory.create(out.toFile())) {
                org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheet(wb.getSheetName(0));
                // groupBy 输出按首次出现排序:食品(row1)/饮料(row2)/日用(row3)
                for (int c = 1; c <= 2; c++)
                    assertThat(sheet.getRow(2).getCell(c).getCellStyle().getFillForegroundColorColor())
                            .as("饮料行整行红底").isNotNull();
                assertThat(sheet.getRow(1).getCell(1).getCellStyle().getFillForegroundColorColor())
                        .as("食品行不整行染色").isNull();
                var x = (org.apache.poi.xssf.usermodel.XSSFCellStyle) sheet.getRow(1).getCell(2).getCellStyle();
                assertThat(x.getFont().getBold()).as("食品(700>600)加粗").isTrue();
                assertThat(sheet.getRow(1).getCell(2).getCellStyle().getDataFormatString())
                        .isEqualTo("#,##0");
                double total = 0;
                for (int r = 1; r <= 3; r++) total += sheet.getRow(r).getCell(2).getNumericCellValue();
                assertThat(total).isEqualTo(700 + 300 + 500.0);   // = 1500
            }
            // SQL 对照版:同一品类汇总一条 GROUP BY(与 groupBy.agg 差分,逐行一致)
            DataFrame sqlSum = Jian.sql(
                    "SELECT 品类, sum(销售额) AS 销售额_sum FROM ${t} GROUP BY 品类", fromDb);
            assertThat(sqlSum.rowCount()).isEqualTo(sum.rowCount()).isEqualTo(3);
            for (int r = 0; r < sqlSum.rowCount(); r++) {
                assertThat(sqlSum.getColumn("品类").get(r)).isEqualTo(sum.getColumn("品类").get(r));
                assertThat(((Number) sqlSum.getColumn("销售额_sum").get(r)).longValue())
                        .isEqualTo(((Number) sum.getColumn("销售额_sum").get(r)).longValue());
            }
        }
    }

    // S19 库间迁移一致性校验:源/目标各读一张表,outer merge 对齐后找差异行
    @Test
    void S19_迁移一致性校验() throws Exception {
        DataFrame src = DataFrame.of(Schema.of("编号", DType.LONG, "金额", DType.LONG),
                new Object[][]{{1L, 100L}, {2L, 200L}, {3L, 300L}, {4L, 400L}, {5L, 500L}});
        // 目标库模拟迁移结果:id3 金额被改坏(300→330),id5 整行丢失
        DataFrame tgt = DataFrame.of(Schema.of("编号", DType.LONG, "金额", DType.LONG),
                new Object[][]{{1L, 100L}, {2L, 200L}, {3L, 330L}, {4L, 400L}});
        try (Connection conn = h2("s19")) {
            Jian.toSql(src, conn, "mig_src", Sql.Mode.CREATE_OR_REPLACE);
            Jian.toSql(tgt, conn, "mig_tgt", Sql.Mode.CREATE_OR_REPLACE);
            DataFrame left = Jian.readSqlTable(conn, "mig_src");
            DataFrame right = Jian.readSqlTable(conn, "mig_tgt");
            DataFrame joined = left.merge(right, "outer", "编号");   // 重名列自动 _x/_y(对齐 pandas)
            DataFrame diff = joined.query(
                    "金额_x is null || 金额_y is null || 金额_x != 金额_y");
            assertThat(diff.rowCount()).isEqualTo(2);   // id3 金额不等 + id5 目标缺失
            assertThat(diff.getColumn("编号").toObjectArray()).containsExactlyInAnyOrder(3L, 5L);
            // 差异金额合计:|300-330| + 单边行按源库全额 500 = 530
            long gap = 0;
            for (int r = 0; r < diff.rowCount(); r++) {
                Object a = diff.getColumn("金额_x").get(r), b = diff.getColumn("金额_y").get(r);
                long av = a instanceof Number n ? n.longValue() : 0;
                long bv = b instanceof Number n ? n.longValue() : 0;
                gap += Math.abs(av - bv);
            }
            assertThat(gap).isEqualTo(30 + 500L);
            // 未受影响的行不允许被误报:id 1/2/4 不在差异集
            assertThat(diff.getColumn("编号").toObjectArray()).doesNotContain(1L, 2L, 4L);
            // SQL 对照版:FULL OUTER JOIN 一条语句找出全部差异行(与 merge+query 差分)。
            // 口径:表限定名(a.x)仅 ON 子句支持;JOIN 后重名列走 _x/_y 后缀(对齐 pandas)
            DataFrame sqlDiff = Jian.sql("""
                    SELECT 编号 FROM ${a} FULL OUTER JOIN ${b} ON a.编号 = b.编号
                    WHERE 金额_x <> 金额_y OR 金额_x IS NULL OR 金额_y IS NULL
                    """, left, right);
            assertThat(sqlDiff.rowCount()).isEqualTo(diff.rowCount()).isEqualTo(2);
            assertThat(sqlDiff.getColumn("编号").toObjectArray()).containsExactlyInAnyOrder(3L, 5L);
        }
    }

    // S20 多源数据库表合并即席分析:两张表各读各的,merge 后按维度汇总
    @Test
    void S20_多源取数合并即席分析() throws Exception {
        DataFrame customers = DataFrame.of(Schema.of("客户", DType.STRING, "城市", DType.STRING),
                new Object[][]{{"C1", "北京"}, {"C2", "上海"}, {"C3", "北京"}});
        DataFrame orders = DataFrame.of(Schema.of("客户", DType.STRING, "金额", DType.LONG),
                new Object[][]{{"C1", 100L}, {"C1", 200L}, {"C2", 150L}, {"C3", 300L}});
        try (Connection conn = h2("s20")) {
            Jian.toSql(customers, conn, "dim_customer", Sql.Mode.CREATE_OR_REPLACE);
            Jian.toSql(orders, conn, "fact_order", Sql.Mode.CREATE_OR_REPLACE);
            DataFrame dim = Jian.readSqlTable(conn, "dim_customer");
            DataFrame fact = Jian.readSqlTable(conn, "fact_order");
            DataFrame wide = fact.merge(dim, "inner", "客户");
            assertThat(wide.rowCount()).isEqualTo(4);   // 事实表每单一行,维度补城市
            // 北京 = 100+200+300 = 600;上海 = 150
            DataFrame byCity = wide.groupBy("城市").agg(java.util.Map.of("金额", "sum"));
            assertThat(lookupLong(byCity, "城市", "北京", "金额_sum")).isEqualTo(600L);
            assertThat(lookupLong(byCity, "城市", "上海", "金额_sum")).isEqualTo(150L);
            assertThat(byCity.rowCount()).isEqualTo(2);
            // SQL 对照版:JOIN + GROUP BY 一条(与 merge+groupBy 差分;限定名仅用于 ON)
            DataFrame sqlCity = Jian.sql("""
                    SELECT 城市, sum(金额) AS total FROM ${f}
                    JOIN ${d} ON f.客户 = d.客户 GROUP BY 城市
                    """, fact, dim);
            assertThat(lookupLong(sqlCity, "城市", "北京", "total")).isEqualTo(600L);
            assertThat(lookupLong(sqlCity, "城市", "上海", "total")).isEqualTo(150L);
        }
    }

    /** 按键值列查聚合结果行,取目标数值列(小工具,场景内多处复用)。 */
    private static long lookupLong(DataFrame df, String keyCol, Object key, String valCol) {
        for (int r = 0; r < df.rowCount(); r++)
            if (df.getColumn(keyCol).get(r).equals(key))
                return ((Number) df.getColumn(valCol).get(r)).longValue();
        throw new AssertionError("键不存在: " + key);
    }
}
