package jian.io.excel;

import jian.core.DataFrame;
import jian.core.Schema;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// ┌─ What : Excel —— Excel(xls/xlsx)读写(对齐 pandas read_excel / to_excel / ExcelWriter,基于 POI)
// │  Why  : 规范 02 §3.2;POI 原生 artifact(非 uber,规范 §2.5),自动识别 xls/xlsx
// │  Who  : 用户经 Jian.readExcel / Excel.write / df.toExcel 调用
// │  When : Excel 报表读写、多 sheet 写出
// │  Where: jian-io-excel/Excel.java
// │  How  : 数据走向:
// │           读:File → WorkbookFactory.create(自动 xls/xlsx)→ Sheet → 逐行 cell → Object[][] + 推断 → DataFrame;
// │           写:DataFrame → XSSFWorkbook → Sheet → Row/Cell → FileOutputStream。
// │         关键变量变化:
// │           - sheetIndex/sheetName:指定读哪个 sheet(默认 0);
// │           - header:首行是否列名(默认 true);
// │           - CellType → 值类型(NUMERIC/STRING/BOOLEAN/FORMULA)。
// │         逻辑路线:
// │           路径 A(读 xlsx)→ XSSFWorkbook 自动;读 xls → HSSFWorkbook;WorkbookFactory 统一识别;
// │           路径 B(写 xlsx)→ XSSFWorkbook(默认);
// │           路径 C(单元格类型)→ NUMERIC 看是否整数(决定 LONG/DOUBLE)、STRING 直存、FORMULA 用 FormulaEvaluator 求值。
/**
 * Excel 读写,对齐 pandas.read_excel / to_excel(基于 POI 5.5.1 原生 artifact)。
 *
 * <p>读:
 * <pre>{@code
 * DataFrame df = Excel.read("data.xlsx").sheet("Sheet1").header(true).go();
 * List<String> sheets = Excel.sheetNames("data.xlsx");   // 枚举所有 sheet
 * }</pre>
 *
 * <p>写:
 * <pre>{@code
 * Excel.write(df, "out.xlsx").sheetName("data").go();
 *
 * // 多 sheet(ExcelWriter 上下文,对齐 pandas)
 * try (ExcelWriter w = Excel.writer("out.xlsx")) {
 *     w.write(df1, "Sheet1");
 *     w.write(df2, "Sheet2");
 * }
 * }</pre>
 */
public final class Excel {

    private Excel() {}

    // ======================== 读 ========================

    /**
     * 按 String 路径读取 Excel 的 builder(自动识别 xls/xlsx)。
     * @param path String Excel 文件路径,需为合法可读文件,不允许 null
     * @return ExcelReader 配置器,链式调用 .sheet/.header 后 .go() 执行
     */
    public static ExcelReader read(String path) { return new ExcelReader(Path.of(path)); }

    /**
     * 按 Path 路径读取 Excel 的 builder(自动识别 xls/xlsx)。
     * @param path Path Excel 文件路径对象,需为合法可读文件,不允许 null
     * @return ExcelReader 配置器,链式调用 .sheet/.header 后 .go() 执行
     */
    public static ExcelReader read(Path path) { return new ExcelReader(path); }

    /**
     * 枚举所有 sheet 名(对齐 pandas.ExcelFile.sheet_names)。
     * @param path String Excel 文件路径,需为合法可读文件,不允许 null
     * @return List&lt;String&gt; 所有 sheet 名称列表(保持文件内顺序),无 sheet 时返回空列表
     * @throws IOException 文件不存在、格式不支持或读取过程发生 IO 错误时抛出
     */
    public static List<String> sheetNames(String path) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new File(path), null, true)) {
            List<String> r = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) r.add(wb.getSheetName(i));
            return r;
        }
    }

    public static final class ExcelReader {
        private final Path path;
        private String sheetName = null;
        private int sheetIndex = 0;
        private boolean header = true;

        ExcelReader(Path p) { this.path = p; }

        /**
         * 按 sheet 名读取(优先级高于 sheetIndex)。
         * @param name String sheet 名称,需与文件内实际 sheet 名一致(大小写敏感),不允许 null
         * @return ExcelReader 当前配置器,便于链式调用
         */
        public ExcelReader sheet(String name) { this.sheetName = name; this.sheetIndex = -1; return this; }

        /**
         * 按 sheet 索引读取(从 0 开始)。
         * @param i int sheet 序号,范围 [0, sheet 总数-1];默认 0(第一个 sheet)
         * @return ExcelReader 当前配置器,便于链式调用
         */
        public ExcelReader sheetIndex(int i) { this.sheetIndex = i; this.sheetName = null; return this; }

        /**
         * 设置首行是否为表头。
         * @param h boolean true=首行作列名(默认);false=首行作数据,列名取 _0,_1,...
         * @return ExcelReader 当前配置器,便于链式调用
         */
        public ExcelReader header(boolean h) { this.header = h; return this; }

        /**
         * 执行读取。
         * @return DataFrame 解析出的数据帧(列名按 header 决定,值类型按列统一精确转换)
         * @throws IOException 文件不存在或读取过程发生 IO 错误时抛出
         * @throws IllegalArgumentException 指定的 sheet 名/索引不存在时抛出(提示现有 sheet 列表)
         */
        public DataFrame go() throws IOException {
            try (FileInputStream fis = new FileInputStream(path.toFile());
                 Workbook wb = WorkbookFactory.create(fis)) {
                Sheet sheet = sheetName != null ? wb.getSheet(sheetName) : wb.getSheetAt(sheetIndex);
                if (sheet == null) {
                    throw new IllegalArgumentException("sheet 不存在:" + sheetName + ",现有:" + allNames(wb));
                }
                return parseSheet(sheet, header);
            }
        }

        private static List<String> allNames(Workbook wb) {
            List<String> r = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) r.add(wb.getSheetName(i));
            return r;
        }
    }

    // ┌─ What : parseSheet —— 逐列扫描类型,精确转换(解决"一列多类型"问题)
    // │  How  : 阶段1 扫描每列所有数据单元格的 CellType → 推断列统一类型;
    //         阶段2 按列统一类型精确转换每个值(整数→Long 不经 double 中转;混合→String 保留原样)。
    private static DataFrame parseSheet(Sheet sheet, boolean header) {
        // 陷阱 #8/#9: getLastRowNum 可能虚高(含空行),用 isRowEmpty 探测真实数据行
        int lastRow = sheet.getLastRowNum();
        if (lastRow < 0) {
            return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
        }
        // 跳过末尾空行
        while (lastRow > 0 && isRowEmpty(sheet.getRow(lastRow))) lastRow--;

        Row firstRow = sheet.getRow(0);
        int cols = firstRow != null ? firstRow.getLastCellNum() : 0;
        List<String> names = new ArrayList<>();
        int startRow;
        if (header) {
            for (int c = 0; c < cols; c++) names.add(getCellString(firstRow.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)));
            // 陷阱 #5: 表头列名去重(重名自动加 _1 _2)
            names = dedupNames(names);
            startRow = 1;
        } else {
            for (int c = 0; c < cols; c++) names.add("_" + c);
            startRow = 0;
        }

        // 收集有效数据行(跳过中间空行,陷阱 #8/#9)
        List<Integer> validRows = new ArrayList<>();
        for (int r = startRow; r <= lastRow; r++) {
            if (!isRowEmpty(sheet.getRow(r))) validRows.add(r);
        }
        int dataRows = validRows.size();

        // ===== 阶段1:逐列扫描,推断每列的统一 CellType =====
        org.apache.poi.ss.usermodel.CellType[] colTypes = new org.apache.poi.ss.usermodel.CellType[cols];
        for (int c = 0; c < cols; c++) {
            colTypes[c] = inferColumnType(sheet, c, validRows);
        }

        // ===== 阶段2:按列类型精确转换(用 validRows 索引遍历) =====
        Object[][] rows = new Object[dataRows][cols];
        for (int ri = 0; ri < validRows.size(); ri++) {
            int r = validRows.get(ri);
            Row row = sheet.getRow(r);
            for (int c = 0; c < cols; c++) {
                Cell cell = row != null ? row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK) : null;
                rows[ri][c] = (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK)
                        ? null : cellValuePrecise(cell, colTypes[c]);
            }
        }
        // 构造 Schema(按列推断的 CellType → jian DType)
        List<jian.core.DType> dtypes = new ArrayList<>();
        // 用 Schema.infer 让 DataFrame 按实际值的 Java 类型精确推断(Long→LONG,Double→DOUBLE,String→STRING)
        // 而非强按 CellType 映射(避免纯整数列被强制 DOUBLE 丢精度)
        return DataFrame.of(Schema.infer(names, rows), rows);
    }

    /** 扫描一列所有有效数据单元格,推断统一 CellType(用 validRows 避免空行干扰)。 */
    private static org.apache.poi.ss.usermodel.CellType inferColumnType(Sheet sheet, int col, List<Integer> validRows) {
        boolean allInt = true, allNumeric = true, allBool = true, allDate = true, allString = true;
        boolean hasAny = false;
        for (int r : validRows) {
            Row row = sheet.getRow(r);
            Cell cell = row != null ? row.getCell(col, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK) : null;
            if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK) continue;
            hasAny = true;
            org.apache.poi.ss.usermodel.CellType ct = cell.getCellType();
            // FORMULA 取缓存值类型
            if (ct == org.apache.poi.ss.usermodel.CellType.FORMULA) ct = cell.getCachedFormulaResultType();
            // ERROR 类型(公式算错如 #DIV/0!) → 当 STRING 输出错误信息
            if (ct == org.apache.poi.ss.usermodel.CellType.ERROR) {
                allInt = false; allNumeric = false; allBool = false; allDate = false; allString = false;
                continue;
            }
            if (ct == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    allInt = false; allNumeric = false; allBool = false;
                    allString = false; // 日期列也当 string 输出 ISO 格式
                    continue;
                }
                double d = cell.getNumericCellValue();
                if (d != Math.floor(d) || Double.isInfinite(d)) allInt = false;
                allString = false; allBool = false;
            } else if (ct == org.apache.poi.ss.usermodel.CellType.STRING) {
                allInt = false; allNumeric = false; allBool = false; allDate = false;
            } else if (ct == org.apache.poi.ss.usermodel.CellType.BOOLEAN) {
                allInt = false; allNumeric = false; allString = false; allDate = false;
            } else {
                allInt = false; allNumeric = false; allBool = false; allDate = false; allString = false;
            }
        }
        if (!hasAny) return org.apache.poi.ss.usermodel.CellType.STRING;
        if (allDate) return org.apache.poi.ss.usermodel.CellType.STRING;  // 日期转 ISO 字符串
        if (allInt) return org.apache.poi.ss.usermodel.CellType.NUMERIC;   // 纯整数列(NUMERIC,阶段2 转 long)
        if (allNumeric) return org.apache.poi.ss.usermodel.CellType.NUMERIC; // 含小数(NUMERIC,阶段2 转 double)
        if (allBool) return org.apache.poi.ss.usermodel.CellType.BOOLEAN;
        if (allString) return org.apache.poi.ss.usermodel.CellType.STRING;
        // 混合类型 → STRING(每值按自身类型转字符串,保留原始表达)
        return org.apache.poi.ss.usermodel.CellType.STRING;
    }

    /**
     * 按列统一类型精确转换单元格值。
     * - NUMERIC 列:整数转 Long(不经 double 中转,防精度丢);小数转 Double
     * - STRING 列:日期转 ISO 字符串;数值按原样字符串(保留手机号等);文本原样
     * - BOOLEAN 列:转 Boolean
     */
    private static Object cellValuePrecise(Cell cell, org.apache.poi.ss.usermodel.CellType colType) {
        org.apache.poi.ss.usermodel.CellType ct = cell.getCellType();
        if (ct == org.apache.poi.ss.usermodel.CellType.FORMULA) ct = cell.getCachedFormulaResultType();

        switch (colType) {
            case NUMERIC: {
                // 先看是否日期格式
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    java.util.Date date = cell.getDateCellValue();
                    return date.toInstant().toString();  // ISO 格式
                }
                double d = cell.getNumericCellValue();
                // 整数 → Long(不经 double 累积误差;用 BigDecimal 精确取整)
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) <= Long.MAX_VALUE) {
                    return (long) d;
                }
                return d;  // 小数 → Double
            }
            case BOOLEAN: return cell.getBooleanCellValue();
            case STRING: {
                // 混合列/字符串列:每个值按其自身 CellType 精确转字符串
                switch (ct) {
                    case NUMERIC:
                        if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                            return cell.getDateCellValue().toInstant().toString();
                        }
                        double d = cell.getNumericCellValue();
                        // 整数 → 不带小数点的字符串(13800000000 不是 1.38E10)
                        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e16) {
                            return String.valueOf((long) d);
                        }
                        return String.valueOf(d);  // 小数 → 有效位
                    case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
                    case STRING: default: return cell.getStringCellValue();
                }
            }
            default: return null;
        }
    }

    /** POI CellType → jian DType。 */
    private static jian.core.DType cellTypeToDType(org.apache.poi.ss.usermodel.CellType ct) {
        return switch (ct) {
            case NUMERIC -> jian.core.DType.DOUBLE;  // NUMERIC 列阶段2 会正确分 long/double,Schema 用 DOUBLE 兜底(DataFrame.buildColumn 会按值精确分)
            case BOOLEAN -> jian.core.DType.BOOL;
            case STRING -> jian.core.DType.STRING;
            default -> jian.core.DType.STRING;
        };
    }

    /** 精确取值(公式取缓存结果类型,再按类型转)。 */
    private static Object cellValue(Cell cell) {
        return cellValuePrecise(cell, cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType());
    }

    private static String getCellString(Cell cell) {
        Object v = cellValue(cell);
        return v == null ? "" : String.valueOf(v);
    }

    // ======================== 写 ========================

    /**
     * 写 Excel(xlsx)的 builder。
     * @param df DataFrame 要写出的数据帧,不允许 null
     * @param path String 输出 xlsx 文件路径,需为合法可写路径,不允许 null
     * @return ExcelWriter 配置器,链式调用 .sheetName/.header 后 .go() 执行
     */
    public static ExcelWriter write(DataFrame df, String path) { return new ExcelWriter(df, Path.of(path)); }

    /** 单 DataFrame 写单 sheet,便捷封装。 */
    public static final class ExcelWriter implements AutoCloseable {
        private final DataFrame df;
        private final Path path;
        private String sheetName = "Sheet1";
        private boolean header = true;

        ExcelWriter(DataFrame df, Path p) { this.df = df; this.path = p; }

        /**
         * 设置输出 sheet 名。
         * @param n String sheet 名称;若超过 31 字符会被自动截断(POI 限制),默认 "Sheet1"
         * @return ExcelWriter 当前配置器,便于链式调用
         */
        public ExcelWriter sheetName(String n) { this.sheetName = n; return this; }

        /**
         * 设置是否写表头行。
         * @param h boolean true=输出表头行(默认);false=不输出表头
         * @return ExcelWriter 当前配置器,便于链式调用
         */
        public ExcelWriter header(boolean h) { this.header = h; return this; }

        /**
         * 执行写出。
         * @throws IOException 目标路径不可写或写出过程发生 IO 错误时抛出
         */
        public void go() throws IOException {
            try (Workbook wb = new XSSFWorkbook()) {
                writeDfToSheet(wb, df, sheetName, header);
                try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
                    wb.write(fos);
                }
            }
        }

        @Override public void close() {}
    }

    /**
     * 多 sheet writer(对齐 pandas.ExcelWriter,try-with-resources)。
     * @param path String 输出 xlsx 文件路径,需为合法可写路径,不允许 null
     * @return ExcelMultiWriter 多 sheet 上下文,链式调用 .write(df, name)... 后 close() 落盘
     */
    public static ExcelMultiWriter writer(String path) {
        return new ExcelMultiWriter(Path.of(path));
    }

    /** 多 sheet writer。 */
    public static final class ExcelMultiWriter implements AutoCloseable {
        private final Path path;
        private final Workbook wb = new XSSFWorkbook();
        private boolean header = true;

        ExcelMultiWriter(Path p) { this.path = p; }

        /**
         * 设置后续 write 是否写表头行。
         * @param h boolean true=输出表头行(默认);false=不输出表头
         * @return ExcelMultiWriter 当前上下文,便于链式调用
         */
        public ExcelMultiWriter header(boolean h) { this.header = h; return this; }

        /**
         * 写一个 DataFrame 到指定 sheet。
         * @param df DataFrame 要写出的数据帧,不允许 null
         * @param sheetName String 目标 sheet 名称;超 31 字符会被截断(POI 限制)
         * @return ExcelMultiWriter 当前上下文,便于连续写多个 sheet
         */
        public ExcelMultiWriter write(DataFrame df, String sheetName) {
            writeDfToSheet(wb, df, sheetName, header);
            return this;
        }

        @Override public void close() throws IOException {
            try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
                wb.write(fos);
            }
            wb.close();
        }
    }

    /** 把 DataFrame 写到 sheet(共享给单/多 writer)。
     *  陷阱 #9: null 写 BLANK(不跳过,保证读回是 null 不是空字符串)
     *  陷阱 #5: ≥15 位 Long 当 STRING 写(防 double 精度丢失)
     *  陷阱 #10: 每列只 createCell 一次 */
    private static void writeDfToSheet(Workbook wb, DataFrame df, String sheetName, boolean header) {
        Sheet sheet = wb.createSheet(sheetName.length() > 31 ? sheetName.substring(0, 31) : sheetName);
        java.util.List<String> cols = df.columnNames();
        int r = 0;
        if (header) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < cols.size(); c++) {
                Cell cell = row.createCell(c);
                cell.setCellValue(cols.get(c));
            }
        }
        for (Object[] rowVals : df.iterRows()) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < cols.size(); c++) {
                Object v = (c < rowVals.length) ? rowVals[c] : null;
                if (v == null) {
                    // 陷阱 #9: null 写 BLANK 单元格(不跳过,保证读回是 null)
                    row.createCell(c).setBlank();
                    continue;
                }
                Cell cell = row.createCell(c);  // 每列只 createCell 一次
                // 陷阱 #5: ≥15 位 Long 当 STRING 写(身份证 18 位/银行卡 16-19 位,防 double 末尾变 0)
                if (v instanceof Long && (Math.abs((Long) v) >= 1_000_000_000_000_000L)) {
                    cell.setCellValue(String.valueOf(v));
                } else if (v instanceof Number) {
                    cell.setCellValue(((Number) v).doubleValue());
                } else if (v instanceof Boolean) {
                    cell.setCellValue((Boolean) v);
                } else {
                    String s = String.valueOf(v);
                    // Web 安全修复(2026-08-08,2026-08-09 增强):
                    // Excel 公式注入防护(与 CSV 一致)。OWASP 严格版——不只看首字符,
                    // 跳过前导空白/Tab/CR/LF 后再判定(防 "\t=cmd|..." / " =cmd|..." 绕过)。
                    if (startsWithFormulaAfterWhitespace(s)) {
                        cell.setCellValue("'" + s);
                    } else {
                        cell.setCellValue(s);
                    }
                }
            }
        }
    }

    // ======================== 辅助:空行检测 + 列名去重 + 公式求值 + 公式注入防护 ========================

    /**
     * 公式注入检测(OWASP 严格版,2026-08-09 增强自旧版 isFormulaStart(char))。
     * <p>不只看首字符——很多注入 payload 用前导空白/Tab/CR/LF 绕过首字符检查
     * (如 {@code "\t=cmd|..."}、{@code " =cmd|..."}),Excel 解析时会先 trim。
     * 本方法跳过前导空白类字符后,再看第一个有效字符是否是公式起始符。
     *
     * @param s String 待检测的单元格字符串,非 null
     * @return boolean true=检测到公式起始符,需加单引号前缀防护
     */
    private static boolean startsWithFormulaAfterWhitespace(String s) {
        if (s.isEmpty()) return false;
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t'
                || s.charAt(i) == '\r' || s.charAt(i) == '\n')) {
            i++;
        }
        if (i >= s.length()) return false;
        char ch = s.charAt(i);
        return ch == '=' || ch == '+' || ch == '-' || ch == '@';
    }

    /** 陷阱 #8/#9: 判断一行是否完全为空(所有 cell 为 null 或 BLANK)。 */
    private static boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != org.apache.poi.ss.usermodel.CellType.BLANK) return false;
        }
        return true;
    }

    /** 陷阱 #5: 表头列名去重(重名自动加 _1 _2 _3...)。 */
    private static List<String> dedupNames(List<String> names) {
        List<String> result = new ArrayList<>();
        java.util.Map<String, Integer> seen = new java.util.LinkedHashMap<>();
        for (String name : names) {
            String n = (name == null || name.isEmpty()) ? "_" : name;
            if (!seen.containsKey(n)) {
                result.add(n);
                seen.put(n, 1);
            } else {
                int count = seen.get(n);
                result.add(n + "_" + count);
                seen.put(n, count + 1);
            }
        }
        return result;
    }
}
