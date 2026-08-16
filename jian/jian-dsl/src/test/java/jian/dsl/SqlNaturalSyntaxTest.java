package jian.dsl;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : SQL 自然语法回归测试(中文列名/别名 + SQL 标准运算符 = / <>)
// │  Why  : 用户实测反馈——用中文列名(如"类别")跑 df.sql / Jian.sql 时,SELECT 层正则
// │         (\w+ 不匹配中文)抛"SELECT 无法识别的列/表达式";WHERE 里的 SQL 标准等号
// │         "=" 直接被 Pratt 词法器拒绝("无法识别的字符 '='")。两处叠加导致
// │         "读 Excel 按中文类别列拆分"这种最常见任务完全跑不通,属重大可用性 BUG
// │  Who  : 由 mvn -pl jian-dsl test 执行;覆盖 SqlEngine/SqlPreprocessor 的标识符与运算符归一化
// │  When : mvn test(jian-dsl 模块),永久回归
// │  Where: jian-dsl/src/test/java/jian/dsl/SqlNaturalSyntaxTest.java
// │  How  : 数据走向:构造含中文列名的 DataFrame → df.sql()/Dsl.sql() 执行各形态 SQL
// │         → 断言行数/列名/单元格值。覆盖面:
// │           ① SELECT 中文列 / SELECT * + WHERE 中文条件
// │           ② 中文别名(AS 分类)/ 中文列作 GROUP BY、ORDER BY 键
// │           ③ WHERE 单等号 = / <> / AND 大写 / 反引号标识符
// │           ④ CASE WHEN 中文条件(表达式列路径)
class SqlNaturalSyntaxTest {

    /** 三行样本:列名全中文,模拟 Excel 读入的真实形态。 */
    private static DataFrame cn() {
        return DataFrame.of(Schema.of(
                        "类别", DType.STRING, "名称", DType.STRING, "金额", DType.LONG),
                new Object[][]{
                        {"食品", "苹果", 10L},
                        {"文具", "铅笔", 5L},
                        {"食品", "面包", 8L},
                });
    }

    // ======================== 中文列名(SELECT 层,\w+ 正则覆盖中文)========================

    @Test
    void SELECT中文列名() {
        DataFrame r = Dsl.sql("SELECT 类别, 金额 FROM ${t}", cn());
        assertThat(r.columnNames()).containsExactly("类别", "金额");
        assertThat(r.rowCount()).isEqualTo(3);
    }

    @Test
    void SELECT星号加WHERE中文条件() {
        DataFrame r = Dsl.sql("SELECT * FROM ${t} WHERE 类别 == '食品'", cn());
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getColumn("名称").get(0)).isEqualTo("苹果");
    }

    @Test
    void SELECT中文列AS中文别名() {
        DataFrame r = Dsl.sql("SELECT 类别 AS 分类 FROM ${t}", cn());
        assertThat(r.columnNames()).containsExactly("分类");
    }

    @Test
    void GROUP_BY中文列聚合() {
        DataFrame r = Dsl.sql("SELECT 类别, sum(金额) AS 合计 FROM ${t} GROUP BY 类别", cn());
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.getColumn("类别").get(0)).isIn("食品", "文具");
    }

    @Test
    void ORDER_BY中文列() {
        DataFrame r = Dsl.sql("SELECT 名称 FROM ${t} ORDER BY 金额 DESC", cn());
        assertThat(r.getColumn("名称").get(0)).isEqualTo("苹果");
    }

    @Test
    void 聚合列参数为中文() {
        DataFrame r = Dsl.sql("SELECT sum(金额) FROM ${t}", cn());
        assertThat(r.rowCount()).isEqualTo(1);
    }

    // ======================== SQL 标准运算符(WHERE 层,= / <> 归一化)========================

    @Test
    void WHERE单等号() {
        DataFrame r = Dsl.sql("SELECT * FROM ${t} WHERE 类别 = '文具'", cn());
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getColumn("名称").get(0)).isEqualTo("铅笔");
    }

    @Test
    void WHERE单等号数值与AND大写() {
        DataFrame r = Dsl.sql("SELECT * FROM ${t} WHERE 类别 = '食品' AND 金额 > 9", cn());
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getColumn("名称").get(0)).isEqualTo("苹果");
    }

    @Test
    void WHERE不等于尖括号() {
        DataFrame r = Dsl.sql("SELECT * FROM ${t} WHERE 类别 <> '食品'", cn());
        assertThat(r.rowCount()).isEqualTo(1);
    }

    @Test
    void WHERE反引号标识符() {
        DataFrame r = Dsl.sql("SELECT * FROM ${t} WHERE `类别` = '食品'", cn());
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void CASE_WHEN中文条件() {
        DataFrame r = Dsl.sql(
                "SELECT 名称, CASE WHEN 金额 >= 10 THEN '大额' ELSE '小额' END AS 档位 FROM ${t}", cn());
        assertThat(r.columnNames()).containsExactly("名称", "档位");
        assertThat(r.getColumn("档位").get(0)).isEqualTo("大额");
        assertThat(r.getColumn("档位").get(1)).isEqualTo("小额");
    }

    @Test
    void 实例入口df_sql中文与单等号() {
        DataFrame r = cn().sql("SELECT * FROM this WHERE 类别 = '食品'");
        assertThat(r.rowCount()).isEqualTo(2);
    }

    @Test
    void HAVING单等号中文条件() {
        DataFrame r = Dsl.sql(
                "SELECT 类别, sum(金额) AS 合计 FROM ${t} GROUP BY 类别 HAVING 合计 = 5", cn());
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getColumn("类别").get(0)).isEqualTo("文具");
    }
}
