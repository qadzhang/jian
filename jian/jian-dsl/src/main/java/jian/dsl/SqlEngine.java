package jian.dsl;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ┌─ What : SqlEngine —— L3 类 SQL 子集(对齐规范 07 §2.3 自写,不依赖 ANTLR)
// │  Why  : 规范 07 §2.3;支持 SELECT/WHERE/GROUP BY/HAVING/ORDER BY/LIMIT/JOIN/UNION ALL
// │  Who  : 由 Dsl.sql 委托
// │  When : df.sql("SELECT ... FROM this GROUP BY ...")
// │  Where: jian-dsl/SqlEngine.java
// │  How  : 数据走向:SQL 字符串 → 正则解析各子句 → 翻译为 core 调用链 → DataFrame。
// │         关键变量变化:
// │           - clauses:解析出的 select/where/groupBy/having/orderBy/limit/join 各段;
// │           - ${df} 占位 → bindings 中取 DataFrame;
// │           - FROM this → 当前 df。
// │         逻辑路线:
// │           路径 A(SELECT * + WHERE)→ df.query(where);
// │           路径 B(GROUP BY + agg)→ df.groupBy + agg;
// │           路径 C(ORDER BY)→ df.sortBy;
// │           路径 D(LIMIT)→ df.head;
// │           路径 E(JOIN ${df2})→ df.merge;
// │           路径 F(UNION ALL)→ DataFrame.concat。
/**
 * L3 类 SQL 子集引擎(规范 07 §2.3)。
 *
 * <p>支持子句:
 * <ul>
 *   <li>{@code SELECT col1, agg(col2) AS alias} / {@code *};</li>
 *   <li>{@code FROM this} / {@code FROM ${name}}(占位绑定 DataFrame);</li>
 *   <li>{@code WHERE expr}(复用 L1 Pratt);</li>
 *   <li>{@code GROUP BY cols} + {@code HAVING expr};</li>
 *   <li>{@code ORDER BY col [DESC]} / {@code col2 DESC};</li>
 *   <li>{@code LIMIT n} / Oracle {@code ROWNUM <= n} / {@code FETCH FIRST n ROWS ONLY}(三方言都认);</li>
 *   <li>{@code JOIN ${name} ON a=b}(INNER);</li>
 *   <li>{@code UNION ALL}(纵向拼接)。</li>
 * </ul>
 *
 * <p><b>简化实现</b>:正则切子句 + 翻译为 core 调用链。完整 ANTLR PlSqlParser.g4 集成留 v2(规范 §1.3)。
 */
final class SqlEngine {

    private SqlEngine() {}

    /**
     * 执行 SQL 子集。df 为 null 时 SQL 必须含 ${name} 绑定。
     *
     * @param df DataFrame SQL 主表(对应 FROM this);占位模式下可传 null
     * @param sql String SQL 字符串,非 null
     * @param bindings Map&lt;String,DataFrame&gt; ${name} → DataFrame 的绑定表,非 null
     * @param dialect SqlDialect SQL 方言,非 null
     * @return DataFrame SQL 执行结果
     */
    static DataFrame execute(DataFrame df, String sql, Map<String, DataFrame> bindings, SqlDialect dialect) {
        return execute(df, sql, bindings, dialect, 0);
    }

    /** 带深度的执行(子查询用,depth 超过 2 抛异常;规范:子查询最多 2 层)。 */
    private static DataFrame execute(DataFrame df, String sql, Map<String, DataFrame> bindings,
                                      SqlDialect dialect, int depth) {
        if (depth > 2) {
            throw new IllegalArgumentException("子查询嵌套深度超过 2 层(规范 07 §2.3 限制):"
                    + sql.substring(0, Math.min(60, sql.length())) + "...");
        }
        int unionIdx = findUnionAll(sql);
        if (unionIdx >= 0) {
            String left = sql.substring(0, unionIdx).trim();
            String right = sql.substring(unionIdx + "UNION ALL".length()).trim();
            return DataFrame.concat(
                    execute(df, left, bindings, dialect, depth),
                    execute(df, right, bindings, dialect, depth));
        }
        return executeSelect(df, sql, bindings, dialect, depth);
    }

    private static int findUnionAll(String sql) {
        Matcher m = Pattern.compile("\\bUNION\\s+ALL\\b", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (m.find()) return m.start();
        return -1;
    }

    private static DataFrame executeSelect(DataFrame df, String sql, Map<String, DataFrame> bindings,
                                            SqlDialect dialect, int depth) {
        String s = sql.trim().replaceAll(";\\s*$", "");
        if (!s.toUpperCase().startsWith("SELECT")) {
            throw new IllegalArgumentException("L3 仅支持 SELECT 入口;本 DSL 不支持 PL/SQL 过程化语法");
        }
        // 静态入口(Dsl.sql/Jian.sql)无主表且无 ${} 占位时,this/DUAL 无对象可指:明确报错而非 NPE
        if (df == null && bindings.isEmpty()) {
            throw new IllegalArgumentException(
                    "SQL 无 ${} 表名占位,无法确定主表;请用 df.sql()(接收者即主表),或把表名写成 ${名} 占位");
        }
        // SELECT DISTINCT(规范 07 §2.3):先记标记,列解析时去掉 DISTINCT 关键字,最后去重
        boolean distinct = s.toUpperCase().matches("(?is)^SELECT\\s+DISTINCT\\b.*");

        FromClause from = parseFrom(df, s, bindings);

        // WHERE(先把 (SELECT...) 子查询展开为字面量,再喂 PrattEngine)
        DataFrame afterWhere = from.df;
        String whereRaw = extractClause(s, "WHERE", new String[]{"GROUP BY", "HAVING", "ORDER BY", "LIMIT", "FETCH", "ROWNUM"});
        if (whereRaw != null) {
            String whereExpanded = expandSubqueries(from.df, whereRaw, bindings, dialect, depth);
            afterWhere = PrattEngine.query(from.df, whereExpanded, Params.EMPTY);
        }

        // GROUP BY
        String groupBy = extractClause(s, "GROUP BY", new String[]{"HAVING", "ORDER BY", "LIMIT", "FETCH"});
        DataFrame grouped;
        if (groupBy != null) {
            String[] gbCols = groupBy.split(",");
            for (int i = 0; i < gbCols.length; i++) gbCols[i] = gbCols[i].trim();
            String selectPart = stripDistinct(extractSelect(s));
            AggSpec spec = parseSelectWithAgg(selectPart);
            var gb = afterWhere.groupBy(gbCols);
            DataFrame agg = gb.agg(spec.aggMap);
            String having = extractClause(s, "HAVING", new String[]{"ORDER BY", "LIMIT", "FETCH"});
            if (having != null) {
                String havingExpanded = expandSubqueries(agg, having, bindings, dialect, depth);
                agg = PrattEngine.query(agg, havingExpanded, Params.EMPTY);
            }
            grouped = selectColumns(agg, spec);
        } else {
            String selectPart = stripDistinct(extractSelect(s));
            // 检测 SELECT 是否含聚合函数(如 mean(x), sum(y) 等,即使无 GROUP BY 也聚合)
            if (selectPart.toLowerCase().matches(".*\\b(sum|mean|count|min|max|median|std|var|first|last|nunique)\\s*\\(.*")) {
                AggSpec spec = parseSelectWithAgg(selectPart);
                // 无 GROUP BY 全局聚合:用临时常量列分组(整表一组)
                DataFrame withConst = afterWhere.assign("__group_all__", r -> "ALL");
                DataFrame agg = withConst.groupBy("__group_all__").agg(spec.aggMap);
                grouped = selectColumns(agg, spec);
            } else {
                // L8 修复(2026-08-09,与 AI agent2 / AI agent1 第二轮审查共识):
                // 取消"含 ? / CASE 才走 parseSelectWithAgg"的门关 —— 该门关导致纯算术表达式
                // (如 "(salary + 1000) AS total")走 parseSelectSimple,后者正则要求开头 \w+,
                // 对 ( 前缀失败,产出 alias==null 的 SelectItem,被 selectColumns 静默丢弃(列消失)。
                // parseSelectWithAgg 已能识别 col / col AS alias / 聚合 / 表达式列全形态,
                // 无条件走它反而把分支收敛,降低"两套解析器分歧"风险。
                grouped = selectColumns(afterWhere, parseSelectWithAgg(selectPart));
            }
        }
        // DISTINCT 去重(SELECT DISTINCT 语义:作用于全部选中列,ORDER BY/LIMIT 之前)
        if (distinct) grouped = grouped.dropDuplicates();

        // ORDER BY
        String orderBy = extractClause(s, "ORDER BY", new String[]{"LIMIT", "FETCH"});
        if (orderBy != null) {
            String[] parts = orderBy.split(",");
            String[] cols = new String[parts.length];
            boolean[] ascs = new boolean[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                boolean desc = p.toUpperCase().endsWith(" DESC");
                cols[i] = desc ? p.substring(0, p.length() - 5).trim() : (p.toUpperCase().endsWith(" ASC") ? p.substring(0, p.length() - 4).trim() : p);
                ascs[i] = !desc;
            }
            grouped = grouped.sortBy(cols, ascs);
        }

        // 分页顺序 = SQL 语义:先 OFFSET 跳过 m 行,再 LIMIT 取 n 行(不能反过来)
        int[] limitOffset = extractLimitOffset(s);
        if (limitOffset[1] > 0) grouped = grouped.slice(limitOffset[1], grouped.rowCount());
        if (limitOffset[0] >= 0) grouped = grouped.head(limitOffset[0]);
        return grouped;
    }

    /** SELECT 列部分去掉 DISTINCT 关键字(仅在开头)。 */
    private static String stripDistinct(String selectPart) {
        return selectPart.replaceFirst("(?i)^DISTINCT\\s+", "");
    }

    /**
     * 抽取 WHERE/HAVING 里的 (SELECT ...) 子查询,递归执行得到值列表,
     * 替换为字面量后返回可喂 PrattEngine 的表达式。支持:
     * <ul>
     *   <li>{@code col IN (SELECT col2 FROM ...)} → col IN (v1, v2, ...)</li>
     *   <li>{@code col > (SELECT max(x) FROM ...)} → col > 单值(标量子查询,取首行首列)</li>
     * </ul>
     * 深度由 depth 控制(本层 +1,> 2 抛异常,规范 §2.3)。
     */
    private static String expandSubqueries(DataFrame df, String expr, Map<String, DataFrame> bindings,
                                            SqlDialect dialect, int depth) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '(' && i + 6 < expr.length()
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
                DataFrame subResult = execute(df, subSql, bindings, dialect, depth + 1);
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
     * 子查询结果 → 字面量。根据子查询前的 token(IN / 比较运算符)决定形态。
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

    /** Java 值 → SQL 字面量(Number 直接,String 加引号)。 */
    private static String toLiteral(Object v) {
        if (v == null) return "null";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return v.toString();
        return "'" + v.toString().replace("'", "\\'") + "'";
    }

    /** 解析 FROM 子句(支持 this / ${name} / INNER|LEFT|RIGHT|FULL OUTER JOIN,链式多表 JOIN)。 */
    private static FromClause parseFrom(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        // OFFSET 也作结束关键字(否则 "FROM ${t} OFFSET 2" 会把 OFFSET 段吞进数据源)
        Matcher m = Pattern.compile("\\bFROM\\s+(.+?)(\\bWHERE\\b|\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bLIMIT\\b|\\bOFFSET\\b|\\bFETCH\\b|$)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        if (!m.find()) {
            return new FromClause(defaultDf, null);
        }
        String fromText = m.group(1).trim();

        // 链式多表 JOIN:逐个识别 "X JOIN ${Y} ON a.x = b.y" 段
        // join 段正则:可选 OUTER 前缀的 (LEFT|RIGHT|FULL [OUTER])? JOIN
        Pattern joinP = Pattern.compile(
                "\\s+(LEFT|RIGHT|FULL(?:\\s+OUTER)?|INNER)?\\s*JOIN\\s+(\\$\\{\\w+\\}|\\w+)\\s+ON\\s+(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)",
                Pattern.CASE_INSENSITIVE);
        Matcher joinM = joinP.matcher(fromText);

        // 找第一个 JOIN 位置,把 fromText 切成 [左源] [JOIN 段1] [JOIN 段2]...
        int firstJoin = -1;
        Matcher probe = joinP.matcher(fromText);
        if (probe.find()) firstJoin = probe.start();
        if (firstJoin < 0) {
            return new FromClause(resolveSource(fromText, defaultDf, bindings), null);
        }

        // 左源 = 第一个 JOIN 之前的文本
        String leftSrc = fromText.substring(0, firstJoin).trim();
        DataFrame current = resolveSource(leftSrc, defaultDf, bindings);

        // 重置 matcher,从 firstJoin 开始扫每个 JOIN 段,逐个 merge(链式)
        joinM.find(firstJoin);
        // 循环:每次 joinM.region(start, len) 重设范围
        int searchFrom = firstJoin;
        while (searchFrom < fromText.length()) {
            joinM = joinP.matcher(fromText);
            if (!joinM.find(searchFrom)) break;
            String howKw = joinM.group(1);
            String rightRef = joinM.group(2);
            String leftAlias = joinM.group(3);
            String leftKey = joinM.group(4);
            String rightAlias = joinM.group(5);
            String rightKey = joinM.group(6);
            String how = howKw == null ? "inner" : howKw.toUpperCase().replaceAll("\\s+", " ").replace(" OUTER", "");
            if (how.equals("FULL")) how = "outer";
            DataFrame rightDf = resolveSource(rightRef, defaultDf, bindings);
            current = current.merge(rightDf, how.toLowerCase(), new String[]{leftKey}, new String[]{rightKey}, null);
            searchFrom = joinM.end();
        }
        return new FromClause(current, null);
    }

    private static DataFrame resolveSource(String src, DataFrame defaultDf, Map<String, DataFrame> bindings) {
        src = src.trim();
        if (src.equalsIgnoreCase("this") || src.equalsIgnoreCase("DUAL")) {
            if (defaultDf == null) {
                // 防 NPE:${} 占位模式下没有主表,this/DUAL 无对象可指,给明确报错而不是空指针
                throw new IllegalArgumentException(
                        "SQL 引用 FROM " + src + " 但未提供主表 DataFrame:使用 ${} 占位时无法同时引用 this/DUAL,"
                                + "请改用 df.sql()(接收者即主表)");
            }
            return defaultDf;
        }
        if (src.startsWith("${") && src.endsWith("}")) {
            String name = src.substring(2, src.length() - 1);
            DataFrame d = bindings.get(name);
            if (d == null) throw new IllegalArgumentException("绑定 ${" + name + "} 未提供");
            return d;
        }
        // 不再静默当作 this:未知数据源直接报错,提示合法写法
        throw new IllegalArgumentException("无法识别的数据源 '" + src + "',支持:this / DUAL / ${name} 占位");
    }

    /**
     * 解析 LIMIT/OFFSET。返回 {limit, offset};limit = -1 表示无 LIMIT。
     * 支持三种分页写法(规范 07 §2.3):
     *   PG/MySQL:{@code LIMIT n [OFFSET m]}(也支持独立 {@code OFFSET m [ROWS]});
     *   Oracle:{@code OFFSET m ROWS FETCH FIRST n ROWS ONLY} 或 {@code ROWNUM <= n}。
     */
    private static int[] extractLimitOffset(String sql) {
        int limit = -1, offset = 0;
        // PG/MySQL: LIMIT n [OFFSET m]
        Matcher lm = Pattern.compile("\\bLIMIT\\s+(\\d+)(?:\\s+OFFSET\\s+(\\d+))?", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (lm.find()) {
            limit = Integer.parseInt(lm.group(1));
            if (lm.group(2) != null) offset = Integer.parseInt(lm.group(2));
        }
        // Oracle: OFFSET m ROWS FETCH FIRST n ROWS ONLY / FETCH FIRST n ROWS ONLY
        Matcher fm = Pattern.compile("\\bFETCH\\s+FIRST\\s+(\\d+)\\s+ROWS\\s+ONLY\\b", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (fm.find() && limit < 0) limit = Integer.parseInt(fm.group(1));
        Matcher om = Pattern.compile("\\bOFFSET\\s+(\\d+)\\s+(?:ROWS\\s+)?FETCH\\s+FIRST", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (om.find() && offset == 0) offset = Integer.parseInt(om.group(1));
        // 独立 OFFSET m [ROWS](不跟 LIMIT/FETCH 时)
        if (offset == 0) {
            Matcher om2 = Pattern.compile("\\bOFFSET\\s+(\\d+)(?:\\s+ROWS)?(?:\\s+(?=FETCH|LIMIT)|$)", Pattern.CASE_INSENSITIVE).matcher(sql);
            if (om2.find()) offset = Integer.parseInt(om2.group(1));
        }
        // Oracle: ROWNUM <= n(只作 LIMIT,无 OFFSET 语义)
        if (limit < 0) {
            Matcher rm = Pattern.compile("\\bROWNUM\\s*<=\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(sql);
            if (rm.find()) limit = Integer.parseInt(rm.group(1));
        }
        return new int[]{limit, offset};
    }

    /** 提取 SELECT 列部分(FROM 之前)。 */
    private static String extractSelect(String sql) {
        Matcher m = Pattern.compile("\\bSELECT\\s+(.+?)\\bFROM\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        if (!m.find()) throw new IllegalArgumentException("SELECT 必须含 FROM");
        return m.group(1).trim();
    }

    /** 简单 SELECT 列(col1, col2 / *,AS alias 支持)。 */
    private static List<SelectItem> parseSelectSimple(String selectPart) {
        List<SelectItem> r = new ArrayList<>();
        if (selectPart.equals("*")) {
            r.add(new SelectItem("*", null));
            return r;
        }
        for (String p : selectPart.split(",")) {
            String t = p.trim();
            // col AS alias 或 col alias
            Matcher am = Pattern.compile("(\\w+)\\s+(?:AS\\s+)?(\\w+)\\s*$", Pattern.CASE_INSENSITIVE).matcher(t);
            if (am.matches()) r.add(new SelectItem(am.group(1), am.group(2)));
            else r.add(new SelectItem(t, null));
        }
        return r;
    }

    /** 带 agg 的 SELECT(SUM/MEAN/COUNT/MIN/MAX/MEDIAN/STD/VAR/FIRST/LAST + 普通列)。 */
    private static AggSpec parseSelectWithAgg(String selectPart) {
        // selectPart 形如 "city, avg(salary) AS avg_sal, count(*) AS cnt"
        List<SelectItem> items = new ArrayList<>();
        Map<String, String> aggMap = new LinkedHashMap<>();
        // 用括号感知切分
        List<String> parts = splitComma(selectPart);
        List<String> groupCols = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            Matcher am = Pattern.compile("(?i)(\\w+)\\((\\*|[\\w.]+)\\)\\s*(?:AS\\s+)?(\\w+)?").matcher(t);
            if (am.matches()) {
                String fn = am.group(1).toLowerCase();
                String col = am.group(2);
                String alias = am.group(3) != null ? am.group(3) : fn + "_" + col;
                if (col.equals("*")) {
                    aggMap.put(am.group(3) != null ? am.group(3) : "count", fn);
                } else {
                    aggMap.put(col, fn);
                }
                items.add(new SelectItem(t, alias));
            } else {
                Matcher rm = Pattern.compile("(\\w+)\\s*(?:AS\\s+(\\w+))?").matcher(t);
                if (rm.matches()) {
                    items.add(new SelectItem(rm.group(1), rm.group(2)));
                    groupCols.add(rm.group(1));
                } else {
                    // 2026-08-09 L1 修复:表达式列(CASE WHEN 转的三元 / 算术表达式 / 字面量)
                    // 形如 "(cond ? v1 : v2) AS alias" / "(a + b) AS sum" / "'hello' AS greeting"
                    Matcher em = Pattern.compile("(.+?)\\s+(?:AS\\s+)?(\\w+)$").matcher(t);
                    if (em.matches()) {
                        // 表达式列:alias 必须有(否则用户无法引用)
                        items.add(new SelectItem(em.group(1).trim(), em.group(2)));
                        // 表达式列不参与 groupBy,但也不报错(在 selectColumns 用 PrattEngine 评估)
                    }
                    // 完全无法识别的项:静默跳过(保留原行为)
                }
            }
        }
        return new AggSpec(items, aggMap);
    }

    /** 按 SELECT items 在结果 df 上选列(带 alias 重命名)。 */
    private static DataFrame selectColumns(DataFrame df, AggSpec spec) {
        // 简化:不重命名 alias,只 select 列
        if (spec.items.size() == 1 && spec.items.get(0).expr.equals("*")) return df;
        List<String> cols = new ArrayList<>();
        // 表达式列(CASE 转的三元 / 算术):用 PrattEngine 评估每行,加为新列
        List<SelectItem> exprItems = new ArrayList<>();
        for (SelectItem it : spec.items) {
            // L1 修复:先判断表达式列(以 ( 开头 / 含三元 ? / 含字符串字面量)
            boolean isExpr = it.expr.startsWith("(") && (it.expr.contains("?")
                            || it.expr.contains("'") || it.expr.matches(".*[+\\-*/].*"));
            if (isExpr) {
                if (it.alias != null) exprItems.add(it);
            } else if (it.expr.toLowerCase().contains("(") && !it.expr.startsWith("(")) {
                // 聚合列:agg(col) 形式
                Matcher am = Pattern.compile("(?i)(\\w+)\\((\\*|[\\w.]+)\\)").matcher(it.expr);
                if (am.find()) {
                    String fn = am.group(1).toLowerCase();
                    String col = am.group(2);
                    String outName = col.equals("*") ? it.alias : (col + "_" + fn);
                    if (df.columnIndex(outName) >= 0) cols.add(outName);
                    else if (it.alias != null && df.columnIndex(it.alias) >= 0) cols.add(it.alias);
                }
            } else {
                if (df.columnIndex(it.expr) >= 0) cols.add(it.expr);
            }
        }
        // 处理表达式列(L1 修复):在 select 前用原始 df 评估,确保表达式能访问所有列
        DataFrame result = df;  // 先保留原始 df(含所有列)
        for (SelectItem it : exprItems) {
            result = applyExprColumn(result, it.expr, it.alias);  // 在原始 df 上加新列
        }
        // 再 select 需要的列(简单列 + 表达式产生的 alias 列)
        if (!cols.isEmpty()) {
            // 把表达式 alias 也加入 select 列表
            for (SelectItem it : exprItems) {
                if (it.alias != null && result.columnIndex(it.alias) >= 0) cols.add(it.alias);
            }
            // 去重(cols 可能有重复)
            cols = new ArrayList<>(new java.util.LinkedHashSet<>(cols));
            result = result.select(cols.toArray(new String[0]));
        }
        return result;
    }

    /**
     * 对 df 应用一个表达式列(算术 / 三元 / 比较 / 逻辑 / 字符串字面量 全支持)。
     *
     * <p>L8 修复(2026-08-09,与 AI agent2 / AI agent1 第二轮审查共识,基于本机反射 + 黑盒实测):
     * 原实现是手写补丁(自写三元正则 tm + 自写 bindRowValues 字面量替换 + evalArithmetic stub),
     * 仅支持三元,算术表达式走 evalArithmetic 恒返 null(后改成抛异常也是逃避)。
     * 现直接委托 {@link PrattEngine#eval},它原生支持算术 / 三元(嵌套)/ 比较 / 逻辑 / 谓词 / 字面量,
     * 且经 PrattEngine 自身的测试套件验证 —— 复用已验证能力,删手写补丁。
     *
     * <p>异常处理:PrattEngine.parse/eval 失败会抛 IAE(列不存在/语法错/类型不匹配),
     * 这里不再 catch(原 return df 会静默丢列),让异常向上传播到 executeSelect,用户拿到带 alias + cause 的报错。
     *
     * @param df DataFrame 当前结果(含表达式能访问的所有列)
     * @param expr String 表达式(如 "salary + 1000" / "(salary > 1000) ? 'high' : 'low'")
     * @param alias String 新列名
     * @return DataFrame 原 df + 新列(类型经 Schema.infer 推断)
     */
    private static DataFrame applyExprColumn(DataFrame df, String expr, String alias) {
        // 数据走向:expr → "alias = expr" → PrattEngine.eval → 加新列的 df
        // PrattEngine.evalSingle 内部:parse(expr) → 逐行 ast.eval(RowBinding) → Schema.infer → assign+astype
        return PrattEngine.eval(df, alias + " = " + expr, Params.EMPTY);
    }


    private static DataFrame selectColumns(DataFrame df, List<SelectItem> items) {
        if (items.size() == 1 && items.get(0).expr.equals("*")) return df;
        List<String> cols = new ArrayList<>();
        for (SelectItem it : items) if (df.columnIndex(it.expr) >= 0) cols.add(it.expr);
        if (cols.isEmpty()) return df;
        return df.select(cols.toArray(new String[0]));
    }

    /** 提取子句(如 WHERE 后到下一个关键字之前的文本)。 */
    /**
     * 提取子句(括号感知:只在深度 0 匹配关键字,子查询括号内的同名关键字不算)。
     * 例:WHERE ... (SELECT ... WHERE x) GROUP BY → 只在第一个外层 WHERE 和 GROUP BY 间取文本。
     */
    private static String extractClause(String sql, String keyword, String[] stops) {
        // 找 keyword 起始(深度 0)
        String kw = keyword.toLowerCase();
        int kwStart = findTopLevelKeyword(sql, kw, 0);
        if (kwStart < 0) return null;
        int contentStart = kwStart + keyword.length();
        // 从 contentStart 开始扫,找第一个深度 0 的 stop
        int depth = 0;
        String lowerTail = sql.substring(contentStart).toLowerCase();
        for (int i = 0; i < lowerTail.length(); i++) {
            char c = lowerTail.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && Character.isLetter(c)) {
                // 在每个 token 起点(深度 0)试匹配各 stop
                if (i == 0 || !Character.isLetterOrDigit(lowerTail.charAt(i - 1))) {
                    for (String stop : stops) {
                        String s = stop.toLowerCase();
                        if (lowerTail.startsWith(s, i)
                                && (i + s.length() == lowerTail.length()
                                    || !Character.isLetterOrDigit(lowerTail.charAt(i + s.length())))) {
                            return sql.substring(contentStart, contentStart + i).trim();
                        }
                    }
                }
            }
        }
        return sql.substring(contentStart).trim();
    }

    /** 在 sql 的 fromIndex 之后找 keyword 的第一个出现位置(深度 0,即不在括号内)。 */
    private static int findTopLevelKeyword(String sql, String lowerKeyword, int fromIndex) {
        String lower = sql.toLowerCase();
        int depth = 0;
        for (int i = fromIndex; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth == 0 && lower.startsWith(lowerKeyword, i)
                    && (i == 0 || !Character.isLetterOrDigit(lower.charAt(i - 1)))
                    && (i + lowerKeyword.length() == lower.length()
                        || !Character.isLetterOrDigit(lower.charAt(i + lowerKeyword.length())))) {
                return i;
            }
        }
        return -1;
    }


    /** 括号感知的逗号切分。 */
    private static List<String> splitComma(String s) {
        List<String> r = new ArrayList<>();
        int depth = 0; int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                r.add(s.substring(start, i));
                start = i + 1;
            }
        }
        r.add(s.substring(start));
        return r;
    }

    // 辅助 record
    record FromClause(DataFrame df, Object dummy) {}
    record SelectItem(String expr, String alias) {}
    record AggSpec(List<SelectItem> items, Map<String, String> aggMap) {}
}
