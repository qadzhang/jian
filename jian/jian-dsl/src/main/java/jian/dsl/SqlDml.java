package jian.dsl;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;

import java.util.*;
import java.util.regex.*;

// ┌─ What : SqlDml —— L3 SQL DML(INSERT/UPDATE/DELETE)内存执行器
// │  Why  : 返回新 DataFrame,不破坏不可变契约
// │  Who  : 由 SqlRegexEngine.update 委托
// │  When : 用户经 SqlEngines.current().update() 调用 DML
// │  Where: jian-dsl/SqlDml.java
// │  How  : 数据走向:
// │           INSERT INTO ${t} (cols) VALUES (v1,v2,...) → 解析值 → 追加行 → 返回新 df
// │           UPDATE ${t} SET col = expr WHERE cond → 逐行求值 → 替换 → 返回新 df
// │           DELETE FROM ${t} WHERE cond → filter 保留 !cond → 返回新 df
/**
 * L3 SQL DML 内存执行器(返回新 DataFrame,不修改原表)。
 *
 * <p>支持:
 * <ul>
 *   <li>INSERT INTO ${t} (cols) VALUES (v1, v2, ...)</li>
 *   <li>UPDATE ${t} SET col = value WHERE cond(简化:value 为字面量)</li>
 *   <li>DELETE FROM ${t} WHERE cond</li>
 * </ul>
 */
public final class SqlDml {

    private SqlDml() {}

    /**
     * 执行 DML,返回受影响行数(INSERT=新增行数,UPDATE=修改行数,DELETE=删除行数)。
     * <p>注:内存 DataFrame 不可变,调用方需自行接收 execute 返回的新 DataFrame。
     * @param defaultDf DataFrame 目标表
     * @param sql       String INSERT/UPDATE/DELETE
     * @param bindings  Map<String,DataFrame> 占位绑定
     * @return int 受影响行数
     */
    public static DmlResult execute(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        String upper = sql.trim().toUpperCase();
        if (upper.startsWith("INSERT")) return executeInsert(defaultDf, sql, bindings);
        if (upper.startsWith("UPDATE")) return executeUpdate(defaultDf, sql, bindings);
        if (upper.startsWith("DELETE")) return executeDelete(defaultDf, sql, bindings);
        throw new IllegalArgumentException("SqlDml 仅支持 INSERT/UPDATE/DELETE,实际:" + upper);
    }

    // ======================== INSERT ========================

    private static final Pattern INSERT_PATTERN = Pattern.compile(
        "(?is)INSERT\\s+INTO\\s+\\$\\{(\\w+)}\\s*\\(([\\w\\s,]+?)\\)\\s*VALUES\\s+(.+)",
        Pattern.UNICODE_CHARACTER_CLASS);  // UCC:列名支持中文

    private static DmlResult executeInsert(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        Matcher m = INSERT_PATTERN.matcher(sql);
        if (!m.find()) {
            throw new IllegalArgumentException("INSERT 语法错误,期望:INSERT INTO ${t} (cols) VALUES (vals)");
        }
        String tableName = m.group(1);
        String[] cols = m.group(2).split("\\s*,\\s*");
        // 支持多行 VALUES (...), (...) —— 引号感知切分每行
        List<String> rowStrs = splitValuesRows(m.group(3).trim());
        if (rowStrs.isEmpty()) {
            throw new IllegalArgumentException("INSERT VALUES 缺少数据行");
        }
        DataFrame target = bindings.getOrDefault(tableName, defaultDf);
        if (target == null) throw new IllegalArgumentException("INSERT 目标表 ${" + tableName + "} 未绑定");
        List<String> targetCols = target.columnNames();
        // 解析每行值
        Object[][] newRows = new Object[rowStrs.size()][];
        for (int ri = 0; ri < rowStrs.size(); ri++) {
            // 引号感知切分值('foo,bar' 不被切成 'foo 与 bar' 两个值)
            List<String> valStrs = splitValuesRow(rowStrs.get(ri));
            if (valStrs.size() != cols.length) {
                throw new IllegalArgumentException("INSERT 第 " + (ri + 1) + " 行列数 " + valStrs.size()
                    + " ≠ 声明列数 " + cols.length);
            }
            Object[] values = new Object[valStrs.size()];
            for (int i = 0; i < valStrs.size(); i++) {
                values[i] = parseLiteral(valStrs.get(i).trim());
            }
            // 按 target 列顺序填,只有 cols 中的列填入 values,其余 null
            Object[] newRow = new Object[target.columnCount()];
            for (int i = 0; i < cols.length; i++) {
                int idx = targetCols.indexOf(cols[i].trim());
                if (idx >= 0) newRow[idx] = values[i];
                else throw new IllegalArgumentException("INSERT 列 \"" + cols[i].trim() + "\" 在目标表中不存在");
            }
            newRows[ri] = newRow;
        }
        // 追加行:原行 + 新行
        int n = target.rowCount();
        Object[][] allData = new Object[n + newRows.length][];
        for (int r = 0; r < n; r++) allData[r] = target.getRow(r);
        System.arraycopy(newRows, 0, allData, n, newRows.length);
        // 重建 DataFrame
        Object[] schParts = new Object[targetCols.size() * 2];
        for (int i = 0; i < targetCols.size(); i++) {
            schParts[i * 2] = targetCols.get(i);
            schParts[i * 2 + 1] = target.dtypes().get(i);
        }
        DataFrame result = DataFrame.of(Schema.of(schParts), allData);
        return new DmlResult(result, newRows.length);  // INSERT 新增行数
    }

    // ======================== UPDATE ========================

    private static final Pattern UPDATE_PATTERN = Pattern.compile(
        "(?is)UPDATE\\s+\\$\\{(\\w+)}\\s+SET\\s+(.+?)\\s+WHERE\\s+(.+)",
        Pattern.UNICODE_CHARACTER_CLASS);  // UCC:占位名支持中文

    private static DmlResult executeUpdate(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        Matcher m = UPDATE_PATTERN.matcher(sql);
        if (!m.find()) {
            throw new IllegalArgumentException("UPDATE 语法错误,期望:UPDATE ${t} SET col = value WHERE cond");
        }
        String tableName = m.group(1);
        // 支持多列 SET(col1=v1, col2=v2),值按引号感知切分
        Map<String, Object> setMap = new LinkedHashMap<>();
        // SET 右值支持表达式(可引用同表列,逐行求值)——
        // 因为把右值一律当字面量会让 "SET salary = salary * 2" 把整串当值存储/解析崩
        // (对齐 SQLite UPDATE SET col = col * 2),所以非引号/非数字/非关键字的右值按 Pratt 表达式处理。
        Map<String, PrattEngine.Node> setExprMap = new LinkedHashMap<>();
        for (String item : splitSetItems(m.group(2).trim())) {
            int eq = item.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("UPDATE SET 项格式错(期望 col = value):" + item);
            String col = item.substring(0, eq).trim();
            // UCC:SET 列名允许中文等 Unicode 标识符
            if (!col.matches("(?U)\\w+")) throw new IllegalArgumentException("UPDATE SET 列名非法:" + col);
            String rawVal = item.substring(eq + 1).trim();
            Object lit = parseLiteral(rawVal);
            boolean quotedStr = rawVal.startsWith("'") && rawVal.endsWith("'") && rawVal.length() >= 2;
            if (lit instanceof String && !quotedStr) {
                // 裸回退字符串 = 表达式候选(如 salary * 2 / salary + 100)
                try {
                    setExprMap.put(col, PrattEngine.parse(SqlPreprocessor.normalizeSqlExpr(rawVal)));
                } catch (RuntimeException pe) {
                    throw new IllegalArgumentException("UPDATE SET '" + col + "' 的值既非字面量也非可解析表达式: "
                            + rawVal + "(" + pe.getMessage() + ")");
                }
            } else {
                setMap.put(col, lit);
            }
        }
        if (setMap.isEmpty() && setExprMap.isEmpty()) throw new IllegalArgumentException("UPDATE SET 不能为空");
        for (String col : setMap.keySet()) {
            if (targetColumnIndex(bindings.getOrDefault(tableName, defaultDf), col) < 0) {
                throw new IllegalArgumentException("UPDATE SET 列 \"" + col + "\" 不存在");
            }
        }
        String where = m.group(3).trim();
        DataFrame target = bindings.getOrDefault(tableName, defaultDf);
        if (target == null) throw new IllegalArgumentException("UPDATE 目标表 ${" + tableName + "} 未绑定");
        // WHERE 表达式只解析一次,逐行 eval(每行 slice+query 重解析会让 1M 行 = 1M 次 parse)
        // 先做 SQL→DSL 运算符归一化(裸 = / <> / 反引号)
        PrattEngine.Node ast = PrattEngine.parse(SqlPreprocessor.normalizeSqlExpr(where));
        List<String> cols = target.columnNames();
        boolean[] matchMask = new boolean[target.rowCount()];
        for (int i = 0; i < target.rowCount(); i++) {
            try {
                matchMask[i] = toBool(ast.eval(new PrattEngine.RowBinding(target, cols, i)));
            } catch (Exception e) {
                // 不静默吞:WHERE 对某行求值失败 = 用户 SQL 写错或类型不匹配,
                // 必须报错不能让该行被悄悄跳过(否则 UPDATE 漏更新 / DELETE 漏删除)
                throw new IllegalArgumentException(
                    "DML WHERE 子句对第 " + i + " 行求值失败(WHERE: " + where + "): " + e.getMessage(), e);
            }
        }
        // 重建:对匹配行替换 set 列值
        Object[][] newData = new Object[target.rowCount()][];
        int affected = 0;
        for (int r = 0; r < target.rowCount(); r++) {
            newData[r] = target.getRow(r);
            if (matchMask[r]) {
                for (Map.Entry<String, Object> e : setMap.entrySet()) {
                    newData[r][target.columnIndex(e.getKey())] = e.getValue();
                }
                // 表达式 SET 值逐行求值(引用该行其它列)
                for (Map.Entry<String, PrattEngine.Node> e : setExprMap.entrySet()) {
                    try {
                        newData[r][target.columnIndex(e.getKey())] =
                                e.getValue().eval(new PrattEngine.RowBinding(target, cols, r));
                    } catch (RuntimeException ee) {
                        throw new IllegalArgumentException("UPDATE SET '" + e.getKey()
                                + "' 表达式对第 " + r + " 行求值失败: " + ee.getMessage(), ee);
                    }
                }
                affected++;
            }
        }
        Object[] schParts = new Object[target.columnCount() * 2];
        List<String> tCols = target.columnNames();
        for (int i = 0; i < tCols.size(); i++) {
            schParts[i * 2] = tCols.get(i);
            schParts[i * 2 + 1] = target.dtypes().get(i);
        }
        DataFrame result = DataFrame.of(Schema.of(schParts), newData);
        return new DmlResult(result, affected);
    }

    // ======================== DELETE ========================

    private static final Pattern DELETE_PATTERN = Pattern.compile(
        "(?is)DELETE\\s+FROM\\s+\\$\\{(\\w+)}\\s+WHERE\\s+(.+)",
        Pattern.UNICODE_CHARACTER_CLASS);  // UCC:占位名支持中文

    private static DmlResult executeDelete(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        Matcher m = DELETE_PATTERN.matcher(sql);
        if (!m.find()) {
            throw new IllegalArgumentException("DELETE 语法错误,期望:DELETE FROM ${t} WHERE cond");
        }
        String tableName = m.group(1);
        String where = m.group(2).trim();
        DataFrame target = bindings.getOrDefault(tableName, defaultDf);
        if (target == null) throw new IllegalArgumentException("DELETE 目标表 ${" + tableName + "} 未绑定");
        // WHERE 单次解析,逐行 eval;先做 SQL→DSL 运算符归一化(裸 = / <> / 反引号)
        PrattEngine.Node ast = PrattEngine.parse(SqlPreprocessor.normalizeSqlExpr(where));
        List<String> cols = target.columnNames();
        boolean[] deleteMask = new boolean[target.rowCount()];
        int deleteCount = 0;
        for (int i = 0; i < target.rowCount(); i++) {
            try {
                deleteMask[i] = toBool(ast.eval(new PrattEngine.RowBinding(target, cols, i)));
                if (deleteMask[i]) deleteCount++;
            } catch (Exception e) {
                // 不静默吞:WHERE 求值失败必须报错,不能让该行被悄悄保留(DELETE 漏删除)
                throw new IllegalArgumentException(
                    "DML WHERE 子句对第 " + i + " 行求值失败(WHERE: " + where + "): " + e.getMessage(), e);
            }
        }
        // 保留 !deleteMask 的行
        boolean[] keepMask = new boolean[target.rowCount()];
        for (int i = 0; i < keepMask.length; i++) keepMask[i] = !deleteMask[i];
        DataFrame result = target.filter(keepMask);
        return new DmlResult(result, deleteCount);
    }

    // ======================== 辅助 ========================

    /** 解析字面量:'string' / number / true / false / null。
     *  字符串按 ANSI SQL 标准,'' 翻倍 = 字面量单引号。 */
    private static Object parseLiteral(String lit) {
        lit = lit.trim();
        if (lit.startsWith("'") && lit.endsWith("'")) {
            return lit.substring(1, lit.length() - 1).replace("''", "'");
        }
        if ("null".equalsIgnoreCase(lit)) return null;
        if ("true".equalsIgnoreCase(lit)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(lit)) return Boolean.FALSE;
        try { return Double.parseDouble(lit); } catch (NumberFormatException e) { return lit; }
    }

    /** 列是否存在(目标表为 null 时返回 -1)。 */
    private static int targetColumnIndex(DataFrame target, String col) {
        return target == null ? -1 : target.columnIndex(col);
    }

    /** WHERE 求值结果 → boolean(与 PrattEngine.toBool 语义一致)。 */
    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        // 因为数值隐式当布尔(非零即 true)会掩盖逻辑 bug,且会让 "DELETE WHERE age"
        // 静默删除 age!=0 的行、而同表达式走 query 抛异常(两路径语义相反),
        // 所以数值不做隐式布尔转换 —— 与 query 三入口(PrattEngine/SimpleQueryParser/DataFrame.cmp)对齐;
        if (v instanceof Number n) {
            throw new IllegalArgumentException("逻辑表达式要求布尔操作数,实际 " + v.getClass().getSimpleName()
                    + " (" + v + ");判空请用 is null、判零请显式 == 0(对齐 pandas,与 query 引擎一致)");
        }
        throw new IllegalArgumentException("WHERE 表达式结果非布尔:" + v);
    }

    /** 切分 VALUES 段为多行:'(1,2), (3,4)' → ['(1,2)', '(3,4)'](引号感知)。 */
    private static List<String> splitValuesRows(String valuesPart) {
        List<String> rows = new ArrayList<>();
        int depth = 0, start = 0;
        int n = valuesPart.length();
        for (int i = 0; i < n; i++) {
            char c = valuesPart.charAt(i);
            if (c == '\'') {
                i = skipString(valuesPart, i);
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) { rows.add(valuesPart.substring(start, i + 1).trim()); start = i + 1; }
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("INSERT VALUES 缺少括号数据行:" + valuesPart);
        }
        return rows;
    }

    /** 切分一行 VALUES 的值:去外层括号 + 引号感知逗号切分('foo,bar' 保持一个值)。 */
    private static List<String> splitValuesRow(String row) {
        String inner = row.trim();
        if (inner.startsWith("(") && inner.endsWith(")")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        List<String> vals = new ArrayList<>();
        int depth = 0, start = 0;
        int n = inner.length();
        for (int i = 0; i < n; i++) {
            char c = inner.charAt(i);
            if (c == '\'') { i = skipString(inner, i); continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) { vals.add(inner.substring(start, i)); start = i + 1; }
        }
        vals.add(inner.substring(start));
        return vals;
    }

    /** 切分 SET 部分为 'col = value' 项(引号感知,支持多列)。 */
    private static List<String> splitSetItems(String setPart) {
        List<String> items = new ArrayList<>();
        int start = 0;
        int n = setPart.length();
        for (int i = 0; i < n; i++) {
            char c = setPart.charAt(i);
            if (c == '\'') { i = skipString(setPart, i); continue; }
            if (c == ',' && i > 0) { items.add(setPart.substring(start, i)); start = i + 1; }
        }
        items.add(setPart.substring(start));
        return items;
    }

    /** 从引号起点跳到字符串末尾(含 '' 翻倍),返回引号闭合位置。 */
    private static int skipString(String s, int quoteIdx) {
        int i = quoteIdx + 1;
        int n = s.length();
        while (i < n) {
            if (s.charAt(i) == '\'') {
                if (i + 1 < n && s.charAt(i + 1) == '\'') { i += 2; continue; }
                return i;
            }
            i++;
        }
        return n - 1;  // 未闭合,返回末尾(后续 parseLiteral 会处理)
    }

    /** DML 执行结果:新 DataFrame + 受影响行数。 */
    public static final class DmlResult {
        public final DataFrame result;
        public final int affectedRows;
        public DmlResult(DataFrame result, int affectedRows) {
            this.result = result;
            this.affectedRows = affectedRows;
        }
    }
}
