package jian.dsl;

import jian.core.DataFrame;

import java.util.*;

// ┌─ What : SqlEngineInterface —— 通用 L3 SQL 引擎接口(2026-08-09 阶段 E 重新设计)
// │  Why  : 用户决策"L3 接口抽象要通用,不拘泥于一两个特定库"
// │         原 v1 接口(execute+name 两方法)太薄,无法表达引擎能力面
// │         新设计抽象 SQL 引擎的通用能力,不绑定 jOOQ/JSqlParser/Calcite 任何特有概念
// │  Who  : 由 SqlEngines.current() 取当前引擎;Dsl.sql/Dsl.update 委托
// │  When : 每次 SQL 调用
// │  Where: jian-dsl/SqlEngineInterface.java
// │  How  : 接口设计要点(6 条):
// │           ① 能力探测(Capability):上层经 supports() 知道引擎能否处理窗口/CTE/DML 等
// │           ② DQL/DML 分离:query(SELECT)与 update(INSERT/UPDATE/DELETE)语义清晰
// │           ③ 引擎元信息:名称/版本/方言/线程安全,让用户做选择
// │           ④ 占位与参数化:${name} 表占位 + 未来 ? 参数占位,兼容多种引擎风格
// │           ⑤ 不抛 SQLException:内存引擎无 checked 异常,统一 IllegalArgumentException
// │           ⑥ 默认方法兜底:复杂能力默认不支持(返回 false/抛 IAE),实现方按需 override
/**
 * 通用 L3 SQL 引擎接口(库无关设计)。
 *
 * <p><b>设计哲学</b>:接口抽象"SQL 引擎应具备的能力",不预设底层是正则解析、JSqlParser、
 * Calcite、还是任何未来库。具体引擎实现({@link SqlRegexEngine} 默认;
 * 用户自定义(如 Calcite/未来库))按需 override 能力方法。
 *
 * <p>能力面分两组:
 * <ul>
 *   <li><b>DQL(数据查询)</b>:{@link #query} 执行 SELECT,返回 DataFrame</li>
 *   <li><b>DML(数据修改)</b>:{@link #update} 执行 INSERT/UPDATE/DELETE,返回受影响行数;
 *       对纯内存引擎(DQL-only),默认抛 {@link UnsupportedOperationException}</li>
 * </ul>
 *
 * <p><b>能力探测</b>:上层经 {@link #supports(Capability)} 判断当前引擎是否支持某语法,
 * 据此选择执行路径(不支持则走回退或提示用户切换引擎)。
 *
 * <p><b>切换引擎</b>:
 * <pre>{@code
 * SqlEngines.useRegex();        // 默认正则引擎(纯 JDK,零依赖)
 * SqlEngines.useCustom(impl);   // 用户自定义(如 Calcite/DuckDB/未来库)
 * }</pre>
 *
 * @see SqlEngines 引擎注册中心
 * @see Capability 引擎能力枚举
 */
public interface SqlEngineInterface {

    // ======================== 引擎元信息(实现方提供)========================

    /**
     * 引擎名(识别/调试/日志用)。
     * @return String 短名,如 "regex"/"jsqlparser"/"calcite"/"custom";非 null
     */
    String name();

    /**
     * 引擎版本(用于兼容性判断)。
     * @return String 形如 "1.0"/"5.3"/"unknown";默认 "unknown"
     */
    default String version() { return "unknown"; }

    /**
     * 引擎描述(给用户看的可读说明)。
     * @return String 一句话,如 "纯 JDK 正则引擎,覆盖 SELECT/WHERE/GROUP/JOIN/UNION/CTE/CASE"
     */
    default String description() { return name() + " v" + version(); }

    /**
     * 是否线程安全(Web 场景能否并发使用)。
     * <p>已知:
     * <ul>
     *   <li>SqlRegexEngine:✅ 纯 JDK 无状态,线程安全</li>
     * </ul>
     * @return boolean true=可并发;false=单线程或需外部同步
     */
    default boolean isThreadSafe() { return false; }

    /**
     * 支持的 SQL 方言列表。
     * @return Set<SqlDialect> 默认 {DEFAULT};某些库支持更多(ORACLE/MYSQL/POSTGRESQL)
     */
    default Set<SqlDialect> supportedDialects() {
        return EnumSet.of(SqlDialect.DEFAULT);
    }

    // ======================== 能力探测(关键)========================

    /**
     * SQL 语法能力枚举(库无关)。
     * <p>每种能力对应一类 SQL 语法,实现方按实际支持情况 override supports。
     */
    enum Capability {
        /** SELECT * / SELECT col / SELECT col AS alias / SELECT DISTINCT */
        SELECT_BASIC,
        /** SELECT agg(col) AS alias 聚合(mean/sum/count/min/max/std/var/...) */
        SELECT_AGG,
        /** SELECT expr AS alias 表达式列(CASE WHEN / 算术 / 三元) */
        SELECT_EXPR,
        /** WHERE 完整运算符(>/</>=/<=/==/!=/and/or/not/in/between/like/is null) */
        WHERE_FULL,
        /** GROUP BY / HAVING */
        GROUP_HAVING,
        /** ORDER BY(ASC/DESC,多列) */
        ORDER_BY,
        /** LIMIT / OFFSET / FETCH FIRST / ROWNUM(方言) */
        LIMIT_OFFSET,
        /** JOIN(INNER/LEFT/RIGHT/FULL OUTER,链式多表) */
        JOIN,
        /** USING(col) / CROSS JOIN / NATURAL JOIN */
        JOIN_ADVANCED,
        /** UNION ALL / UNION 去重 */
        UNION,
        /** INTERSECT / EXCEPT / MINUS */
        SET_OPS,
        /** 子查询(WHERE IN/标量比较) */
        SUBQUERY,
        /** WITH name AS (subquery) CTE */
        CTE,
        /** 派生表 FROM (SELECT ...) */
        DERIVED_TABLE,
        /** CASE WHEN cond THEN v1 ELSE v2 END */
        CASE_WHEN,
        /** 窗口函数 ROW_NUMBER/RANK/LAG/LEAD OVER PARTITION BY */
        WINDOW_FUNCTIONS,
        /** INSERT INTO ... VALUES / INSERT INTO ... SELECT */
        INSERT,
        /** UPDATE ... SET ... WHERE */
        UPDATE,
        /** DELETE FROM ... WHERE */
        DELETE,
        /** 类型转换 CAST(expr AS type) */
        CAST,
        /** ? 参数化占位(防 SQL 注入) */
        PARAMETERIZED,
    }

    /**
     * 检测当前引擎是否支持某能力。
     * <p>默认实现按"基础能力支持,高级能力不支持"兜底;具体引擎 override 精确报告。
     * @param cap Capability 待测能力;非 null
     * @return boolean true=支持;false=不支持(上层应走回退或抛友好提示)
     */
    default boolean supports(Capability cap) {
        // 默认:基础 DQL 支持,高级/窗口/DML 不支持(实现方按需 override)
        return switch (cap) {
            case SELECT_BASIC, SELECT_AGG, WHERE_FULL, GROUP_HAVING,
                 ORDER_BY, LIMIT_OFFSET, JOIN, UNION, SUBQUERY -> true;
            default -> false;
        };
    }

    /**
     * 一次性返回所有支持的能力(便于上层展示/调试)。
     * @return Set<Capability> 支持的能力集合;默认基于 {@link #supports} 逐项判定
     */
    default Set<Capability> capabilities() {
        Set<Capability> all = EnumSet.noneOf(Capability.class);
        for (Capability c : Capability.values()) if (supports(c)) all.add(c);
        return all;
    }

    // ======================== DQL:数据查询(SELECT)========================

    /**
     * 执行 SELECT 查询(返回 DataFrame)。
     * <p>这是 L3 SQL 的核心入口,所有 SELECT 语句经此进入。
     * @param defaultDf DataFrame 主表(FROM this/DUAL 时用);无主表静态入口时 null
     * @param sql       String SQL 语句(含 ${} 占位);非 null
     * @param bindings  Map<String,DataFrame> ${占位名} → DataFrame 绑定;非 null
     * @param dialect   SqlDialect SQL 方言;非 null
     * @return DataFrame SELECT 结果(行/列由 SQL 决定)
     * @throws IllegalArgumentException SQL 解析失败 / 不支持的语法 / 占位缺失
     */
    DataFrame query(DataFrame defaultDf, String sql,
                    Map<String, DataFrame> bindings, SqlDialect dialect);

    /**
     * query 便捷重载:方言 DEFAULT。
     */
    default DataFrame query(DataFrame defaultDf, String sql, Map<String, DataFrame> bindings) {
        return query(defaultDf, sql, bindings, SqlDialect.DEFAULT);
    }

    // ======================== DML:数据修改(INSERT/UPDATE/DELETE)========================

    /**
     * 执行 DML(INSERT/UPDATE/DELETE),返回受影响行数。
     * <p>对纯内存 DataFrame,DML 修改入参 defaultDf 对应的 DataFrame(若引擎支持)。
     * <p>对 DQL-only 引擎(如默认正则引擎),默认抛 UnsupportedOperationException。
     * @param defaultDf DataFrame 目标表
     * @param sql       String INSERT/UPDATE/DELETE 语句
     * @param bindings  Map<String,DataFrame> ${占位名} → DataFrame 绑定
     * @param dialect   SqlDialect SQL 方言
     * @return int 受影响行数(INSERT 行数 / UPDATE 命中行数 / DELETE 删除行数)
     * @throws UnsupportedOperationException 当前引擎不支持 DML({@link #supports}(INSERT/UPDATE/DELETE)==false)
     */
    default int update(DataFrame defaultDf, String sql,
                       Map<String, DataFrame> bindings, SqlDialect dialect) {
        throw new UnsupportedOperationException(
            "引擎 " + name() + " 不支持 DML(INSERT/UPDATE/DELETE);"
            + "请切换支持 DML 的引擎(SqlEngines.useXxx),或用 DataFrame 直接修改");
    }

    // ======================== 向后兼容(原 execute 接口,委托 query)========================

    /**
     * 执行 SQL(原 v1 接口,向后兼容)。
     * <p>自动判断:SELECT 走 {@link #query};INSERT/UPDATE/DELETE 走 {@link #update}(结果忽略)。
     * @deprecated 新代码请用 {@link #query}(DQL)或 {@link #update}(DML)显式区分
     */
    @Deprecated
    default DataFrame execute(DataFrame defaultDf, String sql,
                              Map<String, DataFrame> bindings, SqlDialect dialect) {
        String upper = sql.trim().toUpperCase();
        if (upper.startsWith("SELECT") || upper.startsWith("WITH")) {
            return query(defaultDf, sql, bindings, dialect);
        }
        if (upper.startsWith("INSERT") || upper.startsWith("UPDATE") || upper.startsWith("DELETE")) {
            update(defaultDf, sql, bindings, dialect);
            return defaultDf;  // 返回修改后的表(DML 影响的 DataFrame)
        }
        // 非 SELECT/INSERT/UPDATE/DELETE(如 BEGIN ... END 等 PL/SQL)→ 默认抛
        throw new IllegalArgumentException(
            "引擎 " + name() + " 仅支持 SELECT/WITH/INSERT/UPDATE/DELETE 入口;"
            + "实际 SQL 开头:" + upper.substring(0, Math.min(20, upper.length())));
    }
}
