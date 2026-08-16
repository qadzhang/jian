package jian.dsl;

import jian.core.Column;
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

    /** count(*) 的哨兵列名 —— assign 全 1 常量列后 count(1)=行数。
     *  与 __group_all__ 同风格内部列;用户 SQL 无法引用(选择列阶段被丢弃)。 */
    private static final String COUNT_STAR_SENTINEL = "__jian_cnt_star__";

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

    /** 带深度的执行(子查询用,depth 超过 2 抛异常;规范:子查询最多 2 层)。
     *  包私有:SqlSubqueryExpander 递归调用(同包协作类,非公开 API)。 */
    static DataFrame execute(DataFrame df, String sql, Map<String, DataFrame> bindings,
                                      SqlDialect dialect, int depth) {
        if (depth > 2) {
            throw new IllegalArgumentException("子查询嵌套深度超过 2 层(规范 07 §2.3 限制):"
                    + sql.substring(0, Math.min(60, sql.length())) + "...");
        }
        int unionIdx = findUnionAll(sql);
        if (unionIdx >= 0) {
            String left = sql.substring(0, unionIdx).trim();
            String right = sql.substring(unionIdx + "UNION ALL".length()).trim();
            DataFrame l = execute(df, left, bindings, dialect, depth);
            DataFrame r = execute(df, right, bindings, dialect, depth);
            // 因为列数不等的两侧直接 concat 会让混型值进首见 dtype 列、触发裸 NumberFormatException,
            // 所以先校验列数并抛教学 IAE(对齐 SQLite 同场景报
            // "SELECTs to the left and right of UNION do not have the same number of result columns")
            if (l.columnCount() != r.columnCount()) {
                throw new IllegalArgumentException("UNION ALL 两侧列数不一致:左侧 " + l.columnCount()
                        + " 列(" + l.columnNames() + ") vs 右侧 " + r.columnCount() + " 列(" + r.columnNames() + ")"
                        + "(对齐 SQLite 报错语义)");
            }
            return DataFrame.concat(l, r);
        }
        return executeSelect(df, sql, bindings, dialect, depth);
    }

    private static int findUnionAll(String sql) {
        // 因为字符串字面量里的 UNION ALL 会误切(WHERE col = 'UNION ALL x'),所以查找跳过字符串字面量
        // 同时跳过括号区:子查询内的 UNION ALL 属于子层集合运算,若被本层切分
        // 会把外层查询切坏(报"子查询括号未闭合")。只有深度 0 的 UNION ALL 才属于
        // 本层集合运算,子查询文本随后经 expandSubqueries 递归执行、在子层深度 0 正确切分。
        return indexOfTopLevelKeyword(sql, "union all");
    }

    // ┌─ What : 顶层关键字查找(字符串字面量感知 + 括号深度感知)
    // │  Why  : 关键字解析点(UNION ALL)会误匹配字符串字面量里的同名词,
    // │         也会误匹配括号内子查询里的同名词 —— 统一走
    // │         "跳过单/双引号字面量(含 '' 翻倍转义)+ 跳过括号深度>0 区域"的查找,
    // │         所有关键字定位点共用同一实现
    // │  Who  : findUnionAll
    // │  When : 任何顶层(深度 0)关键字定位
    // │  Where: jian-dsl/SqlEngine.java
    // │  How  : 逐字符扫描,三个状态维度:
    // │           - depth:遇 ( 加一、遇 ) 减一;depth>0 时不做任何关键字匹配;
    // │           - 引号区(单/双,含 '' 翻倍)整体跳过,引号内字符不参与匹配;
    // │           - i 指向关键字起点时做词边界匹配(前后字符均非字母数字)。
    // │         逻辑路线:
    // │           路径 A(遇引号)→ 跳到闭合引号('' 翻倍续走)→ continue;
    // │           路径 B(遇括号)→ 调整 depth 后 continue;
    // │           路径 C(depth==0 且词边界命中)→ 返回 i;
    // │           路径 D(扫完无命中)→ 返回 -1。
    private static int indexOfTopLevelKeyword(String sql, String lowerKeyword) {
        String lower = sql.toLowerCase();
        int n = lower.length();
        int depth = 0;
        for (int i = 0; i < n; i++) {
            char c = lower.charAt(i);
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (c == '\'' || c == '"') {
                char q = c; i++;
                while (i < n) {
                    if (lower.charAt(i) == q) {
                        if (i + 1 < n && lower.charAt(i + 1) == q) { i += 2; continue; }  // '' 翻倍
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (depth == 0 && lower.startsWith(lowerKeyword, i)
                    && (i == 0 || !Character.isLetterOrDigit(lower.charAt(i - 1)))
                    && (i + lowerKeyword.length() == n
                        || !Character.isLetterOrDigit(lower.charAt(i + lowerKeyword.length())))) {
                return i;
            }
        }
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
        // 因为 "WHERE v > 1 OFFSET 1" 的 OFFSET 段若被吞进 WHERE 表达式,
        // Pratt 会报"尾部多余 token 'OFFSET'",所以 stops 里补 OFFSET
        DataFrame afterWhere = from.df;
        String whereRaw = extractClause(s, "WHERE", new String[]{"GROUP BY", "HAVING", "ORDER BY", "LIMIT", "FETCH", "ROWNUM", "OFFSET"});
        if (whereRaw != null) {
            // 先展开子查询,再做 SQL→DSL 运算符归一化(裸 = / <> / 反引号),喂 Pratt
            // 子查询展开器拆在 SqlSubqueryExpander(§3.1 行数约束)
            String whereExpanded = SqlSubqueryExpander.expand(from.df, whereRaw, bindings, dialect, depth);
            afterWhere = PrattEngine.query(from.df, SqlPreprocessor.normalizeSqlExpr(whereExpanded), Params.EMPTY);
        }

        // GROUP BY(AS 别名真重命名,聚合别名在 HAVING 前生效)
        String groupBy = extractClause(s, "GROUP BY", new String[]{"HAVING", "ORDER BY", "LIMIT", "FETCH"});
        DataFrame grouped;
        AggSpec spec;
        DataFrame preProjection;   // 投影前的行集(ORDER BY 引用未选中列时用它排序)
        if (groupBy != null) {
            String[] gbCols = groupBy.split(",");
            // GROUP BY 列名剥反引号(MySQL 标识符引用)
            for (int i = 0; i < gbCols.length; i++) gbCols[i] = SqlPreprocessor.normalizeSqlExpr(gbCols[i].trim());
            String selectPart = SqlPreprocessor.normalizeSqlExpr(stripDistinct(extractSelect(s)));
            spec = parseSelectWithAgg(selectPart);
            // count(*) → 哨兵常量列(全 1 无缺失),count 即组行数(输出 LONG)
            if (spec.aggMap.containsKey(COUNT_STAR_SENTINEL)) {
                afterWhere = afterWhere.assign(COUNT_STAR_SENTINEL, r -> 1L);
            }
            var gb = afterWhere.groupBy(gbCols);
            DataFrame agg = gb.agg(spec.aggMap);
            // 聚合别名(sum(金额) AS 合计 → 金额_sum 改名 合计)先于 HAVING/ORDER BY 生效
            // (SQL 语义:HAVING 可引用 SELECT 别名;忽略别名会与表达式列 AS 行为自相矛盾)
            agg = renameColumns(agg, aggAliasMap(spec, agg));
            String having = extractClause(s, "HAVING", new String[]{"ORDER BY", "LIMIT", "FETCH"});
            if (having != null) {
                // HAVING 同 WHERE,先归一化运算符再喂 Pratt(子查询展开同走 Expander)
                String havingExpanded = SqlSubqueryExpander.expand(agg, having, bindings, dialect, depth);
                agg = PrattEngine.query(agg, SqlPreprocessor.normalizeSqlExpr(havingExpanded), Params.EMPTY);
            }
            preProjection = agg;
            grouped = selectColumns(agg, spec);
        } else {
            // SELECT 列表统一归一化(剥反引号标识符 + 裸 = / <> 归一,
            // CASE 展开的三元 cond 常含 SQL 等号;反引号列名 `类别` 不归一化则无法解析)
            String selectPart = SqlPreprocessor.normalizeSqlExpr(stripDistinct(extractSelect(s)));
            // 检测 SELECT 是否含聚合函数(如 mean(x), sum(y) 等,即使无 GROUP BY 也聚合)
            if (selectPart.toLowerCase().matches(".*\\b(sum|avg|mean|count|min|max|median|std|var|first|last|nunique)\\s*\\(.*")) {
                spec = parseSelectWithAgg(selectPart);
                // 因为无 GROUP BY 的聚合查询里,非聚合列(如 SELECT cat, sum(val))被静默丢弃
                // 会让用户拿到少列结果而无任何提示(静默丢列 = 无声数据丢失;SQLite 同场景报
                // misuse of aggregate),所以只允许纯聚合项 ——
                for (SelectItem it : spec.items) {
                    java.util.regex.Matcher am = AGG_ITEM_RE.matcher(it.expr.trim());
                    if (!am.find()) {
                        throw new IllegalArgumentException("SELECT 含非聚合列 '" + it.expr
                                + "' 但无 GROUP BY(要么补 GROUP BY,要么去掉该列;对齐 SQLite misuse of aggregate)");
                    }
                }
                // 无 GROUP BY 全局聚合:用临时常量列分组(整表一组);count(*) 同走哨兵列
                DataFrame withCnt = spec.aggMap.containsKey(COUNT_STAR_SENTINEL)
                        ? afterWhere.assign(COUNT_STAR_SENTINEL, r -> 1L) : afterWhere;
                DataFrame withConst = withCnt.assign("__group_all__", r -> "ALL");
                preProjection = withConst.groupBy("__group_all__").agg(spec.aggMap);
                // 空表无 GROUP BY 聚合恒 1 行(SQLite "SELECT count(*) FROM 空" = 1 行 0;
                // pandas 同)。count → 0;其余聚合 → null(全组缺失)
                if (preProjection.rowCount() == 0 && withConst.rowCount() == 0) {
                    List<String> outNames = new ArrayList<>();
                    for (SelectItem it : spec.items) {
                        Matcher am = AGG_ITEM_RE.matcher(it.expr.trim());
                        String base = am.find()
                                ? (am.group(2).equals("*") ? COUNT_STAR_SENTINEL + "_count" : am.group(2) + "_" + am.group(1).toLowerCase())
                                : it.expr;
                        outNames.add(it.alias != null ? it.alias : base);
                    }
                    List<jian.core.Column> outCols = new ArrayList<>();
                    for (int ci = 0; ci < outNames.size(); ci++) {
                        SelectItem it = spec.items.get(ci);
                        boolean isCount = it.expr.toLowerCase().matches("(?i)count\\s*\\(.*");
                        outCols.add(isCount
                                ? new jian.core.LongColumn(outNames.get(ci), new long[]{0})
                                : new jian.core.DoubleColumn(outNames.get(ci), new double[]{Double.NaN}));
                    }
                    preProjection = DataFrame.ofColumnsDirect(outCols);
                }
                grouped = selectColumns(preProjection, spec);
            } else {
                // 因为"含 ? / CASE 才走 parseSelectWithAgg"的门关会让纯算术表达式
                // (如 "(salary + 1000) AS total")走 parseSelectSimple,后者正则要求开头 \w+,
                // 对 ( 前缀失败,产出 alias==null 的 SelectItem,被 selectColumns 静默丢弃(列消失),
                // 所以无条件走 parseSelectWithAgg —— 它能识别 col / col AS alias / 聚合 / 表达式列
                // 全形态,分支收敛,降低"两套解析器分歧"风险。
                spec = parseSelectWithAgg(selectPart);
                preProjection = afterWhere;
                grouped = selectColumns(afterWhere, spec);
            }
        }
        // DISTINCT 去重(SELECT DISTINCT 语义:作用于全部选中列,ORDER BY/LIMIT 之前)
        if (distinct) grouped = grouped.dropDuplicates();

        // ORDER BY(支持引用未选中列 —— SQL 语义 ORDER BY 作用于投影前的行集)
        // 优先投影后排序(可引用别名/表达式列名);order 列在投影结果缺失但投影前存在时,
        // 先在投影前行集排序再投影(select/assign 保序,行对应关系不乱)
        // stops 里补 OFFSET(Oracle 标准分页):因为 "ORDER BY salary DESC OFFSET 1 ROWS
        // FETCH FIRST 1 ROWS ONLY" 的 OFFSET 段被吞进排序列名会报
        // "ORDER BY 列不存在:[salary DESC OFFSET 1 ROWS]",所以提前止住
        String orderBy = extractClause(s, "ORDER BY", new String[]{"LIMIT", "FETCH", "OFFSET"});
        if (orderBy != null) {
            String[] parts = orderBy.split(",");
            String[] cols = new String[parts.length];
            boolean[] ascs = new boolean[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                boolean desc = p.toUpperCase().endsWith(" DESC");
                cols[i] = desc ? p.substring(0, p.length() - 5).trim() : (p.toUpperCase().endsWith(" ASC") ? p.substring(0, p.length() - 4).trim() : p);
                // ORDER BY 列名剥反引号
                cols[i] = SqlPreprocessor.normalizeSqlExpr(cols[i]);
                ascs[i] = !desc;
            }
            boolean allInProjected = true;
            for (String c : cols) if (grouped.columnIndex(c) < 0) { allInProjected = false; break; }
            if (allInProjected) {
                grouped = grouped.sortBy(cols, ascs);
            } else {
                boolean allInPre = preProjection != null;
                for (String c : cols) if (preProjection == null || preProjection.columnIndex(c) < 0) { allInPre = false; break; }
                if (!allInPre) {
                    throw new IllegalArgumentException("ORDER BY 列不存在:" + java.util.Arrays.toString(cols)
                        + ";投影结果列:" + grouped.columnNames()
                        + ",投影前列:" + (preProjection == null ? "[]" : preProjection.columnNames()));
                }
                grouped = selectColumns(preProjection.sortBy(cols, ascs), spec);
            }
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

    // ┌─ What : AS 别名重命名辅助(聚合列 + 普通列)
    // │  Why  : 若不重命名 alias,SELECT 类别 AS 分类 的结果列仍叫 类别,
    // │         而表达式列 (x+1) AS total 却生效 —— 库内自相矛盾,且 HAVING/ORDER BY 无法引用别名
    // │  Who  : executeSelect(GROUP BY 分支聚合别名先于 HAVING)+ selectColumns(投影后收尾)
    // │  When : executeSelect 处理 GROUP BY 聚合别名与投影收尾时
    // │  Where: jian-dsl/SqlEngine.java
    // │  How  : 数据走向:SELECT items → 提取 {底层列名 → 别名} 映射 → Column.rename 逐列改名
    // │         → DataFrame.ofColumnsDirect 重建。源列不存在的项跳过(容错:该列可能已被上游改名)
    /** 聚合项正则(预编译;UCC:聚合参数支持中文)。 */
    private static final Pattern AGG_ITEM_RE =
            Pattern.compile("(?i)(\\w+)\\((\\*|[\\w.]+)\\)", Pattern.UNICODE_CHARACTER_CLASS);

    /** 已知聚合函数名(selectColumns 分类与 executeSelect 聚合检测同口径;防普通函数误入聚合分支)。 */
    private static final java.util.Set<String> AGG_FNS = java.util.Set.of(
            "sum", "avg", "mean", "count", "min", "max", "median", "std", "var", "first", "last", "nunique");

    /** 从 SELECT items 提取聚合别名映射 {col_fn 底层名 → alias}(仅聚合项且带别名)。 */
    private static Map<String, String> aggAliasMap(AggSpec spec, DataFrame df) {
        Map<String, String> m = new LinkedHashMap<>();
        for (SelectItem it : spec.items) {
            if (it.alias == null) continue;
            Matcher am = AGG_ITEM_RE.matcher(it.expr);
            if (am.find()) {
                String fn = am.group(1).toLowerCase();
                String col = am.group(2);
                // count(*) 的底层输出列是 哨兵_count(count(1) 实现),同样要 rename 成别名
                String outName = col.equals("*") ? COUNT_STAR_SENTINEL + "_count" : (col + "_" + fn);
                if (df.columnIndex(outName) >= 0 && !outName.equals(it.alias)) m.put(outName, it.alias);
            }
        }
        return m;
    }

    /** 按映射重命名列(源列不存在的项跳过),返回新 DataFrame。 */
    private static DataFrame renameColumns(DataFrame df, Map<String, String> renames) {
        if (renames.isEmpty()) return df;
        List<jian.core.Column> newCols = new ArrayList<>();
        for (String name : df.columnNames()) {
            String to = renames.get(name);
            newCols.add(to != null ? df.getColumn(name).rename(to) : df.getColumn(name));
        }
        return DataFrame.ofColumnsDirect(newCols);
    }

    /**
     * 抽取 WHERE/HAVING 里的 (SELECT ...) 子查询的职责已拆至 {@link SqlSubqueryExpander}
     * (§3.1 单文件 600 行红线;字面量跳过与集合运算分发的职责随之迁移,行为不变)。
     */

    /** 解析 FROM 子句(支持 this / ${name} / INNER|LEFT|RIGHT|FULL OUTER JOIN,链式多表 JOIN)。 */
    private static FromClause parseFrom(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        // OFFSET 也作结束关键字(否则 "FROM ${t} OFFSET 2" 会把 OFFSET 段吞进数据源)
        Matcher m = Pattern.compile("\\bFROM\\s+(.+?)(\\bWHERE\\b|\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bLIMIT\\b|\\bOFFSET\\b|\\bFETCH\\b|$)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        if (!m.find()) {
            return new FromClause(defaultDf, null);
        }
        String fromText = m.group(1).trim();

        // 链式多表 JOIN:逐个识别 "X JOIN ${Y} ON <onText>" 段
        // join 段正则:可选 OUTER 前缀的 (LEFT|RIGHT|FULL [OUTER])? JOIN + 数据源 + ON 关键字。
        // 正则只负责定位段头,ON 子句文本交 SqlJoinClauseParser 解析 ——
        // 因为只取第一对 a.x = b.y 会把 AND 附加条件与 USING 多列
        // (expandUsing 生成的 AND 链)静默丢弃,导致连接行数错误。
        // UCC:ON 条件的表别名/列名支持中文
        Pattern joinP = Pattern.compile(
                "\\s+(LEFT|RIGHT|FULL(?:\\s+OUTER)?|INNER)?\\s*JOIN\\s+(\\$\\{\\w+\\}|\\w+)\\s+ON\\s+",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);

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

        // 从 firstJoin 开始扫每个 JOIN 段,逐个 merge(链式)
        // 关键变量变化:current 从左源起,每处理一个 JOIN 段就被 merge 结果替换;
        //             onEnd 从"下一个 JOIN 段头"或文本末尾取,作为本段 ON 子句终点
        int searchFrom = firstJoin;
        while (searchFrom < fromText.length()) {
            Matcher joinM = joinP.matcher(fromText);
            if (!joinM.find(searchFrom)) break;
            String howKw = joinM.group(1);
            String rightRef = joinM.group(2);
            // ON 子句文本 = ON 关键字之后到下一个 JOIN 段头(链式)或 FROM 段末尾
            Matcher nextJoin = joinP.matcher(fromText);
            int onEnd = nextJoin.find(joinM.end()) ? nextJoin.start() : fromText.length();
            String onText = fromText.substring(joinM.end(), onEnd).trim();
            String how = howKw == null ? "inner" : howKw.toUpperCase().replaceAll("\\s+", " ").replace(" OUTER", "");
            if (how.equals("FULL")) how = "outer";
            DataFrame rightDf = resolveSource(rightRef, defaultDf, bindings);
            current = SqlJoinClauseParser.join(current, rightDf, how, onText);
            searchFrom = onEnd;
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
        // 未知数据源直接报错(不静默当作 this),提示合法写法
        throw new IllegalArgumentException("无法识别的数据源 '" + src + "',支持:this / DUAL / ${name} 占位");
    }

    /**
     * 解析 LIMIT/OFFSET。返回 {limit, offset};limit = -1 表示无 LIMIT。
     * 支持三种分页写法(规范 07 §2.3):
     *   PG/MySQL:{@code LIMIT n [OFFSET m]}(也支持独立 {@code OFFSET m [ROWS]});
     *   Oracle:{@code OFFSET m ROWS FETCH FIRST n ROWS ONLY} 或 {@code ROWNUM <= n}。
     * 策略说明:LIMIT 与 FETCH 并存时优先 LIMIT(后写的 FETCH 不覆盖);
     * 同一 SQL 同时写两种分页属矛盾写法,取第一个解析到的分页方式。
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

    /** 提取 SELECT 列部分(FROM 之前);先剥 -- 行注释(防列名解析被注释文本干扰)。 */
    private static String extractSelect(String sql) {
        String s = stripLineComments(sql);
        Matcher m = Pattern.compile("\\bSELECT\\s+(.+?)\\bFROM\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
        if (!m.find()) throw new IllegalArgumentException("SELECT 必须含 FROM");
        return m.group(1).trim();
    }

    /** 剥离 -- 行注释(字符串字面量内的 -- 不剥)。 */
    private static String stripLineComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                char q = c;
                sb.append(c); i++;
                while (i < n) {
                    sb.append(sql.charAt(i));
                    if (sql.charAt(i) == q) {
                        if (i + 1 < n && sql.charAt(i + 1) == q) { sb.append(q); i += 2; continue; }
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
                sb.append('\n');
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 带 agg 的 SELECT(SUM/MEAN/COUNT/MIN/MAX/MEDIAN/STD/VAR/FIRST/LAST + 普通列)。
     *  UCC:三处标识符正则全开 UNICODE_CHARACTER_CLASS,
     *  列名 / 聚合参数 / AS 别名支持中文(因为 \w 只匹配 ASCII,中文列名会直接抛
     *  "SELECT 无法识别的列/表达式",可用性不可接受)。 */
    private static AggSpec parseSelectWithAgg(String selectPart) {
        // selectPart 形如 "city, avg(salary) AS avg_sal, count(*) AS cnt"
        List<SelectItem> items = new ArrayList<>();
        Map<String, String> aggMap = new LinkedHashMap<>();
        // 用括号感知切分
        List<String> parts = splitComma(selectPart);
        List<String> groupCols = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            // SELECT * 特判(与 parseSelectSimple 一致)
            if (t.equals("*")) {
                items.add(new SelectItem("*", null));
                continue;
            }
            Matcher am = Pattern.compile("(?i)(\\w+)\\((\\*|[\\w.]+)\\)\\s*(?:AS\\s+)?(\\w+)?",
                    Pattern.UNICODE_CHARACTER_CLASS).matcher(t);
            if (am.matches()) {
                String fn = am.group(1).toLowerCase();
                String col = am.group(2);
                String alias = am.group(3) != null ? am.group(3) : fn + "_" + col;
                if (col.equals("*")) {
                    // 因为把「别名」当 key 塞 aggMap 后,GroupBy.agg 会对 map key 做
                    // 列查找而报「列 "别名" 不存在」,所以 count(*) 走哨兵列 + count 实现:
                    // executeSelect 检测到哨兵 key 时先 assign 全 1 常量列,
                    // count(哨兵) = 组行数(LONG,对齐 SQL/pandas)。
                    String prev = aggMap.putIfAbsent(COUNT_STAR_SENTINEL, "count");
                    if (prev != null && !prev.equals("count")) {
                        throw new IllegalArgumentException("SELECT 聚合别名冲突:count(*)(" + prev + ")");
                    }
                } else {
                    aggMap.put(col, fn);
                }
                items.add(new SelectItem(t, alias));
            } else {
                Matcher rm = Pattern.compile("(\\w+)\\s*(?:AS\\s+(\\w+))?",
                        Pattern.UNICODE_CHARACTER_CLASS).matcher(t);
                if (rm.matches()) {
                    items.add(new SelectItem(rm.group(1), rm.group(2)));
                    groupCols.add(rm.group(1));
                } else {
                    // 表达式列(CASE WHEN 转的三元 / 算术表达式 / 字面量)
                    // 形如 "(cond ? v1 : v2) AS alias" / "(a + b) AS sum" / "'hello' AS greeting"
                    Matcher em = Pattern.compile("(.+?)\\s+(?:AS\\s+)?(\\w+)$",
                            Pattern.UNICODE_CHARACTER_CLASS).matcher(t);
                    if (em.matches()) {
                        // 表达式列:alias 必须有(否则用户无法引用)
                        items.add(new SelectItem(em.group(1).trim(), em.group(2)));
                        // 表达式列不参与 groupBy,但也不报错(在 selectColumns 用 PrattEngine 评估)
                    } else {
                        // 因为静默跳过会让用户拿到少列结果而无提示,所以无法识别的 SELECT 项抛 IAE 并带该项文本
                        throw new IllegalArgumentException("SELECT 无法识别的列/表达式:「" + t + "」"
                            + "(支持:列名 / 聚合 fn(col) / 表达式 AS alias)");
                    }
                }
            }
        }
        return new AggSpec(items, aggMap);
    }

    /**
     * 按 SELECT items 在结果 df 上选列(带 alias 重命名)。
     *
     * <p>识别顺序(每项必居其一,消灭静默丢列):
     * <ol>
     *   <li>纯列名({@code columnIndex >= 0})→ 加入 cols;{@code *} → 展开为全部列;</li>
     *   <li>聚合 {@code fn(col)}(含 {@code count(*)})→ 现有底层列名/别名兜底逻辑;</li>
     *   <li>其余一律按<b>表达式项</b>处理(不再要求 {@code startsWith("(")},
     *       {@code salary + 1000 AS total} 无括号同样求值产出),必须有 alias(解析层要求);</li>
     *   <li>表达式求值失败/引用不存在的列 → 抛 IAE(带项文本与原因,applyExprColumn 包装);</li>
     *   <li>未知列名(非聚合、非表达式、columnIndex&lt;0、无 alias)→ 抛 IAE
     *       "SELECT 列不存在:xxx"(不静默跳过,拼错列名立刻暴露,防止返回缺列结果无提示)。</li>
     * </ol>
     * 投影门控:cols 为空但 exprItems 非空时,结果投影为表达式别名列
     * ({@code SELECT (a+b) AS s} 只返回 s 列)。
     */
    private static DataFrame selectColumns(DataFrame df, AggSpec spec) {
        if (spec.items.size() == 1 && spec.items.get(0).expr.equals("*")) return df;
        List<String> cols = new ArrayList<>();
        // 表达式列(CASE 转的三元 / 算术 / 字符串字面量):用 PrattEngine 评估每行,加为新列
        List<SelectItem> exprItems = new ArrayList<>();
        for (SelectItem it : spec.items) {
            if (it.expr.equals("*")) {
                // SELECT *, expr 混写:* 展开为当前全部列(单 * 在方法入口已提前返回)
                cols.addAll(df.columnNames());
            } else if (df.columnIndex(it.expr) >= 0) {
                // ① 纯列名
                cols.add(it.expr);
            } else {
                // ② 聚合 fn(col):非括号开头 + 命中已知聚合函数名(防 abs(x)+1 类普通表达式误入)
                Matcher am = AGG_ITEM_RE.matcher(it.expr.trim());
                if (!it.expr.startsWith("(") && am.find() && AGG_FNS.contains(am.group(1).toLowerCase())) {
                    String col = am.group(2);
                    // count(*) 底层列 = 哨兵_count(count(1) 实现);已 rename 场景走 alias 兜底
                    String outName = col.equals("*") ? COUNT_STAR_SENTINEL + "_count" : (col + "_" + am.group(1).toLowerCase());
                    if (df.columnIndex(outName) >= 0) cols.add(outName);
                    else if (it.alias != null && df.columnIndex(it.alias) >= 0) cols.add(it.alias);
                    else throw new IllegalArgumentException("SELECT 聚合列不存在:" + it.expr
                            + ";结果列:" + df.columnNames());
                } else if (it.alias != null) {
                    // ③ 表达式项:无括号要求;alias 必须有(解析层已要求,无 alias 走 ⑤ 报错)
                    exprItems.add(it);
                } else {
                    // ⑤ 未知列:彻底消灭静默返回全表
                    throw new IllegalArgumentException("SELECT 列不存在:" + it.expr
                            + ";表列:" + df.columnNames());
                }
            }
        }
        // 处理表达式列:在投影前的完整行集上评估(确保表达式能访问所有列);
        // 求值失败(含引用不存在的列)由 applyExprColumn 包装为带项文本与原因的 IAE 向上抛
        DataFrame result = df;
        for (SelectItem it : exprItems) {
            result = applyExprColumn(result, it.expr, it.alias);
        }
        // 再 select 需要的列(简单列 + 表达式产生的 alias 列)
        // 表达式 alias 并入投影目标 —— cols 原本为空时,投影收窄为表达式列
        // (SELECT (salary + 1000) AS total 只返回 total;若用 !cols.isEmpty() 门控会整表 + 新列全返回)
        for (SelectItem it : exprItems) {
            if (it.alias != null && result.columnIndex(it.alias) >= 0) cols.add(it.alias);
        }
        if (!cols.isEmpty()) {
            // SELECT c2,c2,c0 保留重复列(SQLite/pandas 同款三列;因为 LinkedHashSet 去重
            // 会静默丢失一列数据)。列名重复经 rename 加序号
            List<String> uniq = new ArrayList<>();
            java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            List<String> renamedTargets = new ArrayList<>();   // 需要改名回原名的目标(仅重复出现的)
            for (String c : cols) {
                int k = seen.merge(c, 1, Integer::sum);
                if (k == 1) uniq.add(c);
                else { uniq.add(c + "_" + k); renamedTargets.add(c + "_" + k); }
            }
            // 重复列:先 rename 原列的一个副本?DataFrame 列名唯一 —— 用 rename 把重复的第 k 次
            // 出现引用转成"克隆列"。实现:逐个重复目标,addColumn 克隆原列并命名,再 select
            for (int i = 0; i < cols.size(); i++) {
                if (renamedTargets.contains(uniq.get(i))) {
                    Column src = result.getColumn(cols.get(i));
                    Column dup = src.rename(uniq.get(i));
                    result = result.withColumnClone(cols.get(i), uniq.get(i));
                }
            }
            result = result.select(uniq.toArray(new String[0]));
        }
        // 普通列/聚合列的 AS 别名收尾重命名(表达式列已按 alias 命名,跳过)
        // 聚合项:GROUP BY 路径已在上游改过名(源列缺失自动跳过),此处兜底全局聚合路径
        Map<String, String> renames = new LinkedHashMap<>();
        for (SelectItem it : spec.items) {
            // 表达式项跳过判据用"在 exprItems 里"(无括号表达式同跳)
            if (it.alias == null || exprItems.contains(it)) continue;
            Matcher am = AGG_ITEM_RE.matcher(it.expr);
            if (am.find()) {
                // count(*) 的底层列同样是 哨兵_count,一并 rename 成别名
                String outName = am.group(2).equals("*")
                        ? COUNT_STAR_SENTINEL + "_count" : (am.group(2) + "_" + am.group(1).toLowerCase());
                if (result.columnIndex(outName) >= 0 && result.columnIndex(it.alias) < 0) renames.put(outName, it.alias);
            } else if (result.columnIndex(it.expr) >= 0 && result.columnIndex(it.alias) < 0) {
                renames.put(it.expr, it.alias);
            }
        }
        return renameColumns(result, renames);
    }

    /**
     * 对 df 应用一个表达式列(算术 / 三元 / 比较 / 逻辑 / 字符串字面量 全支持)。
     *
     * <p>实现说明:直接委托 {@link PrattEngine#eval},它原生支持算术 / 三元(嵌套)/ 比较 / 逻辑 /
     * 谓词 / 字面量,且经 PrattEngine 自身的测试套件验证 —— 复用已验证能力,不另写解析补丁
     * (自写三元正则 + 字面量替换 + 算术 stub 只能覆盖三元,算术表达式会恒返 null)。
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
        // 数据走向:expr → normalizeSqlExpr(裸 = / <> / 反引号归一化;
        // CASE 展开的三元 cond 里常见 SQL 等号,不归一化 Pratt 词法器直接拒)
        // → "alias = expr" → PrattEngine.eval → 加新列的 df
        // PrattEngine.evalSingle 内部:parse(expr) → 逐行 ast.eval(RowBinding) → Schema.infer → assign+astype
        try {
            return PrattEngine.eval(df, alias + " = " + SqlPreprocessor.normalizeSqlExpr(expr), Params.EMPTY);
        } catch (IllegalArgumentException e) {
            // 因为直接上抛的报错不含是哪一列,所以异常包装带 alias 上下文
            throw new IllegalArgumentException("表达式列 " + alias + " 求值失败(" + expr + "): " + e.getMessage(), e);
        }
    }


    /**
     * 提取子句(括号感知:只在深度 0 匹配关键字,子查询括号内的同名关键字不算)。
     * 例:WHERE ... (SELECT ... WHERE x) GROUP BY → 只在第一个外层 WHERE 和 GROUP BY 间取文本。
     * 扫描同时跳过字符串字面量,防字符串内的关键字误当子句边界。
     */
    private static String extractClause(String sql, String keyword, String[] stops) {
        // 找 keyword 起始(深度 0)
        String kw = keyword.toLowerCase();
        int kwStart = findTopLevelKeyword(sql, kw, 0);
        if (kwStart < 0) return null;
        int contentStart = kwStart + keyword.length();
        // 从 contentStart 开始扫,找第一个深度 0 的 stop(跳过字符串字面量)
        int depth = 0;
        String lowerTail = sql.substring(contentStart).toLowerCase();
        for (int i = 0; i < lowerTail.length(); i++) {
            char c = lowerTail.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '\'' || c == '"') {
                char q = c; i++;
                while (i < lowerTail.length()) {
                    if (lowerTail.charAt(i) == q) {
                        if (i + 1 < lowerTail.length() && lowerTail.charAt(i + 1) == q) { i += 2; continue; }
                        break;
                    }
                    i++;
                }
            }
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

    /** 在 sql 的 fromIndex 之后找 keyword 的第一个出现位置(深度 0,即不在括号内;跳过字符串字面量)。 */
    private static int findTopLevelKeyword(String sql, String lowerKeyword, int fromIndex) {
        String lower = sql.toLowerCase();
        int depth = 0;
        for (int i = fromIndex; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (c == '\'' || c == '"') {
                char q = c; i++;
                while (i < lower.length()) {
                    if (lower.charAt(i) == q) {
                        if (i + 1 < lower.length() && lower.charAt(i + 1) == q) { i += 2; continue; }
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (depth == 0 && lower.startsWith(lowerKeyword, i)
                    && (i == 0 || !Character.isLetterOrDigit(lower.charAt(i - 1)))
                    && (i + lowerKeyword.length() == lower.length()
                        || !Character.isLetterOrDigit(lower.charAt(i + lowerKeyword.length())))) {
                return i;
            }
        }
        return -1;
    }


    /** 括号 + 引号感知的逗号切分:字符串内的逗号不被切分。 */
    private static List<String> splitComma(String s) {
        List<String> r = new ArrayList<>();
        int depth = 0; int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '\'' || c == '"') {
                char q = c; i++;
                while (i < s.length()) {
                    if (s.charAt(i) == q) {
                        if (i + 1 < s.length() && s.charAt(i + 1) == q) { i += 2; continue; }
                        break;
                    }
                    i++;
                }
            }
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
