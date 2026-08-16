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
        // 绑定占位与静态入口 Dsl.sql 统一走 bindPlaceholders(两入口行为一致,
        // 避免重复实现 CTE 宽容分支导致入口行为不一致)
        java.util.Map<String, DataFrame> bindings = Dsl.bindPlaceholders(sql, binds);
        // 经 SqlEngines.current() 走可插拔引擎(默认 SqlRegexEngine)
        return SqlEngines.current().execute(df, sql, bindings, SqlDialect.DEFAULT);
    }

    /**
     * 引擎名称。
     *
     * @return String 引擎标识,固定为 "jian-dsl-full"
     */
    @Override public String name() { return "jian-dsl-full"; }
}
