package jian.dsl;

import jian.core.DataFrame;
import jian.core.DslEngine;

// ┌─ What : JianDslEngine —— jian-dsl 实现 core 的 DslEngine SPI(对齐规范 07 §4)
// │  Why  : 引 jian-dsl jar 后,core 经 ServiceLoader 自动找到本实现,df.query/eval 升级到完整 L1+L2
// │  Who  : 由 ServiceLoader<DslEngine> 加载
// │  When : df.query / df.eval(用户引了 jian-dsl)
// │  Where: jian-dsl/JianDslEngine.java + META-INF/services/jian.core.DslEngine
/**
 * jian-dsl 的 DslEngine SPI 实现。提供完整 L1 query + L2 eval。
 *
 * <p>注册:META-INF/services/jian.core.DslEngine 文件内容 = jian.dsl.JianDslEngine。
 */
public final class JianDslEngine implements DslEngine {

    /**
     * L1 query。
     *
     * @param df DataFrame 数据源,非 null
     * @param expr String 布尔表达式,非 null
     * @return DataFrame 满足 expr 的行组成的新 DataFrame
     */
    @Override public DataFrame query(DataFrame df, String expr) {
        return Dsl.query(df, expr);
    }

    /**
     * L2 eval。
     *
     * @param df DataFrame 数据源,非 null
     * @param expr String 赋值表达式,非 null
     * @return DataFrame 加了新列后的 DataFrame
     */
    @Override public DataFrame eval(DataFrame df, String expr) {
        return Dsl.eval(df, expr);
    }

    /**
     * L3 SQL(规范 07 §2.3)。接收者 df 为 SQL 中的主表(this/DUAL);
     * ${name} 占位按出现顺序绑定到 binds。
     *
     * @param df DataFrame SQL 主表(对应 FROM this / FROM DUAL),非 null
     * @param sql String SQL 字符串,非 null;可含 ${name} 占位
     * @param binds DataFrame[] 占位绑定的 DataFrame,按 ${name} 出现顺序一一对应
     * @return DataFrame SQL 执行结果
     * @throws IllegalArgumentException ${} 占位数与 binds 个数不匹配时抛出
     */
    @Override public DataFrame sql(DataFrame df, String sql, DataFrame... binds) {
        // 无占位 → 主表即 this;有占位 → 按序绑定,主表仍可用 this 引用(与 Dsl.sql 的纯占位模式互补)
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{(\\w+)}").matcher(sql);
        while (m.find()) {
            String name = m.group(1);
            if (!names.contains(name)) names.add(name);
        }
        java.util.Map<String, DataFrame> bindings = new java.util.LinkedHashMap<>();
        // L2 修复(2026-08-09):含 WITH 的 SQL(CTE)允许 names.size() > binds.length
        // 因为 CTE 预处理会动态产生 ${cte_name} 占位,由引擎内部注入 binding,不需用户传 df
        boolean hasCTE = sql.toUpperCase().matches("(?is)^\\s*WITH\\b.*");
        if (!names.isEmpty()) {
            if (hasCTE) {
                // CTE 模式:按 binds 顺序绑定前 binds.length 个占位;其余由引擎预处理注入
                if (names.size() < binds.length) {
                    throw new IllegalArgumentException(
                        "SQL 中有 " + names.size() + " 个 ${} 占位(" + names
                            + "),但传入了 " + binds.length + " 个 DataFrame;参数过多");
                }
                for (int i = 0; i < binds.length; i++) bindings.put(names.get(i), binds[i]);
            } else {
                if (names.size() != binds.length) {
                    throw new IllegalArgumentException(
                            "SQL 中有 " + names.size() + " 个 ${} 占位(" + names
                                    + "),但传入了 " + binds.length + " 个 DataFrame;两者须一一对应");
                }
                for (int i = 0; i < names.size(); i++) bindings.put(names.get(i), binds[i]);
            }
        }
        // 2026-08-09 阶段 E:经 SqlEngines.current() 走可插拔引擎(默认 SqlRegexEngine)
        return SqlEngines.current().execute(df, sql, bindings, SqlDialect.DEFAULT);
    }

    /**
     * 引擎名称。
     *
     * @return String 引擎标识,固定为 "jian-dsl-full"
     */
    @Override public String name() { return "jian-dsl-full"; }
}
