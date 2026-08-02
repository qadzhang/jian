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
}
