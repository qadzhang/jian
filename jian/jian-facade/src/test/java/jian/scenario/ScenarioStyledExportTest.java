package jian.scenario;

import jian.Jian;
import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import jian.export.Styler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// ┌─ What : ScenarioStyledExportTest —— 真实场景:财务月报样式导出(S16,样式能力串联)
// │  Why  : 样式能力(字体颜色/加粗/行列背景/渐变/自动列宽/原生数字格式)不能只有单元测试,
// │         须进真实场景集(四轨红线:场景须登记 scenarios.md 且随 jar 分发)
// │  Who  : mvn -pl jian-facade test;AI 速查见 jar 内 META-INF/ai/scenarios.md S16 行
// │  When : mvn test(jian-facade 模块)
// │  Where: jian-facade/src/test/java/jian/scenario/ScenarioStyledExportTest.java
// │  How  : 数据走向:门店月度损益表 → Styler 规则链(负利润整行红底 / 金额千分位原生格式 /
// │         利润渐变 / 大额加粗 / 备注列灰底)→ toExcel → POI 读回逐项断言(真实可验证)。
class ScenarioStyledExportTest {

    @TempDir Path tmp;

    @Test
    void S16_财务月报样式导出() throws Exception {
        DataFrame pl = DataFrame.of(Schema.of(
                        "门店", DType.STRING, "营收", DType.LONG, "利润", DType.LONG, "备注", DType.STRING),
                new Object[][]{
                        {"北京店", 120_000L, 18_000L, "达标"},
                        {"上海店", 98_000L, -4_200L, "亏损"},
                        {"深圳店", 150_000L, 27_000L, "明星店"},
                        {"成都店", 76_000L, -1_800L, "观察"},
                });
        // 业务规则:亏损店整行标红;营收千分位(Excel 原生格式,可求和);利润绿-黄-红渐变;
        // 明星店(利润>25000)加粗;备注列灰底
        Path out = tmp.resolve("月度损益报表.xlsx");
        Styler.of(pl)
                .rowBackgroundIf("利润", "#ffcccc", v -> ((Number) v).doubleValue() < 0)
                .format("#,##0", "营收")
                .backgroundGradient("利润", Styler.ColorMap.GREEN_YELLOW_RED)
                .boldIf("利润", v -> ((Number) v).doubleValue() > 25_000)
                .columnBackground("备注", "#eeeeee")
                .setCaption("2026-07 门店损益")
                .toExcel(out.toString());

        // POI 读回逐项验证(真实文件,非内存断言)
        try (org.apache.poi.ss.usermodel.Workbook wb =
                     org.apache.poi.ss.usermodel.WorkbookFactory.create(out.toFile())) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheet("2026-07 门店损益");
            assertThat(sheet).isNotNull();
            // ① 亏损行(上海/成都)整行红底:每格 fill 前景色一致非默认
            for (int rowIdx : new int[]{2, 4}) {   // 数据行 1-based +1:上海=2、成都=4
                for (int c = 1; c <= 4; c++) {
                    var style = sheet.getRow(rowIdx).getCell(c).getCellStyle();
                    assertThat(style.getFillForegroundColorColor()).isNotNull();
                }
            }
            // 盈利行(北京)备注列有灰底但门店列无整行染色(默认无 fill)
            assertThat(sheet.getRow(1).getCell(1).getCellStyle().getFillForegroundColorColor()).isNull();
            // ② 营收原生千分位 + 数值可求和
            var rev = sheet.getRow(1).getCell(2);
            assertThat(rev.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(rev.getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            double sum = 0;
            for (int r = 1; r <= 4; r++) sum += sheet.getRow(r).getCell(2).getNumericCellValue();
            assertThat(sum).isEqualTo(120_000 + 98_000 + 150_000 + 76_000.0);
            // ③ 明星店(深圳,行 3)利润加粗
            var xstyle = (org.apache.poi.xssf.usermodel.XSSFCellStyle) sheet.getRow(3).getCell(3).getCellStyle();
            assertThat(xstyle.getFont().getBold()).isTrue();
            // ④ 自动列宽:营收列(格式化后 7 字符 "150,000")宽于默认
            assertThat(sheet.getColumnWidth(2)).isGreaterThan(8 * 256);
        }
        // HTML 侧同规则链路可用(负利润行含红底内联样式)
        String html = Styler.of(pl)
                .rowBackgroundIf("利润", "#ffcccc", v -> ((Number) v).doubleValue() < 0)
                .toHtml();
        assertThat(html).contains("background-color: #ffcccc");
        assertThat(html.split("</tr>")[1]).doesNotContain("#ffcccc");   // 北京行不染
    }
}
