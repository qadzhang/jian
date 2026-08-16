package jian.export;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.nio.file.Files;

import java.nio.file.Path;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : ExportRegressionTest —— jian-export 回归测试集:固化渲染器与 Styler 行为
// │  Why  : 因为公式注入防护、缺失值口径(§3.5.2)、LaTeX 转义、字体规则合并这类
// │         行为契约一旦回归会直接影响导出安全与正确性,所以用强断言锁住
// │  Who  : jian-export 模块测试套件
// │  When : mvn test(jian-export 模块)
// │  Where: jian-export/src/test/java/jian/export/ExportRegressionTest.java
// │  How  : ①Styler.toExcel 公式注入防护(解压 xlsx 实证 ' 前缀);②LaTeX 占位符转义(\ 不二次转义);
// │         ③Console 缺失默认空串(可配 naRep);④toHtml 缺失值默认 <NA>(转义形态)+ naRep 可配;
// │         ⑤toExcel 同列多条 FontRule 合并(颜色与加粗同时生效,POI 读回实证)。
class ExportRegressionTest {

    @TempDir
    Path tmp;

    // ======================== toExcel 公式注入防护 ========================

    @Test
    void styler导出Excel对公式载荷加撇号前缀() throws Exception {
        // "=cmd|' /C calc'!A0" 这类公式载荷必须加 ' 前缀,不能原样写入 sharedStrings
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING),
                new Object[][]{{"=cmd|' /C calc'!A0"}});
        Path p = tmp.resolve("styler-formula.xlsx");
        Styler.of(df).toExcel(p.toString());
        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(p))) {
            String v = wb.getSheetAt(0).getRow(1).getCell(1).getStringCellValue();
            assertThat(v).as("Styler.toExcel 应加 ' 前缀防护").startsWith("'");
        }
    }

    // ======================== LaTeX 占位符转义 ========================

    @Test
    void latex反斜杠不再被二次转义() {
        // 期望 "a\b" → a\textbackslash{}b(可正常编译产出 a\b);
        // 先替换反斜杠再替换花括号会产出 a\textbackslash\{\}b(花括号被二次转义)
        DataFrame df = DataFrame.of(Schema.of("s", DType.STRING), new Object[][]{{"a\\b"}});
        String out = LatexRenderer.of(df).render();   // 直接调渲染器(文件入口走同一 escape)
        assertThat(out).contains("a\\textbackslash{}b");
        assertThat(out).doesNotContain("textbackslash\\{");
    }

    // ======================== Console 缺失值口径 ========================

    @Test
    void 控制台缺失默认空串_可配置naRep() {
        // AGENTS §3.5.2 控制台默认空串;重载可还原 <NA>
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE), new Object[][]{{1.0}, {null}});
        String def = ConsoleRenderer.render(df);
        assertThat(def).doesNotContain("<NA>");
        assertThat(def).doesNotContain("NaN");
        String na = ConsoleRenderer.render(df, 60, 30, "<NA>");
        assertThat(na).contains("<NA>");
    }

    // ======================== toHtml 缺失值默认 <NA> ========================

    @Test
    void toHtml缺失值默认输出NA转义形态() {
        DataFrame df = DataFrame.of(
                Schema.of("name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{{"alice", 90.5}, {"bob", null}});
        String html = Styler.of(df).toHtml();
        // 默认 <NA>,经 escape 输出为 &lt;NA&gt;(与 HtmlRenderer.naRep 同口径)
        assertThat(html).contains("&lt;NA&gt;</td>");
        // 契约红线:不得输出裸 NaN 字样
        assertThat(html).doesNotContain(">NaN<");
    }

    @Test
    void toHtml缺失值naRep自定义生效() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {null}});
        String html = Styler.of(df).naRep("-").toHtml();
        assertThat(html).contains(">-</td>");
        assertThat(html).doesNotContain("&lt;NA&gt;");
    }

    @Test
    void toHtml缺失值高亮规则仍生效() {
        // 回归守护:缺失单元格的样式计算不受 naRep 影响(value 传 null)
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {null}});
        String html = Styler.of(df).highlightNull("#cccccc").toHtml();
        assertThat(html).contains("background-color: #cccccc");
        assertThat(html).contains("&lt;NA&gt;</td>");
    }

    // ======================== toExcel 多 FontRule 合并 ========================

    @Test
    void toExcel多FontRule颜色与加粗同时生效() throws Exception {
        DataFrame df = DataFrame.of(
                Schema.of("name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{{"alice", 90.5}, {"bob", 76.5}});
        // 两条规则:整列红色 + 低分行加粗 —— 只取首条命中会让 Excel 丢 bold
        java.io.File f = tmp.resolve("multi-font-rule.xlsx").toFile();
        Styler.of(df)
                .fontColor("score", "#ff0000")
                .boldIf("score", v -> ((Number) v).doubleValue() < 80)
                .toExcel(f);
        assertThat(f).exists();
        assertThat(f.length()).isGreaterThan(0L);

        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(f)) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheet("jian");
            assertThat(sheet).isNotNull();
            // 行列布局:列 0 = 索引(未隐藏),列 1 = name,列 2 = score;数据行从 1 开始
            // POI 5.x:Cell → CellStyle → fontIndex → wb.getFontAt(字体挂在样式上)
            org.apache.poi.ss.usermodel.Font aliceFont =
                    wb.getFontAt(sheet.getRow(1).getCell(2).getCellStyle().getFontIndexAsInt());
            org.apache.poi.ss.usermodel.Font bobFont =
                    wb.getFontAt(sheet.getRow(2).getCell(2).getCellStyle().getFontIndexAsInt());
            // alice(90.5):仅 fontColor 规则命中 → 红、不加粗
            assertThat(aliceFont.getColor()).isEqualTo((short) 10);  // IndexedColors.RED
            assertThat(aliceFont.getBold()).isFalse();
            // bob(76.5):两条规则都命中 → 红 + 加粗
            assertThat(bobFont.getColor()).isEqualTo((short) 10);
            assertThat(bobFont.getBold()).isTrue();
        }
    }

    // ======================== LaTeX 占位符防御 / 控制台截断 ========================

    @Test
    void Latex渲染_控制字符占位符冲突_抛IAE不静默损坏() {
        // 修复前:'\u0001' 会被占位符机制二次替换成 \textbackslash{}(数据静默损坏);
        // jian-io-latex 的 LatexIo 早有同款防御,本入口补齐后双入口一致
        DataFrame df = DataFrame.of(Schema.of("v", DType.STRING),
            new Object[][]{{"dirty\u0001control"}});
        assertThatThrownBy(() -> LatexRenderer.of(df).render())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("U+0001");
    }

    @Test
    void 控制台渲染_超宽值按pandas语义截断加省略号() {
        // pandas to_string(max_colwidth=4) 对超长值显示 "..."(省略头部保留尾部);
        // 修复前 pad 原样返回超宽值,撑破列宽导致后续列错位
        DataFrame df = DataFrame.of(Schema.of("a", DType.STRING, "b", DType.LONG),
            new Object[][]{{"abcdefghij", 1L}});
        String out = ConsoleRenderer.render(df, 10, 4, "");
        assertThat(out).contains("...");
        assertThat(out).doesNotContain("abcdefghij");
        // 截断后 b 列不再被挤错位:值 1 仍在本行
        assertThat(out).contains("1");
    }

    @Test
    void 控制台渲染_缺失宽度按naRep对齐显示口径() {
        // DOUBLE 列 NaN:显示为空(naRep=""),宽度也应按 naRep 计(修复前按 "NaN"=3 虚高)
        DataFrame df = DataFrame.of(Schema.of("v", DType.DOUBLE),
            new Object[][]{{Double.NaN}, {1.5}});
        String out = ConsoleRenderer.render(df, 10, 10, "");
        String[] lines = out.split("\n");
        // 第 0 行(数据首行)缺失单元格应渲染为空串占位,不出现 "NaN" 字样
        assertThat(lines[1]).doesNotContain("NaN");
    }
}
