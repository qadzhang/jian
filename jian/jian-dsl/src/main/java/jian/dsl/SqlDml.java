package jian.dsl;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;

import java.util.*;
import java.util.regex.*;

// ┌─ What : SqlDml —— L3 SQL DML(INSERT/UPDATE/DELETE)内存执行器
// │  Why  : L7 修复(2026-08-09)—— 返回新 DataFrame,不破坏不可变契约
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
        "(?is)INSERT\\s+INTO\\s+\\$\\{(\\w+)}\\s*\\(([\\w\\s,]+?)\\)\\s*VALUES\\s*\\((.+?)\\)");

    private static DmlResult executeInsert(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        Matcher m = INSERT_PATTERN.matcher(sql);
        if (!m.find()) {
            throw new IllegalArgumentException("INSERT 语法错误,期望:INSERT INTO ${t} (cols) VALUES (vals)");
        }
        String tableName = m.group(1);
        String[] cols = m.group(2).split("\\s*,\\s*");
        String[] valStrs = m.group(3).split("\\s*,\\s*");
        if (cols.length != valStrs.length) {
            throw new IllegalArgumentException("INSERT 列数 " + cols.length + " ≠ 值数 " + valStrs.length);
        }
        DataFrame target = bindings.getOrDefault(tableName, defaultDf);
        if (target == null) throw new IllegalArgumentException("INSERT 目标表 ${" + tableName + "} 未绑定");
        // 解析值
        Object[] values = new Object[valStrs.length];
        for (int i = 0; i < valStrs.length; i++) {
            values[i] = parseLiteral(valStrs[i].trim());
        }
        // 追加行:构建新 Object[][]
        int n = target.rowCount();
        Object[][] newData = new Object[n + 1][];
        for (int r = 0; r < n; r++) newData[r] = target.getRow(r);
        // 新行:按 target 列顺序填,只有 cols 中的列填入 values,其余 null
        Object[] newRow = new Object[target.columnCount()];
        List<String> targetCols = target.columnNames();
        for (int i = 0; i < cols.length; i++) {
            int idx = targetCols.indexOf(cols[i].trim());
            if (idx >= 0) newRow[idx] = values[i];
        }
        newData[n] = newRow;
        // 重建 DataFrame
        Object[] schParts = new Object[targetCols.size() * 2];
        for (int i = 0; i < targetCols.size(); i++) {
            schParts[i * 2] = targetCols.get(i);
            schParts[i * 2 + 1] = target.dtypes().get(i);
        }
        DataFrame result = DataFrame.of(Schema.of(schParts), newData);
        return new DmlResult(result, 1);  // INSERT 新增 1 行
    }

    // ======================== UPDATE ========================

    private static final Pattern UPDATE_PATTERN = Pattern.compile(
        "(?is)UPDATE\\s+\\$\\{(\\w+)}\\s+SET\\s+(\\w+)\\s*=\\s*(.+?)\\s+WHERE\\s+(.+)");

    private static DmlResult executeUpdate(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        Matcher m = UPDATE_PATTERN.matcher(sql);
        if (!m.find()) {
            throw new IllegalArgumentException("UPDATE 语法错误,期望:UPDATE ${t} SET col = value WHERE cond");
        }
        String tableName = m.group(1);
        String setCol = m.group(2);
        String setValue = m.group(3).trim();
        String where = m.group(4).trim();
        DataFrame target = bindings.getOrDefault(tableName, defaultDf);
        if (target == null) throw new IllegalArgumentException("UPDATE 目标表 ${" + tableName + "} 未绑定");
        Object newValue = parseLiteral(setValue);
        // 找匹配 WHERE 的行
        // ┌─ What : 逐行求值 WHERE,收集匹配行下标
        // │  Why  : 不能用 target.query(where) 一次到位,因为要精确知道哪些行匹配(用于 SET 替换);
        //         逐行 slice+query 是为了拿到逐行的 boolean mask
        // │  Who  : executeUpdate
        // │  When : UPDATE 执行时
        // │  How  : ① slice 出单行 df → ② query(where) 试匹配 → ③ 异常立即抛(不静默吞)
        // │         —— L8 修复(2026-08-09,与 AI agent2 第二轮审查共识):
        //            原 catch(Exception){/* skip */ } 会把 WHERE 求值失败的行静默视为不匹配,
        //            导致 UPDATE 漏更新 / DELETE 漏删除,用户完全不知情;现改为抛 IAE(WHERE 原文进消息)
        Set<Integer> matchRows = new HashSet<>();
        boolean[] mask = new boolean[target.rowCount()];
        for (int i = 0; i < target.rowCount(); i++) {
            DataFrame oneRow = target.slice(i, i + 1);
            try {
                mask[i] = oneRow.query(where).rowCount() > 0;
                if (mask[i]) matchRows.add(i);
            } catch (Exception e) {
                // 不静默吞:WHERE 对某行求值失败 = 用户 SQL 写错或类型不匹配,
                // 必须报错不能让该行被悄悄跳过(否则 UPDATE 漏更新 / DELETE 漏删除)。
                // 数据走向:oneRow(第 i 行)→ query(where) → 异常 e → 包装为 IAE 抛出(保留 cause)
                throw new IllegalArgumentException(
                    "DML WHERE 子句对第 " + i + " 行求值失败(WHERE: " + where + "): " + e.getMessage(), e);
            }
        }
        // 重建:对匹配行替换 setCol 值
        int colIdx = target.columnIndex(setCol);
        if (colIdx < 0) throw new IllegalArgumentException("UPDATE SET 列 \"" + setCol + "\" 不存在");
        Object[][] newData = new Object[target.rowCount()][];
        for (int r = 0; r < target.rowCount(); r++) {
            newData[r] = target.getRow(r);
            if (matchRows.contains(r)) {
                newData[r][colIdx] = newValue;
            }
        }
        Object[] schParts = new Object[target.columnCount() * 2];
        List<String> tCols = target.columnNames();
        for (int i = 0; i < tCols.size(); i++) {
            schParts[i * 2] = tCols.get(i);
            schParts[i * 2 + 1] = target.dtypes().get(i);
        }
        DataFrame result = DataFrame.of(Schema.of(schParts), newData);
        return new DmlResult(result, matchRows.size());
    }

    // ======================== DELETE ========================

    private static final Pattern DELETE_PATTERN = Pattern.compile(
        "(?is)DELETE\\s+FROM\\s+\\$\\{(\\w+)}\\s+WHERE\\s+(.+)");

    private static DmlResult executeDelete(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        Matcher m = DELETE_PATTERN.matcher(sql);
        if (!m.find()) {
            throw new IllegalArgumentException("DELETE 语法错误,期望:DELETE FROM ${t} WHERE cond");
        }
        String tableName = m.group(1);
        String where = m.group(2).trim();
        DataFrame target = bindings.getOrDefault(tableName, defaultDf);
        if (target == null) throw new IllegalArgumentException("DELETE 目标表 ${" + tableName + "} 未绑定");
        // 找匹配 WHERE 的行(要删的)
        // ┌─ What : 逐行求值 WHERE,标记要删的行
        // │  Why  : 同 executeUpdate —— 要精确知道哪些行匹配 WHERE(用于 deleteMask)
        // │  Who  : executeDelete
        // │  When : DELETE 执行时
        // │  How  : 逐行 slice+query;异常立即抛(不静默吞,与 UPDATE 分支一致)
        // │         —— L8 修复(2026-08-09):原 catch{/* skip */} 会让 DELETE 漏删除,现抛 IAE
        boolean[] deleteMask = new boolean[target.rowCount()];
        int deleteCount = 0;
        for (int i = 0; i < target.rowCount(); i++) {
            DataFrame oneRow = target.slice(i, i + 1);
            try {
                deleteMask[i] = oneRow.query(where).rowCount() > 0;
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

    /** 解析字面量:'string' / number / true / false / null。 */
    private static Object parseLiteral(String lit) {
        lit = lit.trim();
        if (lit.startsWith("'") && lit.endsWith("'")) return lit.substring(1, lit.length() - 1);
        if ("null".equalsIgnoreCase(lit)) return null;
        if ("true".equalsIgnoreCase(lit)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(lit)) return Boolean.FALSE;
        try { return Double.parseDouble(lit); } catch (NumberFormatException e) { return lit; }
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
