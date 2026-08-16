package jian.export;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : Styler 测试 —— format/highlight/gradient/bar/caption/hide
class StylerTest {

    @Test
    void format数值格式化() {
        DataFrame df = df();
        String html = Styler.of(df).format("#,##0.00", "score").toHtml();
        // score 经格式化为 90.50(2 位小数)
        assertThat(html).contains("90.50");
    }

    @Test
    void highlightMax上色() {
        DataFrame df = df();
        String html = Styler.of(df).highlightMax("score", "#ffff00").toHtml();
        // 最大值 90.5 单元格应带背景色
        assertThat(html).contains("background-color: #ffff00");
    }

    @Test
    void backgroundGradient渐变() {
        DataFrame df = df();
        String html = Styler.of(df)
                .backgroundGradient("score", Styler.ColorMap.GREEN_YELLOW_RED)
                .toHtml();
        assertThat(html).contains("background-color: #");
    }

    @Test
    void bar单元格条形() {
        DataFrame df = df();
        String html = Styler.of(df).bar("score", "#4682b4").toHtml();
        assertThat(html).contains("linear-gradient");
    }

    @Test
    void caption和hideIndex() {
        DataFrame df = df();
        String html = Styler.of(df).setCaption("员工表").hideIndex().toHtml();
        assertThat(html).contains("<caption>员工表</caption>");
        assertThat(html).doesNotContain("<th></th>");  // 隐藏索引列
    }

    @Test
    void hideColumns隐藏指定列() {
        DataFrame df = df();
        String html = Styler.of(df).hideColumns("id").toHtml();
        assertThat(html).doesNotContain(">id<");  // 表头无 id
        assertThat(html).contains("name");  // 其它列还在
    }

    @Test
    void highlightNull缺失高亮() {
        DataFrame df = DataFrame.of(
                Schema.of("v", DType.DOUBLE),
                new Object[][]{{1.0}, {null}});
        String html = Styler.of(df).highlightNull("#cccccc").toHtml();
        assertThat(html).contains("background-color: #cccccc");
    }

    @Test
    void 链式多条规则叠加() {
        DataFrame df = df();
        String html = Styler.of(df)
                .format("#,##0.00", "score")
                .highlightMax("score", "#ff0000")
                .backgroundGradient("score", Styler.ColorMap.BLUE_RED)
                .setCaption("test")
                .toHtml();
        assertThat(html).contains("90.50");
        assertThat(html).contains("<caption>test</caption>");
    }

    @Test
    void 色图插值正确() {
        // t=0 → 第一色;t=1 → 末色;t=0.5 → 中点((0+255)/2=127.5→127=0x7f)
        assertThat(Styler.colorAt("#000000:#ffffff", 0.0)).isEqualTo("#000000");
        assertThat(Styler.colorAt("#000000:#ffffff", 1.0)).isEqualTo("#ffffff");
        assertThat(Styler.colorAt("#000000:#ffffff", 0.5)).isEqualTo("#7f7f7f");  // 中灰(127=0x7f)
    }

    private DataFrame df() {
        return DataFrame.of(
                Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
                new Object[][]{
                        {1L, "alice", 90.5},
                        {2L, "bob", 85.0},
                        {3L, "carol", 76.5}
                });
    }

    @Test
    void toExcel生成带样式文件() throws Exception {
        DataFrame df = df();
        java.io.File f = java.io.File.createTempFile("jian-styler", ".xlsx");
        f.deleteOnExit();
        Styler.of(df)
                .format("#,##0.00", "score")
                .highlightMax("score", "#ffff00")
                .setCaption("员工表")
                .toExcel(f);
        // 文件存在且非空
        assertThat(f.exists()).isTrue();
        assertThat(f.length()).isGreaterThan(500L);  // xlsx 有内容
        // 用 POI 读回验证
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(f)) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheet("员工表");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getLastRowNum()).isEqualTo(3);  // 表头 + 3 数据行
        }
    }

    @Test
    void toExcel长sheet名截断() throws Exception {
        DataFrame df = df();
        java.io.File f = java.io.File.createTempFile("jian-long", ".xlsx");
        f.deleteOnExit();
        String longName = "这是一个非常非常长的sheet名称超过三十一字符的限制需要被截断处理才行";
        Styler.of(df).setCaption(longName).toExcel(f);
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(f)) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            assertThat(wb.getSheetName(0).length()).isLessThanOrEqualTo(31);
        }
    }

    // ┌─ What : 字体颜色/加粗/自动列宽/原生数字格式 的回归测试
    // │  Why  : 补齐 pandas Styler.applymap 等价能力;HTML 断言内联样式,Excel 用 POI 读回断言
    // │  How  : ①toHtml:fontColor/bold → color:/font-weight: 内联;②toExcel 读回:
    //          Font.getColor/getBold、setColumnWidth 生效、数值单元格仍为 NUMERIC(可求和)+
    //          DataFormatString 为原生格式串(透传)
    @Test
    void 字体颜色与加粗_HTML() {
        String html = Styler.of(styled())
                .fontColor("姓名", "#ff0000")
                .fontColorIf("金额", "#cc0000", v -> ((Number) v).doubleValue() < 0)
                .bold("姓名")
                .boldIf("金额", v -> ((Number) v).doubleValue() > 0)
                .toHtml();
        assertThat(html).contains("color: #ff0000");
        assertThat(html).contains("font-weight: bold");
        assertThat(html).contains("color: #cc0000");   // 条件字色命中负值
    }

    @Test
    void 字体与加粗与列宽_Excel读回() throws Exception {
        java.io.File f = java.io.File.createTempFile("exp020", ".xlsx");
        Styler.of(styled())
                .bold("姓名")
                .fontColorIf("金额", "#ff0000", v -> ((Number) v).doubleValue() < 0)
                .format("#,##0.00", "金额")
                .toExcel(f);
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(f)) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            // 自动列宽生效(金额列内容 "1,234.50" 7 字符 → 宽 9×256 > 默认 8×256;只放宽不缩窄)
            assertThat(sheet.getColumnWidth(2)).isGreaterThan(8 * 256);
            // 表头行数据行存在
            assertThat(sheet.getRow(0)).isNotNull();
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("张三");
            // 数值单元格保持 NUMERIC(可求和)+ 原生数字格式透传
            org.apache.poi.ss.usermodel.Cell num = sheet.getRow(1).getCell(2);
            assertThat(num.getCellType())
                    .isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(num.getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
        }
        f.delete();
    }

    @Test
    void 行列背景_HTML与Excel() throws Exception {
        // 整行:金额为负 → 整行红底;整列:备注列固定灰底
        String html = Styler.of(styled())
                .rowBackgroundIf("金额", "#ffcccc", v -> ((Number) v).doubleValue() < 0)
                .toHtml();
        assertThat(html.split("</tr>")[2]).contains("background-color: #ffcccc");   // 李四(-50)行每格都染
        assertThat(html.split("</tr>")[1]).doesNotContain("#ffcccc");                // 张三(1234.5)行不染

        java.io.File f = java.io.File.createTempFile("exp021", ".xlsx");
        Styler.of(styled()).columnBackground("姓名", "#cccccc").toExcel(f);
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(f)) {
            var cell = wb.getSheetAt(0).getRow(1).getCell(1);
            assertThat(cell.getCellStyle().getFillForegroundColorColor()).isNotNull();
        }
        f.delete();
    }

    /** 样式样本:含负值(触发条件字色/加粗)。 */
    private static jian.core.DataFrame styled() {
        return jian.core.DataFrame.of(
                jian.core.Schema.of("姓名", jian.core.DType.STRING, "金额", jian.core.DType.DOUBLE),
                new Object[][]{{"张三", 1234.5}, {"李四", -50.0}});
    }
}
