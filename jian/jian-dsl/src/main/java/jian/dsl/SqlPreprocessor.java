package jian.dsl;

import jian.core.DataFrame;

import java.util.*;
import java.util.regex.*;

// ┌─ What : SqlPreprocessor —— L3 SQL 新语法预处理(CASE/CTE/派生表/集合运算)
// │  Why  : §3.1.1.1 内聚规则:与 SqlRegexEngine 同职能的"语法预处理扩展",拆文件避免主类超 600 行
// │         把 SqlEngine 不识别的高级语法转换为它可执行的形式(参考 JSqlParser 实现思路,自写避免其 BUG)
// │  Who  : 由 SqlRegexEngine.execute 调用
// │  When : 2026-08-09 阶段 E 落地
// │  Where: jian-dsl/SqlPreprocessor.java
// │  How  : 数据走向:
// │           ① preprocess:扫一遍 SQL,CASE → 三元;CTE → ${} 占位;派生表 → ${__derived_N__};
// │             USING → ON;CROSS JOIN → ON 1=1
// │           ② hasSetOperation:检测 UNION(去重)/INTERSECT/EXCEPT
// │           ③ executeSetOperations:处理集合运算(UNION ALL + 后处理 dropDuplicates/intersect/except)
/**
 * L3 SQL 新语法预处理器。
 *
 * <p>把高级语法转换为既有 SqlEngine 可执行的形式。所有方法纯函数,无副作用。
 */
public final class SqlPreprocessor {

    private SqlPreprocessor() {}

    /** 预处理结果容器。 */
    public static final class PreprocessedSql {
        public final String sql;
        public final Map<String, DataFrame> bindings;
        public PreprocessedSql(String sql, Map<String, DataFrame> bindings) {
            this.sql = sql;
            this.bindings = bindings;
        }
    }

    /**
     * 预处理:把 CASE/CTE/派生表/USING/CROSS JOIN 转换为 SqlEngine 可执行形式。
     * <p>注意:不处理集合运算(UNION/INTERSECT/EXCEPT),那些由 hasSetOperation + executeSetOperations 处理。
     * @param defaultDf DataFrame 主表(用于 CTE/派生表内 this 引用);可 null
     */
    public static PreprocessedSql preprocess(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        Map<String, DataFrame> expandedBindings = new LinkedHashMap<>(bindings);
        String processed = sql;

        // 1. CTE 展开(WITH name AS (subquery) SELECT ...)
        processed = expandCTE(defaultDf, processed, expandedBindings);
        // 2. CASE WHEN ... THEN ... ELSE ... END → (cond ? v1 : v2)
        processed = expandCaseWhen(processed);
        // 3. 派生表 FROM (SELECT ...) AS t → 提取子查询为 ${__derived_N__}
        processed = expandDerivedTables(defaultDf, processed, expandedBindings);
        // 4. USING(col) → ON a.col = b.col(L3:多列转 AND 链)
        processed = expandUsing(processed);
        // 5. CROSS JOIN:L4 修复 —— 直接做笛卡尔积,结果注入 binding,SQL 改为 FROM ${cross_N}
        processed = expandCrossJoinToCartesian(defaultDf, processed, expandedBindings);

        return new PreprocessedSql(processed, expandedBindings);
    }

    /** 向后兼容:defaultDf=null 的预处理(无主表场景)。 */
    public static PreprocessedSql preprocess(String sql, Map<String, DataFrame> bindings) {
        return preprocess(null, sql, bindings);
    }

    /** 检测 SQL 是否含集合运算(非 UNION ALL)。 */
    public static boolean hasSetOperation(String sql) {
        String upper = sql.toUpperCase();
        // UNION 后不跟 ALL,或 INTERSECT,或 EXCEPT(MINUS 是 Oracle 别名)
        return upper.matches("(?is).*\\bUNION\\b(?!\\s+ALL).*")
            || upper.matches("(?is).*\\bINTERSECT\\b.*")
            || upper.matches("(?is).*\\bEXCEPT\\b.*")
            || upper.matches("(?is).*\\bMINUS\\b.*");
    }

    /**
     * 执行集合运算:把 SQL 拆成多个 SELECT,逐一执行,然后做集合运算。
     * <p>支持:UNION(去重)/UNION ALL(直接)/INTERSECT/EXCEPT/MINUS。
     */
    public static DataFrame executeSetOperations(DataFrame defaultDf, String sql,
                                                  Map<String, DataFrame> bindings, SqlDialect dialect) {
        // 用正则分割(保留运算符)
        // 简化:支持单一集合运算(A UNION B,A INTERSECT B,A EXCEPT B),不支持 A UNION B INTERSECT C 链式
        Pattern setOp = Pattern.compile(
            "(?is)\\b(UNION\\s+ALL|UNION|INTERSECT|EXCEPT|MINUS)\\b");
        Matcher m = setOp.matcher(sql);
        java.util.List<String> parts = new ArrayList<>();
        java.util.List<String> ops = new ArrayList<>();
        int last = 0;
        while (m.find()) {
            parts.add(sql.substring(last, m.start()).trim());
            ops.add(m.group(1).toUpperCase().replaceAll("\\s+", " "));
            last = m.end();
        }
        parts.add(sql.substring(last).trim());
        if (parts.size() < 2) {
            // 无集合运算 → 走默认路径
            return SqlEngine.execute(defaultDf, sql, bindings, dialect);
        }

        // 依次执行每个 SELECT 部分
        DataFrame result = SqlEngine.execute(defaultDf, parts.get(0), bindings, dialect);
        for (int i = 1; i < parts.size(); i++) {
            DataFrame right = SqlEngine.execute(defaultDf, parts.get(i), bindings, dialect);
            String op = ops.get(i - 1);
            result = applySetOp(result, right, op);
        }
        return result;
    }

    /** 在两表上应用集合运算。 */
    private static DataFrame applySetOp(DataFrame left, DataFrame right, String op) {
        switch (op) {
            case "UNION ALL":
                return DataFrame.concat(java.util.List.of(left, right), 0);
            case "UNION":
                // UNION ALL + dropDuplicates
                DataFrame concatenated = DataFrame.concat(java.util.List.of(left, right), 0);
                return concatenated.dropDuplicates();
            case "INTERSECT": {
                // 两表的行级交集:用左表行签名在右表查找
                java.util.List<String> cols = left.columnNames();
                java.util.Set<String> rightSigs = new HashSet<>();
                for (int i = 0; i < right.rowCount(); i++) {
                    rightSigs.add(sigOf(right, i, cols));
                }
                boolean[] mask = new boolean[left.rowCount()];
                for (int i = 0; i < left.rowCount(); i++) {
                    mask[i] = rightSigs.contains(sigOf(left, i, cols));
                }
                return left.filter(mask);
            }
            case "EXCEPT":
            case "MINUS": {
                // 左表 - 右表(右表中没有的左表行)
                java.util.List<String> cols = left.columnNames();
                java.util.Set<String> rightSigs = new HashSet<>();
                for (int i = 0; i < right.rowCount(); i++) {
                    rightSigs.add(sigOf(right, i, cols));
                }
                boolean[] mask = new boolean[left.rowCount()];
                for (int i = 0; i < left.rowCount(); i++) {
                    mask[i] = !rightSigs.contains(sigOf(left, i, cols));
                }
                return left.filter(mask);
            }
            default:
                throw new IllegalArgumentException("未知集合运算:" + op);
        }
    }

    /** 行签名(用列值拼字符串作 hash key)。 */
    private static String sigOf(DataFrame df, int row, java.util.List<String> cols) {
        StringBuilder sb = new StringBuilder();
        for (String c : cols) sb.append(df.get(row, c)).append("\u0001");
        return sb.toString();
    }

    // ======================== 内部:语法转换 ========================

    // 1. CTE
    private static final Pattern CTE_PATTERN = Pattern.compile(
        "(?is)^\\s*WITH\\s+(.+?)\\s+AS\\s*\\((.+?)\\)\\s*(SELECT\\s+.*)$");

    private static String expandCTE(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        Matcher m = CTE_PATTERN.matcher(sql);
        if (!m.find()) return sql;
        String cteName = m.group(1).trim();
        String cteBody = m.group(2).trim();
        String rest = m.group(3).trim();
        // 执行 CTE body(走完整 SqlRegexEngine 递归,传 defaultDf 让 body 里的 this 可解析)
        DataFrame cteResult = SqlEngines.current().query(defaultDf, cteBody, bindings, SqlDialect.DEFAULT);
        bindings.put(cteName, cteResult);
        // 把 rest 中裸 cteName 替换为 ${cteName}
        // 注意:只替换"未被 ${}"包裹的裸 cteName(避免 ${young} 变成 ${${young}})
        // 用负向先行/后顾断言:前面不是 { ,后面不是 }
        return rest.replaceAll("(?<![\\w{])" + Pattern.quote(cteName) + "(?![\\w}])",
            "\\${" + cteName + "}");
    }

    // 2. CASE WHEN
    private static final Pattern CASE_PATTERN = Pattern.compile(
        "(?is)CASE\\s+WHEN\\s+(.+?)\\s+THEN\\s+(.+?)\\s+ELSE\\s+(.+?)\\s+END");

    private static String expandCaseWhen(String sql) {
        String result = sql;
        Matcher m = CASE_PATTERN.matcher(result);
        while (m.find()) {
            String cond = m.group(1).trim();
            String v1 = m.group(2).trim();
            String v2 = m.group(3).trim();
            String ternary = "(" + cond + " ? " + v1 + " : " + v2 + ")";
            result = result.substring(0, m.start()) + ternary + result.substring(m.end());
            m.reset(result);
        }
        return result;
    }

    // 3. 派生表 FROM (SELECT ...)
    private static final Pattern DERIVED_PATTERN = Pattern.compile(
        "(?is)FROM\\s*\\(\\s*(SELECT\\s+.+?)\\s*\\)\\s*(?:AS\\s+)?(\\w+)");

    private static String expandDerivedTables(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        Matcher m = DERIVED_PATTERN.matcher(sql);
        int counter = 0;
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String subquery = m.group(1).trim();
            counter++;
            String placeholder = "__derived_" + counter + "__";
            DataFrame subResult = SqlEngines.current().query(defaultDf, subquery, bindings, SqlDialect.DEFAULT);
            bindings.put(placeholder, subResult);
            m.appendReplacement(sb, "FROM \\${" + placeholder + "}");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // 4. USING(col1, col2, ...) → ON a.col1 = b.col1 AND a.col2 = b.col2(L3:多列支持)
    private static final Pattern USING_PATTERN = Pattern.compile(
        "(?is)USING\\s*\\(\\s*([\\w\\s,]+?)\\s*\\)");

    private static String expandUsing(String sql) {
        Matcher m = USING_PATTERN.matcher(sql);
        if (!m.find()) return sql;
        String[] cols = m.group(1).split("\\s*,\\s*");
        StringBuilder on = new StringBuilder("ON ");
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) on.append(" AND ");
            on.append("a.").append(cols[i].trim()).append(" = b.").append(cols[i].trim());
        }
        return sql.substring(0, m.start()) + on + sql.substring(m.end());
    }

    // 5. CROSS JOIN:L4 修复 —— 直接做笛卡尔积(不走 SqlEngine JOIN 解析)
    // 策略:找 "CROSS JOIN ${right}" 段,做 left × right 笛卡尔积,结果存为 ${__cross_N__},
    //       把 SQL 改为 "FROM ${__cross_N__}"(无 JOIN)
    private static final Pattern CROSS_JOIN_PATTERN = Pattern.compile(
        "(?is)FROM\\s+(\\$\\{\\w+\\}|this|DUAL)\\s+CROSS\\s+JOIN\\s+\\$\\{(\\w+)}");

    private static String expandCrossJoinToCartesian(DataFrame defaultDf, String sql,
                                                       Map<String, DataFrame> bindings) {
        Matcher m = CROSS_JOIN_PATTERN.matcher(sql);
        if (!m.find()) return sql;
        String leftRef = m.group(1);
        String rightName = m.group(2);
        // 取左右 df
        DataFrame left = resolveDfRef(defaultDf, leftRef, bindings);
        DataFrame right = bindings.get(rightName);
        if (left == null || right == null) return sql;  // 无法解析,交由后续报错
        // 笛卡尔积:left 每行 × right 每行
        DataFrame crossProduct = cartesianProduct(left, right);
        // 注入 binding
        String placeholder = "__cross_" + (bindings.size() + 1) + "__";
        bindings.put(placeholder, crossProduct);
        // 替换 SQL:FROM ${left} CROSS JOIN ${right} → FROM ${__cross_N__}
        return sql.substring(0, m.start()) + "FROM ${" + placeholder + "}"
            + sql.substring(m.end());
    }

    /** 解析 df 引用(${name} / this / DUAL)为实际 DataFrame。 */
    private static DataFrame resolveDfRef(DataFrame defaultDf, String ref, Map<String, DataFrame> bindings) {
        if (ref.equalsIgnoreCase("this") || ref.equalsIgnoreCase("DUAL")) return defaultDf;
        Matcher m = Pattern.compile("\\$\\{(\\w+)}").matcher(ref);
        if (m.matches()) return bindings.get(m.group(1));
        return null;
    }

    /** 笛卡尔积:left.rowCount() × right.rowCount() 行;列 = left + right(重名列加 _r 后缀)。 */
    private static DataFrame cartesianProduct(DataFrame left, DataFrame right) {
        int nl = left.rowCount(), nr = right.rowCount();
        // 列名:右表重名列加 _r 后缀(避免冲突)
        java.util.List<String> leftNames = new java.util.ArrayList<>(left.columnNames());
        java.util.Set<String> leftSet = new java.util.HashSet<>(leftNames);
        java.util.List<String> rightNames = new java.util.ArrayList<>();
        for (String c : right.columnNames()) {
            rightNames.add(leftSet.contains(c) ? c + "_r" : c);
        }
        // 构建 schema
        java.util.List<String> allNames = new java.util.ArrayList<>();
        allNames.addAll(leftNames);
        allNames.addAll(rightNames);
        // 构建行数据
        Object[][] rows = new Object[nl * nr][];
        int idx = 0;
        for (int i = 0; i < nl; i++) {
            Object[] leftRow = left.getRow(i);
            for (int j = 0; j < nr; j++) {
                Object[] rightRow = right.getRow(j);
                Object[] row = new Object[leftRow.length + rightRow.length];
                System.arraycopy(leftRow, 0, row, 0, leftRow.length);
                System.arraycopy(rightRow, 0, row, leftRow.length, rightRow.length);
                rows[idx++] = row;
            }
        }
        // 用 Schema.infer 推断类型
        java.util.List<jian.core.DType> dtypes = new java.util.ArrayList<>();
        for (String c : left.columnNames()) dtypes.add(left.getColumn(c).dtype());
        for (String c : right.columnNames()) dtypes.add(right.getColumn(c).dtype());
        Object[] schParts = new Object[allNames.size() * 2];
        for (int i = 0; i < allNames.size(); i++) {
            schParts[i * 2] = allNames.get(i);
            schParts[i * 2 + 1] = dtypes.get(i);
        }
        return jian.core.DataFrame.of(jian.core.Schema.of(schParts), rows);
    }
}
