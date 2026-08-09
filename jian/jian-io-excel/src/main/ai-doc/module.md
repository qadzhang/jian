# jian-io-excel

## 基本信息
- **library**: jian
- **entryClass**: jian.io.excel.Excel
- **deps**: jian-core;Apache POI 5.5.1(XSSF + HSSF)

## 摘要
Excel(.xls/.xlsx)读写,对齐 pandas.read_excel / to_excel / ExcelWriter;POI WorkbookFactory 自动识别新旧格式,FORMULA 取缓存值,内置 Excel 公式注入防护。

## 能力
- 读 Excel.read(path):sheet(index/name)、header(首行作列名)、自动推断类型(NUMERIC 整数→LONG/小数→DOUBLE、STRING、BOOLEAN)
- FORMULA 单元格:取 `getCachedFormulaResultType()` 的缓存值;ERROR 类型(#DIV/0! 等)按错误信息 STRING 输出
- Excel.sheetNames(path):枚举全部 sheet 名
- 单 sheet 写 Excel.write(df, path):sheetName/header 可配
- 多 sheet 写 Excel.writer(path)(try-with-resources,对齐 pandas.ExcelWriter):`w.write(df1,"S1"); w.write(df2,"S2")`
- Excel 公式注入防护(OWASP,与 CSV 一致):`= + - @` 开头字符串加单引号前缀

## 限制
- 读时不执行公式计算,仅取 Excel 已缓存的公式结果(未缓存时数值列可能为空)
- 写出仅 xlsx(XSSF),不写 .xls(HSSF 写出未实现)
- 不支持单元格样式/合并单元格/图表等 POI 高级特性(只做表格数据读写)

## 快速上手
```java
import jian.io.excel.Excel;

DataFrame df = Excel.read("data.xlsx").sheet("Sheet1").header(true).go();
java.util.List<String> sheets = Excel.sheetNames("data.xlsx");

Excel.write(df, "out.xlsx").sheetName("data").go();

// 多 sheet
try (Excel.ExcelMultiWriter w = Excel.writer("out.xlsx")) {
    w.write(df1, "Sheet1");
    w.write(df2, "Sheet2");
}
```
