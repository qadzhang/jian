package jian.dsl;

import jian.core.DataFrame;

import java.util.*;

// ┌─ What : SqlRegexEngine —— 默认 SQL 引擎(纯 JDK,零依赖,可插拔接口的参考实现)
// │  Why  : 用户决策"L3 接口抽象,默认自写引擎,可插拔更换"
// │         覆盖 95% 用例,无外部库依赖,无 5.x 回归/线程不安全等风险
// │  Who  : 由 SqlEngines.current() 默认返回;Dsl.sql 委托
// │  When : Dsl.sql / SqlEngines.current().query() 被调用时(默认引擎)
// │  Where: jian-dsl/SqlRegexEngine.java
// │  How  : 数据走向:
// │           Dsl.sql(sql, dfs) → SqlEngines.current().query() → 本类
// │           → SqlPreprocessor.preprocess(CASE/CTE/派生表/集合运算/USING 转换)
// │           → SqlEngine.execute(正则解析 SELECT/WHERE/GROUP/JOIN/UNION ALL/子查询)
// │         关键变量:无状态(线程安全),所有数据流过方法参数
/**
 * 默认 SQL 引擎(纯 JDK,零依赖,基于既有正则 SqlEngine)。
 *
 * <p>这是 SqlEngineInterface 的参考实现,展示如何用最小依赖覆盖最大功能面。
 *
 * <p><b>支持能力</b>(精确报告):
 * <ul>
 *   <li>✅ SELECT_BASIC / SELECT_AGG / WHERE_FULL / GROUP_HAVING / ORDER_BY / LIMIT_OFFSET</li>
 *   <li>✅ JOIN(inner/left/right/full outer)/ UNION / UNION ALL / SUBQUERY(≤2 层)</li>
 *   <li>✅ CTE / DERIVED_TABLE / CASE_WHEN / SET_OPS(INTERSECT/EXCEPT/MINUS)</li>
 *   <li>✅ SELECT_EXPR(SELECT 列表表达式列,算术/三元/字符串字面量;CASE 经预处理转三元;
 *       无括号表达式(如 salary + 1000 AS total)同样支持,与回归测试一致)</li>
 *   <li>✅ INSERT / UPDATE / DELETE(内存 DML)</li>
 *   <li>⚠️ JOIN_ADVANCED(USING 单/多列;CROSS JOIN 有限支持;NATURAL 不支持)</li>
 *   <li>❌ WINDOW_FUNCTIONS(ROW_NUMBER/RANK/LAG/LEAD OVER;建议用 jian Resampler/colRank 替代)</li>
 *   <li>❌ CAST / PARAMETERIZED(? 占位)</li>
 * </ul>
 *
 * <p><b>线程安全</b>:✅ 真无状态:递归深度计数走方法参数(调用链独享),
 * 无任何实例可变字段,可并发使用(Web 场景友好)。
 */
public final class SqlRegexEngine implements SqlEngineInterface {

    @Override public String name() { return "regex"; }
    @Override public String version() { return "1.0"; }
    @Override public String description() {
        return "jian 自写正则引擎(纯 JDK,零依赖);覆盖 SELECT/WHERE/GROUP/JOIN/UNION/CTE/CASE/派生表/集合运算";
    }
    @Override public boolean isThreadSafe() { return true; }

    @Override
    /**
     * @param cap 参数;非 null
     */
    public boolean supports(Capability cap) {
        return switch (cap) {
            // 基础 DQL 全支持
            case SELECT_BASIC, SELECT_AGG, WHERE_FULL, GROUP_HAVING,
                 ORDER_BY, LIMIT_OFFSET, JOIN, UNION, SUBQUERY,
                 // 阶段 E 新增
                 CTE, DERIVED_TABLE, CASE_WHEN, SET_OPS,
                 // 阶段 E+ L3/L4 新增
                 JOIN_ADVANCED,
                 // DML(返回新 DataFrame,不破坏不可变)
                 INSERT, UPDATE, DELETE,
                 // SELECT_EXPR 支持(算术/三元/字符串字面量表达式列,
                 // 无括号表达式同样产出),声明与实现、测试一致
                 SELECT_EXPR -> true;
            // 高级 / 不支持
            case WINDOW_FUNCTIONS,
                 CAST, PARAMETERIZED -> false;
        };
    }

    /** 语义见 {@link SqlEngineInterface#query}(默认正则引擎实现)。
     * @param defaultDf DataFrame 主表(FROM this/DUAL 时用),可 null
     * @param sql String SQL 语句,非 null
     * @param bindings Map&lt;String,DataFrame&gt; ${占位} 绑定,非 null
     * @param dialect SqlDialect 方言,非 null
     * @return DataFrame 查询结果
     */
    @Override
    public DataFrame query(DataFrame defaultDf, String sql,
                            Map<String, DataFrame> bindings, SqlDialect dialect) {
        return queryRecursive(defaultDf, sql, bindings, dialect, 0);
    }

    // ┌─ What : queryRecursive —— 带递归深度的查询执行(深度走方法参数)
    // │  Why  : 防无限递归的深度计数若挂实例字段(SqlEngines.REGEX_DEFAULT 是
    // │         static final 共享单例,所有线程的 ThreadLocal 初值同一实例),
    // │         并发嵌套 CTE/子查询时多线程深度相加,虚假抛"嵌套过深(>3 层)"。
    // │         所以改为 depth 方法参数:每条查询的调用链独享计数,互不可见
    // │  Who  : query(入口,depth=0)+ preprocess 的 RecursiveQuery 闭包(每层 +1)
    // │  When : 每次 query / CTE / 派生表递归
    // │  Where: jian-dsl/SqlRegexEngine.java
    // │  How  : 数据走向:query(depth=0) → preprocess(recursor 闭包捕获 depth+1)
    // │         → CTE/派生表经 recursor 重入 queryRecursive(depth+1) → …
    // │         关键变量变化:depth 沿递归链 +1(单线程语义与旧计数器一致:
    // │         嵌套 4 层 CTE/子查询即 >3 抛);线程间零共享。
    // │         逻辑路线:
    //           路径 A(depth>3)→ IAE"嵌套过深"(防 WITH 嵌套无限递归);
    //           路径 B(含顶层集合运算)→ executeSetOperations;
    //           路径 C(默认)→ SqlEngine.execute。
    private DataFrame queryRecursive(DataFrame defaultDf, String sql,
                                     Map<String, DataFrame> bindings, SqlDialect dialect, int depth) {
        if (depth > 3) {
            throw new IllegalArgumentException("CTE/子查询嵌套过深(>3 层),疑似无限递归:"
                + sql.substring(0, Math.min(60, sql.length())) + "...");
        }
        // 递归执行器:CTE/派生表内部子查询经本闭包重入,深度 +1(调用链独享,线程安全)
        SqlPreprocessor.RecursiveQuery recursor =
                (df2, sql2, bind2) -> queryRecursive(df2, sql2, bind2, dialect, depth + 1);
        // 1. 预处理新语法(CASE/CTE/派生表/USING/CROSS JOIN)→ 转换为 SqlEngine 可执行形式
        // 传 defaultDf 让 CTE/派生表内部 this 引用可解析
        SqlPreprocessor.PreprocessedSql pp = SqlPreprocessor.preprocess(defaultDf, sql, bindings, recursor);
        // 2. 集合运算(UNION 去重/INTERSECT/EXCEPT)独立处理(SqlEngine 只支持 UNION ALL)
        if (SqlPreprocessor.hasSetOperation(pp.sql)) {
            return SqlPreprocessor.executeSetOperations(defaultDf, pp.sql, pp.bindings, dialect);
        }
        // 3. 委托给既有 SqlEngine(覆盖 SELECT/WHERE/GROUP/JOIN/UNION ALL/子查询)
        return SqlEngine.execute(defaultDf, pp.sql, pp.bindings, dialect);
    }

    /** 语义见 {@link SqlEngineInterface#update}(默认正则引擎实现,DML 经 SqlDml)。
     * @param defaultDf DataFrame 目标表,非 null
     * @param sql String INSERT/UPDATE/DELETE 语句,非 null
     * @param bindings Map&lt;String,DataFrame&gt; ${占位} 绑定,非 null
     * @param dialect SqlDialect 方言,非 null
     * @return int 受影响行数
     */
    @Override
    public int update(DataFrame defaultDf, String sql,
                      Map<String, DataFrame> bindings, SqlDialect dialect) {
        // DML 经 SqlDml 执行,返回受影响行数
        // 注:DataFrame 不可变,SqlDml 返回新 DataFrame;调用方需自行接收 execute 返回值
        String upper = sql.trim().toUpperCase();
        if (!upper.startsWith("INSERT") && !upper.startsWith("UPDATE") && !upper.startsWith("DELETE")) {
            throw new IllegalArgumentException("update 仅接受 INSERT/UPDATE/DELETE,实际:" + upper);
        }
        SqlDml.DmlResult r = SqlDml.execute(defaultDf, sql, bindings);
        return r.affectedRows;
    }
}
