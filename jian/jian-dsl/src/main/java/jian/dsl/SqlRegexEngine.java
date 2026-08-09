package jian.dsl;

import jian.core.DataFrame;

import java.util.*;

// ┌─ What : SqlRegexEngine —— 默认 SQL 引擎(纯 JDK,零依赖,可插拔接口的参考实现)
// │  Why  : 用户决策"L3 接口抽象,默认自写引擎,可插拔更换"
// │         覆盖 95% 用例,无外部库依赖,无 5.x 回归/线程不安全等风险
// │  Who  : 由 SqlEngines.current() 默认返回;Dsl.sql 委托
// │  When : 2026-08-09 阶段 E 落地
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
 *   <li>✅ INSERT / UPDATE / DELETE(内存 DML)</li>
 *   <li>⚠️ JOIN_ADVANCED(USING 单列简化;CROSS JOIN 有限支持;NATURAL 不支持)</li>
 *   <li>❌ SELECT_EXPR(SELECT 列表中的任意表达式,如算术/三元;CASE 通过预处理转换)</li>
 *   <li>❌ WINDOW_FUNCTIONS(ROW_NUMBER/RANK/LAG/LEAD OVER;建议用 jian Resampler/colRank 替代)</li>
 *   <li>❌ CAST / PARAMETERIZED(? 占位)</li>
 * </ul>
 *
 * <p><b>线程安全</b>:✅ 纯 JDK 无状态,可并发使用(Web 场景友好)。
 */
public final class SqlRegexEngine implements SqlEngineInterface {

    @Override public String name() { return "regex"; }
    @Override public String version() { return "1.0"; }
    @Override public String description() {
        return "jian 自写正则引擎(纯 JDK,零依赖);覆盖 SELECT/WHERE/GROUP/JOIN/UNION/CTE/CASE/派生表/集合运算";
    }
    @Override public boolean isThreadSafe() { return true; }

    @Override
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
                 INSERT, UPDATE, DELETE -> true;
            // 高级 / 不支持
            case SELECT_EXPR, WINDOW_FUNCTIONS,
                 CAST, PARAMETERIZED -> false;
        };
    }

    @Override
    public DataFrame query(DataFrame defaultDf, String sql,
                            Map<String, DataFrame> bindings, SqlDialect dialect) {
        // 1. 预处理新语法(CASE/CTE/派生表/USING/CROSS JOIN)→ 转换为 SqlEngine 可执行形式
        // 传 defaultDf 让 CTE/派生表内部 this 引用可解析
        SqlPreprocessor.PreprocessedSql pp = SqlPreprocessor.preprocess(defaultDf, sql, bindings);
        // 2. 集合运算(UNION 去重/INTERSECT/EXCEPT)独立处理(SqlEngine 只支持 UNION ALL)
        if (SqlPreprocessor.hasSetOperation(pp.sql)) {
            return SqlPreprocessor.executeSetOperations(defaultDf, pp.sql, pp.bindings, dialect);
        }
        // 3. 委托给既有 SqlEngine(覆盖 SELECT/WHERE/GROUP/JOIN/UNION ALL/子查询)
        return SqlEngine.execute(defaultDf, pp.sql, pp.bindings, dialect);
    }

    @Override
    public int update(DataFrame defaultDf, String sql,
                      Map<String, DataFrame> bindings, SqlDialect dialect) {
        // L7 实现(2026-08-09):DML 经 SqlDml 执行,返回受影响行数
        // 注:DataFrame 不可变,SqlDml 返回新 DataFrame;调用方需自行接收 execute 返回值
        String upper = sql.trim().toUpperCase();
        if (!upper.startsWith("INSERT") && !upper.startsWith("UPDATE") && !upper.startsWith("DELETE")) {
            throw new IllegalArgumentException("update 仅接受 INSERT/UPDATE/DELETE,实际:" + upper);
        }
        SqlDml.DmlResult r = SqlDml.execute(defaultDf, sql, bindings);
        return r.affectedRows;
    }
}
