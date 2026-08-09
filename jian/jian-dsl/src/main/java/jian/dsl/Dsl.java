package jian.dsl;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ┌─ What : Dsl —— jian-dsl 顶层门面:df.query() / df.eval() / df.sql() / Jian.sql()(对齐规范 07 §2)
// │  Why  : 规范 07;L1/L2 完整 Pratt parser(支持三元/参数/算术/逻辑),L3 简化 SQL 子集(自写,不依赖 ANTLR)
// │  Who  : 用户经 df.query/eval/sql 调用
// │  When : DataFrame 上的表达式/SQL 操作
// │  Where: jian-dsl/Dsl.java(顶层门面,内部委托 PrattEngine + SqlEngine)
/**
 * jian-dsl 顶层门面。三档能力(规范 07 §2):
 *
 * <ul>
 *   <li>{@link #query} L1 布尔过滤(对齐 df.query);</li>
 *   <li>{@link #eval} L2 表达式求值(对齐 df.eval,派生新列,支持三元);</li>
 *   <li>{@link #sql} L3 类 SQL 子集(SELECT/WHERE/GROUP BY/HAVING/ORDER BY/LIMIT/JOIN/UNION ALL)。</li>
 * </ul>
 *
 * <p>用法:
 * <pre>{@code
 * // L1
 * DataFrame adults = Dsl.query(df, "age > 18 && city == 'SH'");
 *
 * // L2 派生列
 * Dsl.eval(df, "total = price * qty");
 * Dsl.eval(df, "grade = score >= 90 ? 'A' : 'B'");
 *
 * // L3 SQL
 * DataFrame r = Dsl.sql("SELECT city, avg(salary) AS avg_sal FROM ${t} GROUP BY city ORDER BY avg_sal DESC LIMIT 10", df);
 * }</pre>
 *
 * <p><b>方言</b>:L3 通过 {@link SqlDialect} 切换(Oracle/PG/MySQL);分页 ROWNUM/LIMIT/FETCH FIRST
 * 三种写法都认;空值 NVL/COALESCE/IFNULL 都认(规范 §2.4)。
 */
public final class Dsl {

    private Dsl() {}

    /**
     * L1:布尔过滤(对齐 df.query)。
     *
     * @param df DataFrame 数据源,非 null
     * @param expr String 布尔表达式,非 null;支持比较/逻辑/算术/三元/谓词
     * @return DataFrame 满足 expr 的行组成的新 DataFrame
     */
    public static DataFrame query(DataFrame df, String expr) {
        return PrattEngine.query(df, expr, Params.EMPTY);
    }

    /**
     * L1:带参数的布尔过滤。
     *
     * @param df DataFrame 数据源,非 null
     * @param expr String 布尔表达式,非 null;可含 ${name} 占位由 params 展开
     * @param params Params 命名参数绑定,非 null(无参传 Params.EMPTY)
     * @return DataFrame 满足 expr 的行组成的新 DataFrame
     */
    public static DataFrame query(DataFrame df, String expr, Params params) {
        return PrattEngine.query(df, expr, params);
    }

    /**
     * L2:派生新列(对齐 df.eval)。返回新 DataFrame(原 df + 新列)。
     *
     * @param df DataFrame 数据源,非 null
     * @param expr String 赋值表达式,非 null;形如 "name = expr",分号分隔可派生多列
     * @return DataFrame 加了新列后的 DataFrame(原 df 不变)
     */
    public static DataFrame eval(DataFrame df, String expr) {
        return PrattEngine.eval(df, expr, Params.EMPTY);
    }

    /**
     * L2:带参数的派生新列。
     *
     * @param df DataFrame 数据源,非 null
     * @param expr String 赋值表达式,非 null;可含 ${name} 占位由 params 展开
     * @param params Params 命名参数绑定,非 null(无参传 Params.EMPTY)
     * @return DataFrame 加了新列后的 DataFrame(原 df 不变)
     */
    public static DataFrame eval(DataFrame df, String expr, Params params) {
        return PrattEngine.eval(df, expr, params);
    }

    /**
     * L3:SQL 子集。${名} 作表名占位,DataFrame 按出现顺序绑定。
     * <p>单表多表统一 API:sql 在前,DataFrame 参数在后。
     * <pre>{@code
     * // 单表
     * Dsl.sql("SELECT dept, mean(salary) FROM ${t} GROUP BY dept", df);
     *
     * // 多表 JOIN
     * Dsl.sql("SELECT * FROM ${a} JOIN ${b} ON a.id=b.id", df1, df2);
     *
     * // 三表链式
     * Dsl.sql("SELECT * FROM ${a} JOIN ${b} ON a.x=b.x JOIN ${c} ON b.y=c.y", df1, df2, df3);
     *
     * // UNION ALL
     * Dsl.sql("SELECT * FROM ${x} UNION ALL SELECT * FROM ${y}", df1, df2);
     * }</pre>
     *
     * @param sql SQL 字符串,${名} 作表名占位(名字纯可读,执行时按出现顺序绑定 DataFrame)
     * @param dfs DataFrame 参数,按 ${名} 在 SQL 中的首次出现顺序绑定
     * @return DataFrame SQL 执行结果
     * @throws IllegalArgumentException dfs 为空,或 ${} 占位数与 dfs 个数不匹配时抛出
     */
    public static DataFrame sql(String sql, DataFrame... dfs) {
        if (dfs.length == 0) {
            throw new IllegalArgumentException("sql() 至少需要一个 DataFrame 参数");
        }
        // 解析 SQL 中 ${名} 的出现顺序 → 对应 dfs[0], dfs[1]...
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{(\\w+)}").matcher(sql);
        while (m.find()) {
            String name = m.group(1);
            if (!names.contains(name)) names.add(name);
        }
        Map<String, DataFrame> bindings = new java.util.LinkedHashMap<>();
        if (!names.isEmpty() && names.size() != dfs.length) {
            throw new IllegalArgumentException(
                    "SQL 中有 " + names.size() + " 个 ${} 占位(" + names
                            + "),但传入了 " + dfs.length + " 个 DataFrame;两者须一一对应");
        }
        for (int i = 0; i < names.size(); i++) {
            bindings.put(names.get(i), dfs[i]);
        }
        // 无占位 → bindings 为空,由引擎抛"静态入口无主表"的明确报错
        // 2026-08-09 阶段 E:经 SqlEngines.current() 走可插拔引擎(默认 SqlRegexEngine)
        return SqlEngines.current().execute(null, sql, bindings, SqlDialect.DEFAULT);
    }

    /**
     * L3:指定方言(sql 在前 + 方言参数)。
     *
     * @param sql SQL 字符串,${名} 作表名占位(名字纯可读,执行时按出现顺序绑定 DataFrame)
     * @param dialect SqlDialect SQL 方言(ORACLE/POSTGRESQL/MYSQL/DEFAULT),非 null
     * @param dfs DataFrame 参数,按 ${名} 在 SQL 中的首次出现顺序绑定
     * @return DataFrame SQL 执行结果
     * @throws IllegalArgumentException dfs 为空,或 ${} 占位数与 dfs 个数不匹配时抛出
     */
    public static DataFrame sql(String sql, SqlDialect dialect, DataFrame... dfs) {
        if (dfs.length == 0) {
            throw new IllegalArgumentException("sql() 至少需要一个 DataFrame 参数");
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{(\\w+)}").matcher(sql);
        while (m.find()) {
            String name = m.group(1);
            if (!names.contains(name)) names.add(name);
        }
        Map<String, DataFrame> bindings = new java.util.LinkedHashMap<>();
        if (!names.isEmpty() && names.size() != dfs.length) {
            throw new IllegalArgumentException(
                    "SQL 中有 " + names.size() + " 个 ${} 占位(" + names
                            + "),但传入了 " + dfs.length + " 个 DataFrame;两者须一一对应");
        }
        for (int i = 0; i < names.size(); i++) {
            bindings.put(names.get(i), dfs[i]);
        }
        // 无占位 → bindings 为空,由 SqlEngine 抛"静态入口无主表"的明确报错(见 executeSelect)
        return SqlEngines.current().execute(null, sql, bindings, dialect);
    }
}
