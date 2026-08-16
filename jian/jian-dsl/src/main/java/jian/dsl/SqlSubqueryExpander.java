package jian.dsl;

import jian.core.DataFrame;

import java.util.Map;

// ┌─ What : SqlSubqueryExpander —— WHERE/HAVING 里的 (SELECT ...) 子查询展开器
// │  Why  : §3.1.1.1 内聚 + §3.1 行数约束:子查询展开(扫描/配平/递归执行/转字面量)
// │         是独立职责,从 SqlEngine 拆出(SqlEngine 超 600 行红线,JOIN 解析已拆
// │         SqlJoinClauseParser,本类承接第二块独立职责)
// │  Who  : SqlEngine.executeSelect(WHERE 与 HAVING 喂 Pratt 前)
// │  When : WHERE/HAVING 表达式含 "(SELECT" 时
// │  Where: jian-dsl/SqlSubqueryExpander.java
// │  How  : 数据走向:expr → 逐字符扫描(字符串字面量整段跳过)→ "(SELECT" 处括号配平
// │         抽子查询 → 递归执行(SqlEngine.execute / 集合运算路径)→ resultToLiteral
// │         按前驱 token(IN/比较符)转字面量回填 → 可喂 PrattEngine 的表达式文本。
// │         关键变量变化:
// │           - i 扫描游标;引号区内整体复制不判定子查询;
// │           - depthP 括号深度(depth>0 未闭合即 IAE);
// │           - subResult 子查询结果 DataFrame → vals 值列表 → 字面量串。
// │         逻辑路线:
// │           路径 A(遇引号)→ 整段字面量复制('' 翻倍保留,字面量内 "(SELECT" 不判定);
// │           路径 B(遇 "(SELECT")→ 配平抽子查询 → 含集合运算走 executeSetOperations
// │             (裸 UNION 残留文本会污染 WHERE),否则递归 execute(depth+1)
// │             → 结果转字面量;
// │           路径 C(其余字符)→ 原样复制。
/**
 * WHERE/HAVING 里的 (SELECT ...) 子查询展开器(SqlEngine 的独立职责拆分)。
 *
 * <p>支持形态:
 * <ul>
 *   <li>{@code col IN (SELECT col2 FROM ...)} → col IN (v1, v2, ...)</li>
 *   <li>{@code col > (SELECT max(x) FROM ...)} → col &gt; 单值(标量子查询,取首行首列)</li>
 * </ul>
 *
 * <p>深度由 depth 控制(本层 +1,&gt; 2 抛异常,规范 07 §2.3)。
 * 扫描先跳字符串字面量 —— 因为值里含 "(SELECT"(如 {@code WHERE name == 'x(SELECT y)z'})
 * 若被误判为子查询执行,会报"SELECT 必须含 FROM"。
 */
final class SqlSubqueryExpander {

    private SqlSubqueryExpander() {}

    /**
     * 展开表达式里的全部 (SELECT ...) 子查询为字面量。
     *
     * @param df DataFrame 当前主表(子查询内 this 引用与外层同源),非 null
     * @param expr String WHERE/HAVING 表达式文本,非 null
     * @param bindings Map&lt;String,DataFrame&gt; ${占位} 绑定,非 null
     * @param dialect SqlDialect SQL 方言,非 null
     * @param depth int 当前子查询深度(入口传外层 depth,本层 +1,&gt;2 抛异常)
     * @return String 全部子查询替换为字面量后的表达式文本
     * @throws IllegalArgumentException 子查询括号未闭合,或子查询执行失败
     */
    static String expand(DataFrame df, String expr, Map<String, DataFrame> bindings,
                         SqlDialect dialect, int depth) {
        // 伪代码:
        //   1. 逐字符扫描;遇单/双引号 → 整段字面量原样复制('' 翻倍保留),不做子查询判定
        //   2. 遇 "(SELECT"(词边界)→ 括号配平找匹配右括号,抽出子查询文本
        //   3. 子查询递归执行(含集合运算走 executeSetOperations),结果按前驱 token
        //      (IN/比较符)转字面量回填
        //   4. 其余字符原样复制
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '\'' || c == '"') {
                // 字符串字面量整体跳过(含 '' 翻倍转义),字面量内的 "(SELECT" 不当子查询
                char q = c;
                out.append(c);
                i++;
                while (i < expr.length()) {
                    out.append(expr.charAt(i));
                    if (expr.charAt(i) == q) {
                        if (i + 1 < expr.length() && expr.charAt(i + 1) == q) {
                            out.append(q);
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                i++;
            } else if (c == '(' && i + 6 < expr.length()
                    && expr.regionMatches(true, i + 1, "SELECT", 0, 6)) {
                // 括号配平找匹配的右括号
                int depthP = 1;
                int start = i + 1;
                int j = i + 1;
                while (j < expr.length() && depthP > 0) {
                    char cj = expr.charAt(j);
                    if (cj == '(') depthP++;
                    else if (cj == ')') { depthP--; if (depthP == 0) break; }
                    j++;
                }
                if (depthP != 0) {
                    throw new IllegalArgumentException("子查询括号未闭合:" + expr.substring(i));
                }
                String subSql = expr.substring(start, j).trim();
                // 子查询含 UNION(去重)/INTERSECT/EXCEPT/MINUS 时走集合运算路径
                // (execute 只认 UNION ALL,裸 UNION 残留文本会污染 WHERE 喂 Pratt 报
                // "无法识别的字符 '$'");UNION ALL 保持原递归路径不变
                DataFrame subResult = SqlPreprocessor.hasSetOperation(subSql)
                        ? SqlPreprocessor.executeSetOperations(df, subSql, bindings, dialect)
                        : SqlEngine.execute(df, subSql, bindings, dialect, depth + 1);
                out.append(resultToLiteral(subResult, expr, i));
                i = j + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 子查询结果 → 字面量。根据子查询前的 token(IN / 比较运算符)决定形态:
     * IN → (v1, v2, ...);其余(标量子查询)→ 首行首列单值。
     *
     * @param sub DataFrame 子查询结果
     * @param expr String 完整表达式文本(用于向前找前驱 token)
     * @param subStart int 子查询 "(SELECT" 的起始下标
     * @return String 字面量文本(空结果 → IN 给 "(null)",标量给 "null")
     */
    private static String resultToLiteral(DataFrame sub, String expr, int subStart) {
        // 向前找前一个 token
        int p = subStart - 1;
        while (p >= 0 && Character.isWhitespace(expr.charAt(p))) p--;
        int tokEnd = p + 1;
        while (p >= 0 && Character.isLetterOrDigit(expr.charAt(p))) p--;
        String prevTok = expr.substring(p + 1, tokEnd).toUpperCase();

        if (sub.rowCount() == 0 || sub.columnCount() == 0) {
            return prevTok.equals("IN") ? "(null)" : "null";
        }
        java.util.List<Object> vals = new java.util.ArrayList<>();
        for (int r = 0; r < sub.rowCount(); r++) vals.add(sub.get(r, 0));

        if (prevTok.equals("IN")) {
            StringBuilder sb = new StringBuilder("(");
            for (int k = 0; k < vals.size(); k++) {
                if (k > 0) sb.append(", ");
                sb.append(toLiteral(vals.get(k)));
            }
            return sb.append(')').toString();
        }
        // 标量子查询:取首行首列
        return toLiteral(vals.get(0));
    }

    /**
     * Java 值 → SQL/DSL 字面量(Number/Boolean 直接,String 加引号)。
     * 字符串转义用 SQL 标准单引号翻倍(''):因为反斜杠转义(\\')在
     * MySQL 8+ 默认 NO_BACKSLASH_ESCAPES 下会静默失效导致注入,
     * 而 '' 翻倍是 ANSI SQL 标准,所有数据库方言通用。
     *
     * @param v Object 单元格值,可 null
     * @return String 字面量文本
     */
    private static String toLiteral(Object v) {
        if (v == null) return "null";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return v.toString();
        return "'" + v.toString().replace("'", "''") + "'";
    }
}
