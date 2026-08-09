package jian.core;

// ┌─ What : SimpleDslEngine —— core 内置的 L1 子集 DSL 兜底(对齐规范 07 §4 找不到 jian-dsl 时用)
// │  Why  : 用户只引 core 也能用 df.query(基本布尔表达式);完整 L1+L2+L3 引 jian-dsl
// │  Who  : 由 DslEngine.current() 在未找到外部实现时返回
// │  When : df.query / df.eval 且未引 jian-dsl
// │  Where: jian-core/SimpleDslEngine.java
// │  How  : 数据走向:委托 core 现有的 SimpleQueryParser(L1 子集)。
/**
 * core 内置 L1 子集兜底。委托 {@link SimpleQueryParser}。
 *
 * <p>能力子集:比较 / 逻辑 / between / like / in / is null / 括号(不支持三元/参数/L2 eval/L3 SQL)。
 * 完整能力引 jian-dsl 模块(规范 07 §4)。
 */
public final class SimpleDslEngine implements DslEngine {

    /**
     * L1 query 兜底(委托 SimpleQueryParser 解析布尔表达式)。
     * @param df   DataFrame 输入表,非 null
     * @param expr String 布尔表达式子集(比较/逻辑/between/like/in/is null/括号);非 null
     * @return DataFrame 过滤后的新表(行数 ≤ df.rowCount())
     */
    @Override public DataFrame query(DataFrame df, String expr) {
        return df.filter(SimpleQueryParser.evaluate(df, expr));
    }

    /**
     * L2 eval 兜底(不支持)。
     * @param df   DataFrame 输入表
     * @param expr String 派生表达式
     * @return DataFrame 永不返回
     * @throws UnsupportedOperationException 兜底实现总是抛(需引 jian-dsl)
     */
    @Override public DataFrame eval(DataFrame df, String expr) {
        throw new UnsupportedOperationException(
                "L2 eval 需引 jian-dsl 模块(core 内置兜底仅支持 L1 query)");
    }

    /** @return String 固定 "simple-builtin" */
    @Override public String name() { return "simple-builtin"; }
}
