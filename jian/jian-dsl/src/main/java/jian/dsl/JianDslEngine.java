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

    @Override public DataFrame query(DataFrame df, String expr) {
        return Dsl.query(df, expr);
    }

    @Override public DataFrame eval(DataFrame df, String expr) {
        return Dsl.eval(df, expr);
    }

    /**
     * L3 SQL(规范 07 §2.3)。接收者 df 为 SQL 中的主表(this/DUAL);
     * ${name} 占位按出现顺序绑定到 binds。
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
        if (!names.isEmpty()) {
            if (names.size() != binds.length) {
                throw new IllegalArgumentException(
                        "SQL 中有 " + names.size() + " 个 ${} 占位(" + names
                                + "),但传入了 " + binds.length + " 个 DataFrame;两者须一一对应");
            }
            for (int i = 0; i < names.size(); i++) bindings.put(names.get(i), binds[i]);
        }
        return SqlEngine.execute(df, sql, bindings, SqlDialect.DEFAULT);
    }

    @Override public String name() { return "jian-dsl-full"; }
}
