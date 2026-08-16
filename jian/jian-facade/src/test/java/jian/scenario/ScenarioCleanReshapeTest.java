package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : ScenarioCleanReshapeTest —— 真实业务场景测试(清洗/重塑域,S3/S8/S9/S11)
// │  Why  : 同 ScenarioSalesFinanceTest(网络调研 + 可手算断言 + 每场景 ≥3 独立断言)
// │  Who  : mvn -pl jian-facade test;场景清单 AI 版见 META-INF/ai/scenarios.md
// │  When : mvn test(jian-facade 模块)
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioCleanReshapeTest.java
class ScenarioCleanReshapeTest {

    @TempDir Path tmp;

    // S3 问卷缺失值清洗:isna 计数 → dropna 完整样本 → fillna 统一填充 → 频次统计
    @Test
    void S3_问卷缺失值清洗() throws Exception {
        Path csv = tmp.resolve("survey.csv");
        java.nio.file.Files.writeString(csv, """
                姓名,年龄,城市
                张三,25,北京
                李四,,上海
                王五,30,
                赵六,28,广州
                孙七,,北京
                周八,22,上海
                """);
        DataFrame df = Jian.readCsv(csv.toString());
        // 缺失计数:年龄 2(李四/孙七)、城市 1(王五)
        DataFrame na = df.isna();
        assertThat(sumMask(na, "年龄")).isEqualTo(2);
        assertThat(sumMask(na, "城市")).isEqualTo(1);
        // 完整样本 dropna → 3 行(张三/赵六/周八);年龄均值 (25+30+28+22)/4 = 26.25
        DataFrame complete = df.dropna();
        assertThat(complete.rowCount()).isEqualTo(3);
        assertThat(complete.getColumn("姓名").toObjectArray())
                .containsExactlyInAnyOrder("张三", "赵六", "周八");
        assertThat(df.colMean("年龄")).isEqualTo(26.25);
        // 统一值填充后无任何缺失(fillna 为统一值语义,按 §3.8)
        DataFrame filled = df.fillna(0);
        assertThat(sumMask(filled.isna(), "年龄")).isZero();
        assertThat(sumMask(filled.isna(), "城市")).isZero();
        // 城市频次:北京 2 / 上海 2 / 广州 1(缺失王五不计)
        Map<Object, Integer> vc = df.colValueCounts("城市");
        assertThat(vc.get("北京")).isEqualTo(2);
        assertThat(vc.get("上海")).isEqualTo(2);
        assertThat(vc.get("广州")).isEqualTo(1);
        assertThat(vc.containsKey(null)).isFalse();   // 缺失值不进频次统计
    }

    private static int sumMask(DataFrame maskDf, String col) {
        int n = 0;
        for (Object v : maskDf.getColumn(col).toObjectArray()) if (Boolean.TRUE.equals(v)) n++;
        return n;
    }

    // S8 成绩长表透视(pivot)+ 融化(melt)roundtrip 无损
    @Test
    void S8_成绩透视与排名() {
        DataFrame long_ = DataFrame.of(Schema.of("姓名", DType.STRING, "科目", DType.STRING, "分数", DType.LONG),
                new Object[][]{{"张三", "数学", 90L}, {"张三", "英语", 70L}, {"李四", "数学", 85L}, {"李四", "英语", 95L},
                        {"王五", "数学", 60L}, {"王五", "英语", 80L}, {"赵六", "数学", 75L}, {"赵六", "英语", 75L}});
        DataFrame wide = long_.pivotTable("姓名", "科目", "分数", "mean");
        assertThat(wide.rowCount()).isEqualTo(4);   // 4 名学生
        // 科目均分:数学 (90+85+60+75)/4 = 77.5;英语 (70+95+80+75)/4 = 80
        double mathSum = 0, engSum = 0;
        for (int r = 0; r < wide.rowCount(); r++) {
            mathSum += wideColumn(wide, r, "数学");
            engSum += wideColumn(wide, r, "英语");
        }
        assertThat(mathSum).isEqualTo(310.0);
        assertThat(engSum).isEqualTo(320.0);
        assertThat(mathSum / 4).isEqualTo(77.5);
        assertThat(engSum / 4).isEqualTo(80.0);
        // 总分排名:李四 180 > 张三 160 > 赵六 150 > 王五 140
        DataFrame withTotal = wide.assign("总分", r -> wideColumn(wide, r, "数学") + wideColumn(wide, r, "英语"))
                .sortBy("总分", false);
        assertThat(withTotal.getColumn("姓名").toObjectArray())
                .containsExactly("李四", "张三", "赵六", "王五");
        // roundtrip:宽表 melt 回长表,行数守恒(8)
        DataFrame back = wide.melt(new String[]{"姓名"}, new String[]{"数学", "英语"});
        assertThat(back.rowCount()).isEqualTo(8);
    }

    private static double wideColumn(DataFrame wide, int r, String col) {
        Object v = wide.getColumn(col).get(r);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    // S9 HR 员工-部门合并:inner 剔孤儿,left 留 null,分组计数
    @Test
    void S9_员工部门合并() {
        DataFrame emp = DataFrame.of(Schema.of("姓名", DType.STRING, "部门ID", DType.LONG),
                new Object[][]{{"张", 1L}, {"李", 2L}, {"王", 3L}, {"赵", 2L}, {"钱", 9L}, {"孙", 1L}});
        DataFrame dept = DataFrame.of(Schema.of("部门ID", DType.LONG, "部门名", DType.STRING),
                new Object[][]{{1L, "研发"}, {2L, "市场"}, {3L, "财务"}, {4L, "人事"}});
        DataFrame inner = emp.merge(dept, "inner", "部门ID");
        DataFrame left = emp.merge(dept, "left", "部门ID");
        // inner:钱的部门 9 无匹配被剔 → 5 行
        assertThat(inner.rowCount()).isEqualTo(5);
        assertThat(inner.getColumn("姓名").toObjectArray()).doesNotContain("钱");
        // left:6 行,钱的部门名为 null(孤儿)
        assertThat(left.rowCount()).isEqualTo(6);
        int nullDept = 0;
        for (Object v : left.getColumn("部门名").toObjectArray()) if (v == null) nullDept++;
        assertThat(nullDept).isEqualTo(1);
        // 部门人数:研发 2 / 市场 2 / 财务 1;人事在 inner 结果中不出现(0 人)
        Map<Object, Integer> headcount = inner.colValueCounts("部门名");
        assertThat(headcount.get("研发")).isEqualTo(2);
        assertThat(headcount.get("市场")).isEqualTo(2);
        assertThat(headcount.get("财务")).isEqualTo(1);
        assertThat(headcount.containsKey("人事")).isFalse();
    }

    // S11 嵌套 JSON 订单拍平 → 一行一商品 → GMV 统计
    @Test
    void S11_嵌套JSON拍平() throws Exception {
        String json = """
                {"orders": [
                  {"orderId": "A1", "customer": {"name": "张三", "city": "北京"},
                   "items": [{"sku": "x", "qty": 2, "price": 10}, {"sku": "y", "qty": 1, "price": 30}]},
                  {"orderId": "A2", "customer": {"name": "李四", "city": "上海"},
                   "items": [{"sku": "x", "qty": 3, "price": 10}]}
                ]}""";
        // jian 的 jsonNormalize 语义:嵌套对象(customer.name)与数组(items.0.sku)全拍平为列
        DataFrame flat = Jian.jsonNormalize(json, "orders");
        assertThat(flat.rowCount()).isEqualTo(2);   // 每订单一行
        assertThat(flat.columnNames()).contains("customer.name", "items.0.sku", "items.1.sku");
        // GMV:A1 = 2×10 + 1×30 = 50;A2 = 3×10 = 30;总 80(按 items.N 列族逐行累计)
        double[] gmv = new double[flat.rowCount()];
        for (int r = 0; r < flat.rowCount(); r++) {
            double rowTotal = 0;
            for (String col : flat.columnNames()) {
                if (col.startsWith("items.") && col.endsWith(".qty")) {
                    String prefix = col.substring(0, col.length() - 4);
                    Object qty = flat.getColumn(col).get(r);
                    Object price = flat.getColumn(prefix + ".price").get(r);
                    if (qty instanceof Number q && price instanceof Number pr)
                        rowTotal += q.doubleValue() * pr.doubleValue();
                }
            }
            gmv[r] = rowTotal;
        }
        assertThat(gmv[0]).isEqualTo(50.0);
        assertThat(gmv[1]).isEqualTo(30.0);
        assertThat(gmv[0] + gmv[1]).isEqualTo(80.0);   // 总 GMV
    }
}
