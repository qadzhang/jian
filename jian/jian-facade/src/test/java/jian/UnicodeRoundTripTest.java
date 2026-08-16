package jian;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : UnicodeRoundTripTest —— 多语言/Unicode 全链路回环测试(写→读值不变,蜕变关系)
// │  Why  : ai-code-testing skill 阶段 3(蜕变测试)+ 用户专项要求"中文等各种语言 UTF 编码全面排查"。
// │         蜕变关系绕开 oracle:不预设"期望输出",只断言「写出的再读回来必须逐字符相等」+
// │         「同一数据经 SQL 过滤后行集与直接遍历一致」——期望值本身错了测试也能抓出来
// │  Who  : mvn -pl jian/jian-facade -am test;覆盖 IO(csv/json/excel/pickle/html/md)+ jian-dsl SQL
// │  When : mvn test(jian-facade 模块),永久回归
// │  Where: jian/jian-facade/src/test/java/jian/UnicodeRoundTripTest.java
// │  How  : 样本设计(每类 Unicode 风险点一行):
// │           简繁中文 / 日文 / 韩文 / 西里尔 / 阿拉伯(RTL)/ 希腊 / CJK 扩展B(增补平面,
// │           考验代理对)/ emoji(So 类,只作值不作列名)/ 组合变音(e+U+0301)/ 全角标点
// │         蜕变断言:
// │           R1(回环):write→read 后每格与原值 equals(String 逐字符,含代理对完整性)
// │           R2(行数):回环后 rowCount/columnCount 不变
// │           R3(SQL 一致):WHERE 列 = '某语言值' 的行集 == 直接遍历 equals 的行集
// │           R4(渲染):toHtml/toMarkdown 含原文(未被转义吃掉/乱码)
class UnicodeRoundTripTest {

    @TempDir Path tmp;

    /** 多语言样本:每行一个语言风险点;列名本身也含中文(考验 header 编码)。 */
    private static DataFrame poly() {
        return DataFrame.of(Schema.of("类别", DType.STRING, "名称", DType.STRING),
                new Object[][]{
                        {"简体中文", "苹果,香蕉「全角」；"},
                        {"繁體中文", "蘋果豐"},
                        {"日本語", "カテゴリー名"},
                        {"한국어", "한글 분류"},
                        {"Русский", "Категория"},
                        {"العربية", "فئة منتج"},        // RTL
                        {"Ελληνικά", "Κατηγορία"},
                        {"扩展B", "𠀀𠀁𠀂"},              // 增补平面(代理对)
                        {"emoji值", "😀🚀👨‍👩‍👧"},        // ZWJ 序列(只作值)
                        {"组合音", "é"},                 // e + U+0301 组合变音
                });
    }

    private static void assertRoundTrip(DataFrame back, DataFrame src) {
        // R2:形状不变
        assertThat(back.rowCount()).isEqualTo(src.rowCount());
        assertThat(back.columnNames()).containsExactlyElementsOf(src.columnNames());
        // R1:每格逐字符相等(emoji/代理对拆散、组合变音规范化都会在这里暴露)
        for (int r = 0; r < src.rowCount(); r++) {
            for (int c = 0; c < src.columnCount(); c++) {
                assertThat(back.getColumn(src.columnNames().get(c)).get(r))
                        .as("行%d 列%s", r, src.columnNames().get(c))
                        .isEqualTo(src.getColumn(src.columnNames().get(c)).get(r));
            }
        }
    }

    @Test
    void CSV多语言回环() throws Exception {
        Path p = tmp.resolve("多语言.csv");
        Jian.toCsv(poly(), p.toString());
        // 文件本身必须是合法 UTF-8(读回无替换字符)
        String raw = Files.readString(p);
        assertThat(raw).doesNotContain("\uFFFD").contains("扩展B");
        assertRoundTrip(Jian.readCsv(p.toString()), poly());
    }

    @Test
    void JSON多语言回环() throws Exception {
        Path p = tmp.resolve("多语言.json");
        Jian.toJson(poly(), p.toString());
        assertThat(Files.readString(p)).doesNotContain("\uFFFD").contains("日本語");
        assertRoundTrip(Jian.readJson(p.toString()), poly());
    }

    @Test
    void Excel多语言回环() throws Exception {
        Path p = tmp.resolve("多语言.xlsx");
        Jian.toExcel(poly(), p.toString());
        assertRoundTrip(Jian.readExcel(p.toString()), poly());
    }

    @Test
    void Pickle多语言回环() throws Exception {
        Path p = tmp.resolve("多语言.jpk");
        Jian.toPickle(poly(), p.toString());
        assertRoundTrip(Jian.readPickle(p.toString()), poly());
    }

    @Test
    void 渲染器多语言不乱码() throws Exception {
        // R4:HTML/Markdown 渲染保留原文
        Path html = tmp.resolve("d.html");
        Jian.toHtml(poly(), html.toString());
        assertThat(Files.readString(html)).contains("繁體中文").contains("𠀀");
        Path md = tmp.resolve("d.md");
        Jian.toMarkdown(poly(), md.toString());
        assertThat(Files.readString(md)).contains("日本語").doesNotContain("\uFFFD");
    }

    // ======================== SQL 层多语言(蜕变 R3:SQL 过滤 == 直接遍历)========================

    @Test
    void SQL多语言值过滤一致性() {
        DataFrame df = poly();
        for (int r = 0; r < df.rowCount(); r++) {
            String cat = (String) df.getColumn("类别").get(r);
            // 蜕变:SQL WHERE 的行集 == 遍历找 equals 的行集(含 emoji 值 / RTL / 增补平面值)
            // 注:cat 为测试自控常量故拼接;生产中用户输入请用 Jian.query(df,"类别==${c}",Params.of(...))
            DataFrame viaSql = Jian.sql("SELECT * FROM ${t} WHERE 类别 = '" + cat + "'", df);
            assertThat(viaSql.rowCount()).as("类别=%s", cat).isEqualTo(1);
            assertThat(viaSql.getColumn("名称").get(0)).isEqualTo(df.getColumn("名称").get(r));
        }
    }

    @Test
    void SQL多语言列名() {
        // 列名本身用日/韩/俄文 + 增补平面汉字(\w 的 UCC 边界)
        DataFrame df = DataFrame.of(Schema.of("カテゴリ", DType.STRING, "분류코드", DType.LONG,
                        "Категория", DType.DOUBLE, "𠀀列", DType.STRING),
                new Object[][]{{"A", 1L, 1.5, "代理对列名"}, {"B", 2L, 2.5, "x"}});
        DataFrame r = Jian.sql("SELECT カテゴリ, 분류코드 FROM ${t} WHERE 분류코드 > 1", df);
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.columnNames()).containsExactly("カテゴリ", "분류코드");
        DataFrame r2 = Jian.sql("SELECT `Категория` FROM ${t} WHERE `𠀀列` = '代理对列名'", df);
        assertThat(r2.rowCount()).isEqualTo(1);
    }

    @Test
    void SQL中文CTE名与中文占位名() {
        DataFrame df = poly();
        // 中文 CTE 名(裸名替换断言的 Unicode 词边界)
        DataFrame r = Jian.sql("WITH 明细 AS (SELECT * FROM ${t} WHERE 类别 <> '简体中文') SELECT 名称 FROM ${明细}", df);
        assertThat(r.rowCount()).isEqualTo(9);
        // 中文占位名 ${表}(绑定端 UCC)
        DataFrame r2 = Jian.sql("SELECT count(*) AS 总数 FROM ${表}", df);
        assertThat(r2.getColumn("总数").get(0)).isEqualTo(10L);
    }

    @Test
    void SQL中CTE名是列名子串不误替换() {
        // 定向回归:CTE 名「结」、列名「结果」——
        // 断言 (?<![\w{]) 不认中文为词字符时,会把列名「结果」里的「结」替换成 ${结} 导致列消失
        DataFrame df = DataFrame.of(Schema.of("结果", DType.LONG, "结", DType.STRING),
                new Object[][]{{1L, "a"}, {2L, "b"}});
        DataFrame r = Jian.sql("WITH 结 AS (SELECT * FROM ${t} WHERE 结果 > 1) SELECT 结 FROM ${结}", df);
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.getColumn("结").get(0)).isEqualTo("b");
    }

    @Test
    void SQL中文CASE_WHEN与表达式列() {
        DataFrame df = poly();
        DataFrame r = Jian.sql(
                "SELECT 类别, CASE WHEN 类别 = '日本語' THEN 'はい' ELSE 'いいえ' END AS 判定 FROM ${t}", df);
        assertThat(r.columnNames()).containsExactly("类别", "判定");
        List<Object> judg = new ArrayList<>();
        for (int i = 0; i < 10; i++) judg.add(r.getColumn("判定").get(i));
        assertThat(judg.get(2)).isEqualTo("はい");
        assertThat(judg.stream().filter("いいえ"::equals).count()).isEqualTo(9);
    }

    @Test
    void query表达式多语言字面量() {
        DataFrame df = poly();
        // L1 层:多语言字面量 + LIKE 前缀(韩文)+ IN(RTL 阿拉伯文)
        assertThat(Dsl_query(df, "类别 like '한%'")).isEqualTo(1);
        assertThat(Dsl_query(df, "类别 in ('العربية', 'Русский')")).isEqualTo(2);
        assertThat(Dsl_query(df, "类别 == '扩展B' && 名称 == '𠀀𠀁𠀂'")).isEqualTo(1);
    }

    /** df.query 经 DslEngine SPI;返回命中行数(测试辅助,断言行数即可)。 */
    private static int Dsl_query(DataFrame df, String expr) {
        return df.query(expr).rowCount();
    }
}
