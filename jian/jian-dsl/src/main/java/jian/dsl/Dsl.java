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
        // 绑定占位(CTE 宽容分支与 df.sql() 实例入口统一,见 bindPlaceholders)
        Map<String, DataFrame> bindings = bindPlaceholders(sql, dfs);
        // 无占位 → bindings 为空,由引擎抛"静态入口无主表"的明确报错
        // 经 SqlEngines.current() 走可插拔引擎(默认 SqlRegexEngine)
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
        Map<String, DataFrame> bindings = bindPlaceholders(sql, dfs);
        // 无占位 → bindings 为空,由 SqlEngine 抛"静态入口无主表"的明确报错(见 executeSelect)
        return SqlEngines.current().execute(null, sql, bindings, dialect);
    }

    // ┌─ What : bindPlaceholders —— 解析 SQL 中 ${} 占位并按出现顺序绑定 DataFrame(两入口共用)
    // │  Why  : 静态入口与实例入口的占位绑定行为必须一致:
    // │         `Jian.sql("WITH 明细 AS (...) ... ${明细}", df)` 在 CTE 模式下占位可多于 df
    // │         (多余占位由 CTE 预处理动态注入 binding),静态入口若不支持会误报
    // │         "2 占位 vs 1 df"。抽公共方法收敛(§3.1.1.1 内聚)
    // │  Who  : Dsl.sql 两个重载(静态入口)+ JianDslEngine.sql(实例入口)
    // │  When : 每次 SQL 调用的绑定阶段
    // │  Where: jian-dsl/Dsl.java
    // │  How  : 数据走向:sql → 提取去重占位名(UCC,支持中文 ${表})→ hasCTE 判定 → 绑定 map。
    // │         逻辑路线:
    // │           路径 A(无占位)→ 返回空 map(静态入口由引擎报"无主表";实例入口主表=this);
    // │           路径 B(WITH 开头,CTE)→ 占位数 ≥ df 数才合法,按序绑前 dfs.length 个
    // │             (CTE 名占位由 SqlPreprocessor.expandCTE 注入,不需用户传);
    // │           路径 C(普通 SQL)→ 占位数必须 == df 数,否则抛 IAE(防静默少绑)。
    /**
     * 解析 ${} 占位并绑定(包私有,JianDslEngine 共用)。
     * @param sql String SQL 文本,非 null
     * @param dfs DataFrame[] 按占位首次出现顺序对应
     * @return Map&lt;String,DataFrame&gt; 占位名 → DataFrame
     * @throws IllegalArgumentException 普通 SQL 占位数与 df 数不匹配,或 CTE 模式 df 数多于占位
     */
    static Map<String, DataFrame> bindPlaceholders(String sql, DataFrame... dfs) {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{(\\w+)}",
                java.util.regex.Pattern.UNICODE_CHARACTER_CLASS).matcher(sql);
        while (m.find()) {
            String name = m.group(1);
            if (!names.contains(name)) names.add(name);
        }
        Map<String, DataFrame> bindings = new java.util.LinkedHashMap<>();
        if (names.isEmpty()) return bindings;
        // WITH 开头 = CTE:占位可多于 df(CTE 名占位由预处理注入);df 多于占位仍是用户错
        boolean hasCTE = sql.toUpperCase().matches("(?is)^\\s*WITH\\b.*");
        if (hasCTE) {
            if (names.size() < dfs.length) {
                throw new IllegalArgumentException(
                        "SQL 中有 " + names.size() + " 个 ${} 占位(" + names
                                + "),但传入了 " + dfs.length + " 个 DataFrame;参数过多");
            }
            for (int i = 0; i < dfs.length; i++) bindings.put(names.get(i), dfs[i]);
        } else {
            if (names.size() != dfs.length) {
                throw new IllegalArgumentException(
                        "SQL 中有 " + names.size() + " 个 ${} 占位(" + names
                                + "),但传入了 " + dfs.length + " 个 DataFrame;两者须一一对应");
            }
            for (int i = 0; i < names.size(); i++) bindings.put(names.get(i), dfs[i]);
        }
        return bindings;
    }
}
