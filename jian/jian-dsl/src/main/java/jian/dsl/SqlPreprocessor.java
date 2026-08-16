package jian.dsl;

import jian.core.DataFrame;

import java.util.*;
import java.util.regex.*;

// ┌─ What : SqlPreprocessor —— L3 SQL 新语法预处理(CASE/CTE/派生表/集合运算)
// │  Why  : §3.1.1.1 内聚规则:与 SqlRegexEngine 同职能的"语法预处理扩展",拆文件避免主类超 600 行
// │         把 SqlEngine 不识别的高级语法转换为它可执行的形式(参考 JSqlParser 实现思路,自写避免其 BUG)
// │  Who  : 由 SqlRegexEngine.execute 调用
// │  When : 任何含新语法(CASE/CTE/派生表/集合运算)的 SQL 进入引擎时
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

    // ┌─ What : RecursiveQuery —— CTE/派生表内部子查询的递归执行器
    // │  Why  : 因为递归深度计数若挂在引擎实例字段(共享单例计数器),
    // │         多线程嵌套深度会相加 → 虚假"嵌套过深",
    // │         所以改为引擎闭包把 depth 作为方法参数传递(调用链独享,线程安全),
    // │         预处理器经本接口回调引擎,不感知深度细节
    // │  Who  : SqlRegexEngine.queryRecursive(构造闭包)→ expandCTE / expandDerivedTables(调用)
    // │  When : 预处理遇到 CTE body / 派生表子查询时
    // │  Where: jian-dsl/SqlPreprocessor.java
    // │  How  : 数据走向:(defaultDf, 子查询 SQL, bindings) → 引擎递归执行 → 子查询结果 DataFrame。
    /**
     * CTE/派生表内部子查询的递归执行器(深度计数走引擎闭包的方法参数,无共享可变状态)。
     */
    @FunctionalInterface
    public interface RecursiveQuery {
        /**
         * 递归执行一条子查询 SQL。
         *
         * @param defaultDf DataFrame 主表(子查询里 this 引用),可 null
         * @param sql String 子查询 SQL,非 null
         * @param bindings Map&lt;String,DataFrame&gt; ${占位} 绑定,非 null
         * @return DataFrame 子查询结果
         */
        DataFrame query(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings);
    }

    /** 默认递归执行器:经当前引擎入口重入(等价旧路径;深度计数由引擎自身兜底)。 */
    private static final RecursiveQuery DEFAULT_RECURSOR =
            (defaultDf, sql, bindings) -> SqlEngines.current().query(defaultDf, sql, bindings, SqlDialect.DEFAULT);

    /**
     * 预处理:把 CASE/CTE/派生表/USING/CROSS JOIN 转换为 SqlEngine 可执行形式。
     * <p>注意:不处理集合运算(UNION/INTERSECT/EXCEPT),那些由 hasSetOperation + executeSetOperations 处理。
     * @param defaultDf DataFrame 主表(用于 CTE/派生表内 this 引用);可 null
     * @param sql 参数;非 null
     * @param bindings 参数;非 null
     */
    public static PreprocessedSql preprocess(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        return preprocess(defaultDf, sql, bindings, DEFAULT_RECURSOR);
    }

    /**
     * 预处理(指定递归执行器;引擎把深度计数闭包在 recursor 里,线程安全)。
     *
     * @param defaultDf DataFrame 主表(用于 CTE/派生表内 this 引用);可 null
     * @param sql String SQL 文本,非 null
     * @param bindings Map&lt;String,DataFrame&gt; ${占位} 绑定,非 null
     * @param recursor RecursiveQuery CTE/派生表子查询的递归执行器,非 null
     * @return PreprocessedSql 转换后的 SQL 与扩充后的绑定
     */
    public static PreprocessedSql preprocess(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings,
                                             RecursiveQuery recursor) {
        Map<String, DataFrame> expandedBindings = new LinkedHashMap<>(bindings);
        String processed = sql;

        // 1. CTE 展开(WITH name AS (subquery) SELECT ...)
        processed = expandCTE(defaultDf, processed, expandedBindings, recursor);
        // 2. CASE WHEN ... THEN ... ELSE ... END → (cond ? v1 : v2);无 ELSE → (cond ? v1 : null)
        processed = expandCaseWhen(processed);
        // 3. 派生表 FROM (SELECT ...) AS t → 提取子查询为 ${__derived_N__}
        processed = expandDerivedTables(defaultDf, processed, expandedBindings, recursor);
        // 4. USING(col) → ON a.col = b.col(L3:多列转 AND 链)
        processed = expandUsing(processed);
        // 5. CROSS JOIN:直接做笛卡尔积,结果注入 binding,SQL 改为 FROM ${cross_N}
        processed = expandCrossJoinToCartesian(defaultDf, processed, expandedBindings);

        return new PreprocessedSql(processed, expandedBindings);
    }

    /**
     * 向后兼容:defaultDf=null 的预处理(无主表场景)。
     * @param sql 参数;非 null
     * @param bindings 参数;非 null
     */
    public static PreprocessedSql preprocess(String sql, Map<String, DataFrame> bindings) {
        return preprocess(null, sql, bindings);
    }

    /**
     * 检测 SQL 是否含集合运算(非 UNION ALL)。
     * @param sql 参数;非 null
     */
    public static boolean hasSetOperation(String sql) {
        // 因为 WHERE s == "UNION" 里的字符串关键词若被误判为集合运算,SQL 会被错误拆分
        // 后报"字符串未闭合",所以先剥字符串字面量再匹配
        // (与 Engine.checkReadOnly 的 scrubSqlLiterals 同思路,本地轻量版:引号段整体置空)
        String upper = scrubStringLiterals(sql).toUpperCase();
        // UNION 后不跟 ALL,或 INTERSECT,或 EXCEPT(MINUS 是 Oracle 别名)
        return upper.matches("(?is).*\\bUNION\\b(?!\\s+ALL).*")
            || upper.matches("(?is).*\\bINTERSECT\\b.*")
            || upper.matches("(?is).*\\bEXCEPT\\b.*")
            || upper.matches("(?is).*\\bMINUS\\b.*");
    }

    /** 剥除字符串字面量('...'/"...",'' 与 "" 翻倍转义)为等长空格(关键词检测前置)。 */
    private static String scrubStringLiterals(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                char q = c;
                sb.append(' '); i++;
                while (i < n) {
                    if (sql.charAt(i) == q) {
                        if (i + 1 < n && sql.charAt(i + 1) == q) { sb.append("  "); i += 2; continue; }
                        sb.append(' '); i++;
                        break;
                    }
                    sb.append(' '); i++;
                }
            } else { sb.append(c); i++; }
        }
        return sb.toString();
    }

    /**
     * 执行集合运算:把 SQL 拆成多个 SELECT,逐一执行,然后做集合运算。
     * <p>支持:UNION(去重)/UNION ALL(直接)/INTERSECT/EXCEPT/MINUS。
     */
    /**
     * @param defaultDf DataFrame 主表(无占位时的数据源),可 null
     * @param sql String 含集合运算的 SQL,非 null
     * @param bindings Map&lt;String,DataFrame&gt; ${占位} 绑定,非 null
     * @param dialect SqlDialect SQL 方言,非 null
     * @return DataFrame 集合运算结果
     */
    public static DataFrame executeSetOperations(DataFrame defaultDf, String sql,
                                                  Map<String, DataFrame> bindings, SqlDialect dialect) {
        // 因为裸正则会把子查询内的 UNION 也当外层运算符,外层 FROM/WHERE 被切坏
        // (WHERE v IN (SELECT ... UNION ALL SELECT ...) 报"子查询括号未闭合"),
        // 所以切分点只在「字符串字面量外 + 括号深度 0」处 ——
        // 伪代码:
        //   1. 逐字符扫描:引号区(含 '' 翻倍)整体跳过;括号深度>0 跳过
        //   2. 深度 0 处按「先长后短」试匹配 UNION ALL / UNION / INTERSECT / EXCEPT / MINUS
        //      (词边界完整匹配),命中则记下运算符位置
        //   3. 按切分点把 SQL 分成 parts(SELECT 文本)与 ops(运算符)
        //   4. 无切分点 → 原样交 SqlEngine.execute(默认路径)
        //   5. 依次执行各 SELECT 并左折叠应用运算符
        String lower = sql.toLowerCase();
        int n = lower.length();
        java.util.List<Integer> cuts = new ArrayList<>();
        java.util.List<String> ops = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < n; i++) {
            char c = lower.charAt(i);
            if (c == '\'' || c == '"') {
                char q = c;
                i++;
                while (i < n) {
                    if (lower.charAt(i) == q) {
                        if (i + 1 < n && lower.charAt(i + 1) == q) { i += 2; continue; }  // '' 翻倍
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth != 0 || (i > 0 && Character.isLetterOrDigit(lower.charAt(i - 1)))) continue;
            // 先长后短:UNION ALL 必须先于 UNION 试,否则 "UNION ALL" 被切成 "UNION" + 残留 "ALL"
            String kw = matchSetOpKeyword(lower, i, n);
            if (kw != null) {
                cuts.add(i);
                ops.add(kw.toUpperCase());
                i += kw.length() - 1;   // 加 for 的 i++ 共跳过整个关键字
            }
        }
        if (cuts.isEmpty()) {
            // 无(顶层)集合运算 → 走默认路径
            return SqlEngine.execute(defaultDf, sql, bindings, dialect);
        }
        java.util.List<String> parts = new ArrayList<>();
        int last = 0;
        for (int k = 0; k < cuts.size(); k++) {
            parts.add(sql.substring(last, cuts.get(k)).trim());
            last = cuts.get(k) + ops.get(k).length();
        }
        parts.add(sql.substring(last).trim());

        // 依次执行每个 SELECT 部分
        DataFrame result = SqlEngine.execute(defaultDf, parts.get(0), bindings, dialect);
        for (int i = 1; i < parts.size(); i++) {
            DataFrame right = SqlEngine.execute(defaultDf, parts.get(i), bindings, dialect);
            String op = ops.get(i - 1);
            result = applySetOp(result, right, op);
        }
        return result;
    }

    /**
     * 在 lower 的 i 位置按「先长后短」匹配集合运算关键字(完整词边界)。
     * @return 命中的关键字原文(小写);未命中返回 null
     */
    private static String matchSetOpKeyword(String lower, int i, int n) {
        for (String kw : new String[]{"union all", "union", "intersect", "except", "minus"}) {
            if (lower.startsWith(kw, i)
                    && (i + kw.length() == n || !Character.isLetterOrDigit(lower.charAt(i + kw.length())))) {
                return kw;
            }
        }
        return null;
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
                // 两表的行级交集:用左表行签名在右表查找。
                // ① 结果 dropDuplicates —— SQL 集合运算语义是去重的(SQLite/pandas 同),
                //   [1,1,2] INTERSECT 不能保留两行 1;
                // ② 签名按列【位置】取值 —— 用左表列名取右表值时,两侧列名不同会直接崩
                requireSameColumnCount(left, right);
                java.util.Set<String> rightSigs = new HashSet<>();
                for (int i = 0; i < right.rowCount(); i++) {
                    rightSigs.add(sigOf(right, i));
                }
                boolean[] mask = new boolean[left.rowCount()];
                for (int i = 0; i < left.rowCount(); i++) {
                    mask[i] = rightSigs.contains(sigOf(left, i));
                }
                return left.filter(mask).dropDuplicates();
            }
            case "EXCEPT":
            case "MINUS": {
                // 左表 - 右表(右表中没有的左表行);去重 + 按位置签名(同 INTERSECT)
                requireSameColumnCount(left, right);
                java.util.Set<String> rightSigs = new HashSet<>();
                for (int i = 0; i < right.rowCount(); i++) {
                    rightSigs.add(sigOf(right, i));
                }
                boolean[] mask = new boolean[left.rowCount()];
                for (int i = 0; i < left.rowCount(); i++) {
                    mask[i] = !rightSigs.contains(sigOf(left, i));
                }
                return left.filter(mask).dropDuplicates();
            }
            default:
                throw new IllegalArgumentException("未知集合运算:" + op);
        }
    }

    /**
     * 集合运算两侧列数校验(签名按位置取值的前提)。
     * @throws IllegalArgumentException 列数不等时(SQLite 同场景报
     *         "SELECTs to the left and right of INTERSECT do not have the same number of result columns")
     */
    private static void requireSameColumnCount(DataFrame left, DataFrame right) {
        if (left.columnCount() != right.columnCount()) {
            throw new IllegalArgumentException("集合运算两侧列数不一致:左侧 " + left.columnCount()
                    + " 列(" + left.columnNames() + ") vs 右侧 " + right.columnCount()
                    + " 列(" + right.columnNames() + ")(对齐 SQLite 报错语义)");
        }
    }

    /**
     * 行签名(按列<b>位置</b>拼接值作 hash key;两侧列数已校验相等,与列名无关,
     * 两侧同列不同名也能对上)。1 与 1.0 的字符串形态不同("1" vs "1.0")不判等 ——
     * 数值跨 dtype 等价属 v2 议题。
     */
    private static String sigOf(DataFrame df, int row) {
        // 类型感知签名:旧实现裸 append,null 被写成 "null"、
        // DOUBLE NaN 被写成 "NaN" —— 字符串列的字面 "null"/"NaN" 与真缺失互撞,
        // INTERSECT/EXCEPT 误判等。现在:① 每列带 dtype 标签(DOUBLE 的 NaN 与 STRING
        // 的 "NaN" 不同签名);② 缺失用 \u0000N 哨兵(isNull 权威判定,NULL=NULL 判等,
        // SQL 集合运算语义);③ 值中的 \u0000 转义为 \u0000S,防字面值伪造哨兵。
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < df.columnCount(); c++) {
            jian.core.Column col = df.getColumn(df.columnNames().get(c));
            sb.append(col.dtype().name()).append('\u0002');
            if (col.isNull(row)) {
                sb.append("\u0000N");
            } else {
                String s = String.valueOf(df.get(row, c));
                if (s.indexOf('\u0000') >= 0) s = s.replace("\u0000", "\u0000S");
                sb.append(s);
            }
            sb.append('\u0001');
        }
        return sb.toString();
    }

    // ======================== 运算符归一化 =========================

    // ┌─ What : normalizeSqlExpr —— 把 SQL 谓词/表达式里的标准运算符归一化为 Pratt DSL 形式
    // │  Why  : SQL 标准 `=`(等号)与 `<>`(不等)直接喂 Pratt 词法器会抛
    // │         "无法识别的字符 '='" / "意外的 token '>'";反引号 `col` 标识符同样被拒。
    // │         用户写 "WHERE 类别 = '食品'" 是最自然的 SQL 直觉,却必挂
    // │  Who  : SqlEngine(WHERE/HAVING/表达式列)与 SqlDml(UPDATE/DELETE 的 WHERE)调用
    // │  When : 任何 SQL 子句文本喂 PrattEngine 之前
    // │  Where: jian-dsl/SqlPreprocessor.java
    // │  How  : 伪代码:
    //           1. 逐字符扫描;遇单/双引号进入字面量模式,整体原样复制('' 翻倍转义保留)
    //           2. 遇 `<>` → 输出 `!=`(跳两字符)
    //           3. 遇 `=`:若已是 == / >= / <= / != 的组成部分则原样,否则输出 `==`
    //           4. 遇反引号 ` → 剥除(MySQL 标识符引用,DSL 不需要)
    // │         关键变量变化:
    //           - 入参 "类别 <> '食''品' OR `金额` = 5"
    //           - 出参 "类别 != '食''品' OR 金额 == 5"(字面量内部零改动)
    // │         逻辑路线:
    //           路径 A(引号内)→ 原样复制直到闭合引号('' 翻倍续走);
    //           路径 B(<> / 裸 = / 反引号)→ 按上表替换/剥除;
    //           路径 C(其余字符)→ 原样追加。
    /**
     * SQL 谓词/表达式 → Pratt DSL 可执行形式(纯函数,无副作用)。
     *
     * <p>转换规则(字符串字面量感知,引号内不改):
     * <ul>
     *   <li>{@code <>} → {@code !=};裸 {@code =} → {@code ==}({@code == >= <= !=} 保持不动)</li>
     *   <li>反引号 {@code `col`} → {@code col}(MySQL 标识符引用剥除)</li>
     * </ul>
     *
     * @param expr String SQL 谓词/表达式文本,非 null
     * @return String Pratt DSL 可执行文本
     */
    public static String normalizeSqlExpr(String expr) {
        StringBuilder sb = new StringBuilder(expr.length() + 8);
        int n = expr.length();
        for (int i = 0; i < n; i++) {
            char c = expr.charAt(i);
            if (c == '\'' || c == '"') {
                char q = c; sb.append(c); i++;
                while (i < n) {
                    sb.append(expr.charAt(i));
                    if (expr.charAt(i) == q) {
                        if (i + 1 < n && expr.charAt(i + 1) == q) { sb.append(q); i += 2; continue; }
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '<' && i + 1 < n && expr.charAt(i + 1) == '>') { sb.append("!="); i++; continue; }
            if (c == '=') {
                char prev = i > 0 ? expr.charAt(i - 1) : 0;
                char next = i + 1 < n ? expr.charAt(i + 1) : 0;
                // 已是 == / >= / <= / != 的组成部分 → 原样;SQL 裸 = → DSL ==
                if (next == '=' || prev == '>' || prev == '<' || prev == '!' || prev == '=') sb.append(c);
                else sb.append("==");
                continue;
            }
            if (c == '`') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    // ======================== 内部:语法转换 ========================

    // 1. CTE
    private static final Pattern CTE_PATTERN = Pattern.compile(
        "(?is)^\\s*WITH\\s+(.+?)\\s+AS\\s*\\((.+?)\\)\\s*(SELECT\\s+.*)$");

    private static String expandCTE(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings,
                                    RecursiveQuery recursor) {
        Matcher m = CTE_PATTERN.matcher(sql);
        if (!m.find()) return sql;
        String cteName = m.group(1).trim();
        String cteBody = m.group(2).trim();
        String rest = m.group(3).trim();
        // 执行 CTE body(走完整引擎递归,传 defaultDf 让 body 里的 this 可解析;
        // recursor 闭包携带深度计数,线程安全)
        DataFrame cteResult = recursor.query(defaultDf, cteBody, bindings);
        bindings.put(cteName, cteResult);
        // 把 rest 中裸 cteName 替换为 ${cteName} —— 仅限 FROM/JOIN 后的「表引用」位置
        // (全文裸名替换会把 SELECT 列表里的同名列也替换掉:如 CTE 名「结」、
        // SELECT 结 FROM 结 → SELECT ${结} → 列解析必挂);
        // 限定 (FROM|JOIN)\s+名 后,SELECT 列/别名/值里的同名 token 不再误伤。
        // (?U):中文等 Unicode 标识符的 \b 词边界按 Unicode 语义(防「结」误匹配「结果」开头);
        // ${名} 形态因名字前是 { 不满足 \s+名 前缀,天然不会被二次包裹
        // 因为替换产物 "${t}" 恰是 Matcher 命名组引用语法,replaceAll 会抛
        // "No group with name {t}"(裸名引用 CTE 必崩),所以动态名要避开组引用解析。注意不能用
        // quoteReplacement 包整串 —— 它会把 $1/$2 组引用也转义成字面量,丢失 "FROM " 前缀;
        // 函数式 replaceAll 里手工拼 group(1)/group(2),动态名不经组引用解析。
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(?iU)(\\bFROM\\b|\\bJOIN\\b)(\\s+)" + Pattern.quote(cteName) + "(?![\\w}])");
        // 本 JDK 的函数式 replaceAll 仍走 appendExpandedReplacement($ 组引用仍被解析),
        // 故动态段用 quoteRetention 转义 —— $1/$2 需要解析,${t} 必须转义
        return p.matcher(rest).replaceAll(mr -> mr.group(1) + mr.group(2)
                + Matcher.quoteReplacement("${" + cteName + "}"));
    }

    // 2. CASE WHEN(嵌套形态由 expandCaseWhen 内联处理:最内层优先)

    // ┌─ What : expandCaseWhen —— CASE WHEN → Pratt 三元;无 ELSE 分支补 null(SQL 语义)
    // │  Why  : 嵌套 CASE 由最内层优先展开;无 ELSE 形态必须支持 ——
    // │         SQL 规范 CASE 无 ELSE 时结果为 NULL,无 ELSE 的 CASE 若
    // │         不被任何模式匹配,会原样漏到 Pratt 层报"无法识别的字符"(CASE WHEN v > 1
    // │         THEN 'x' END AS b 是最常见写法之一)
    // │  Who  : preprocess 第 2 步
    // │  When : 预处理任何含 CASE 的 SQL
    // │  Where: jian-dsl/SqlPreprocessor.java
    // │  How  : 伪代码:
    //           1. 循环:先试「带 ELSE」最内层模式(THEN/ELSE 分支均不含 CASE),
    //              展开为 (cond ? v1 : v2);命中则 reset 续扫
    //           2. 带 ELSE 无命中 → 试「无 ELSE」模式,展开为 (cond ? v1 : null)
    //           3. 两者都无命中 → 返回;带上限防病态输入死循环
    // │         关键变量变化:result 逐轮被替换为少一个 CASE 的文本,直到不含 CASE;
    //           v2 在无 ELSE 路径恒为 null 字面量。
    // │         逻辑路线:路径 A(命中带 ELSE)→ 替换续扫;路径 B(命中无 ELSE)→
    //           替换续扫;路径 C(均无命中)→ 返回 result。
    private static String expandCaseWhen(String sql) {
        // 因为嵌套 CASE 若不按**最内层**优先展开,(THEN/ELSE 均贪婪/非贪婪混合)的模式
        // 会把内层 CASE 当普通值文本消费,展开产物仍含未展开的 CASE
        // (PrattEngine 无法解析,报"缺 )"),所以取 THEN/ELSE 分支均不含 "CASE" 的
        // 最内层 CASE;循环直到无 CASE 可匹配(带上限防病态输入死循环)。
        String result = sql;
        int guard = 0;
        Pattern withElse = Pattern.compile(
            "(?is)CASE\\s+WHEN\\s+((?:(?!CASE).)+?)\\s+THEN\\s+((?:(?!CASE).)+?)\\s+ELSE\\s+((?:(?!CASE).)+?)\\s+END");
        Pattern noElse = Pattern.compile(
            "(?is)CASE\\s+WHEN\\s+((?:(?!CASE).)+?)\\s+THEN\\s+((?:(?!CASE).)+?)\\s+END");
        while (guard++ < 100) {
            Matcher m = withElse.matcher(result);
            if (m.find()) {
                String ternary = "(" + m.group(1).trim() + " ? " + m.group(2).trim()
                        + " : " + m.group(3).trim() + ")";
                result = result.substring(0, m.start()) + ternary + result.substring(m.end());
                continue;
            }
            // 无 ELSE → else 分支补 null(SQL 语义:缺 ELSE 结果为 NULL)
            Matcher ne = noElse.matcher(result);
            if (ne.find()) {
                String ternary = "(" + ne.group(1).trim() + " ? " + ne.group(2).trim() + " : null)";
                result = result.substring(0, ne.start()) + ternary + result.substring(ne.end());
                continue;
            }
            break;
        }
        return result;
    }

    // 3. 派生表 FROM (SELECT ...)
    // 因为懒惰正则 (.+?) 在子查询含任何括号时会截断(FROM (SELECT dept, sum(salary) AS s ...)
    // 被截成 SELECT dept, sum(salary),报"SELECT 必须含 FROM"),而派生表带聚合是
    // 最常见形态,所以改为手工扫描:从 ( 起按括号深度配平到匹配 ),再吞掉可选 AS 别名 —— 不再用正则。

    // ┌─ What : expandDerivedTables —— 派生表括号配平提取(手工扫描,字面量感知)
    // │  Who  : preprocess 第 3 步
    // │  Where: jian-dsl/SqlPreprocessor.java
    // │  How  : 伪代码:
    //           1. 逐字符扫描;引号区(含 '' 翻倍)整体跳过复制
    //           2. 词边界命中 "FROM" 且其后(跳空白)是 '(' → 从该括号按深度配平找匹配 ')'
    //              (同样跳过引号区);子查询文本 = 括号内(须以 SELECT 开头,否则按普通文本复制)
    //           3. 递归执行子查询 → 结果挂 ${__derived_N__} 绑定;输出 "FROM ${__derived_N__}"
    //           4. 吞掉闭合括号后的可选「AS 别名」/裸别名(词边界,替换后别名被丢弃,同旧行为)
    //           5. 扫描游标跳过整个派生表段,继续(嵌套派生表由子查询自身的预处理递归处理)
    // │         关键变量变化:i 扫描游标;counter 占位序号;out 累积改写后的 SQL。
    // │         逻辑路线:路径 A(FROM + (SELECT)→ 提取替换;路径 B(普通字符/字面量)→ 原样复制;
    //           路径 C(括号未闭合)→ IAE。
    private static String expandDerivedTables(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings,
                                              RecursiveQuery recursor) {
        StringBuilder out = new StringBuilder(sql.length());
        int n = sql.length();
        int i = 0;
        int counter = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                // 字符串字面量整体原样复制('' 翻倍保留)
                char q = c;
                out.append(c);
                i++;
                while (i < n) {
                    out.append(sql.charAt(i));
                    if (sql.charAt(i) == q) {
                        if (i + 1 < n && sql.charAt(i + 1) == q) {
                            out.append(q);
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                i++;
            } else if (isWordAt(sql, i, "FROM")) {
                int j = skipWhitespace(sql, i + 4);
                if (j < n && sql.charAt(j) == '(') {
                    int close = findMatchingParen(sql, j);
                    String subquery = sql.substring(j + 1, close).trim();
                    if (subquery.regionMatches(true, 0, "SELECT", 0, 6)) {
                        counter++;
                        String placeholder = "__derived_" + counter + "__";
                        // 递归执行器闭包携带深度计数(线程安全)
                        DataFrame subResult = recursor.query(defaultDf, subquery, bindings);
                        bindings.put(placeholder, subResult);
                        out.append("FROM ${").append(placeholder).append("}");
                        i = close + 1;
                        // 吞掉可选别名:AS t / t(替换后别名丢弃,与旧正则行为一致)
                        int k = skipWhitespace(sql, i);
                        int p = k;
                        if (isWordAt(sql, p, "AS")) p = skipWhitespace(sql, p + 2);
                        int e = identifierEnd(sql, p);
                        if (e > p) i = e;
                        continue;
                    }
                }
                out.append(c);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 从 open(指向 '(')按括号深度配平找匹配的 ')'。
     * @param sql String SQL 文本,非 null
     * @param open int 左括号下标
     * @return int 匹配的右括号下标
     * @throws IllegalArgumentException 括号未闭合时
     */
    private static int findMatchingParen(String sql, int open) {
        int depth = 0;
        int n = sql.length();
        for (int i = open; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                char q = c;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == q) {
                        if (i + 1 < n && sql.charAt(i + 1) == q) { i += 2; continue; }
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new IllegalArgumentException("派生表括号未闭合:" + sql.substring(open, Math.min(open + 60, n)));
    }

    /** 词边界匹配:sql 在 i 处是否是完整的 word(大小写不敏感,前后均非字母数字)。 */
    private static boolean isWordAt(String sql, int i, String word) {
        if (!sql.regionMatches(true, i, word, 0, word.length())) return false;
        int n = sql.length();
        boolean beforeOk = i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1));
        int end = i + word.length();
        boolean afterOk = end >= n || !Character.isLetterOrDigit(sql.charAt(end));
        return beforeOk && afterOk;
    }

    /** 跳过空白,返回第一个非空白字符下标(到末尾返回 n)。 */
    private static int skipWhitespace(String sql, int i) {
        int n = sql.length();
        while (i < n && Character.isWhitespace(sql.charAt(i))) i++;
        return i;
    }

    /** 标识符结束下标(UCC:中文等 Unicode 字母/数字/_;增补平面按码点推进)。 */
    private static int identifierEnd(String sql, int i) {
        int n = sql.length();
        while (i < n) {
            int cp = sql.codePointAt(i);
            if (cp > 0xFFFF || Character.isLetterOrDigit(cp) || cp == '_') i += Character.charCount(cp);
            else break;
        }
        return i;
    }

    // 4. USING(col1, col2, ...) → ON a.col1 = b.col1 AND a.col2 = b.col2(L3:多列支持)
    // UCC:USING 列名支持中文
    private static final Pattern USING_PATTERN = Pattern.compile(
        "(?is)USING\\s*\\(\\s*([\\w\\s,]+?)\\s*\\)", Pattern.UNICODE_CHARACTER_CLASS);

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

    // 5. CROSS JOIN:直接做笛卡尔积(不走 SqlEngine JOIN 解析)
    // 策略:找 "CROSS JOIN ${right}" 段,做 left × right 笛卡尔积,结果存为 ${__cross_N__},
    //       把 SQL 改为 "FROM ${__cross_N__}"(无 JOIN)
    private static final Pattern CROSS_JOIN_PATTERN = Pattern.compile(
        "(?is)FROM\\s+(\\$\\{\\w+\\}|this|DUAL)\\s+CROSS\\s+JOIN\\s+\\$\\{(\\w+)}",
        Pattern.UNICODE_CHARACTER_CLASS);

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

    /** 解析 df 引用(${name} / this / DUAL)为实际 DataFrame。
     *  占位名正则加 UCC,中文占位名 ${表} 可解析。 */
    private static DataFrame resolveDfRef(DataFrame defaultDf, String ref, Map<String, DataFrame> bindings) {
        if (ref.equalsIgnoreCase("this") || ref.equalsIgnoreCase("DUAL")) return defaultDf;
        Matcher m = Pattern.compile("\\$\\{(\\w+)}", Pattern.UNICODE_CHARACTER_CLASS).matcher(ref);
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
