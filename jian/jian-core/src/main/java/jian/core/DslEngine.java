package jian.core;

import java.util.ServiceLoader;

// ┌─ What : DslEngine —— DSL 引擎 SPI 接口(对齐规范 07 §4 core 与 jian-dsl 解耦)
// │  Why  : 规范 07 §4;core 内置 SimpleDslEngine 兜底,jian-dsl 引入后通过 ServiceLoader 自动升级
// │  Who  : DataFrame.query/eval 通过 LOADER 取 DslEngine 实现
// │  When : df.query / df.eval 调用
// │  Where: jian-core/DslEngine.java
// │  How  : 数据走向:ServiceLoader<DslEngine> 扫 META-INF/services → 找到第一个非兜底实现就用,否则用 BUILTIN。
// │         逻辑路线:
// │           路径 A(用户引了 jian-dsl jar)→ 加载 JianDslEngine(L1+L2+L3 完整);
// │           路径 B(未引 jian-dsl)→ 用 core 内置 SimpleDslEngine(L1 子集兜底)。
/**
 * DSL 引擎 SPI。core 内置兜底;jian-dsl 模块经 ServiceLoader 升级为完整版(规范 07 §4)。
 */
public interface DslEngine {

    /**
     * L1:布尔过滤(对齐 pandas df.query)。
     * @param df  DataFrame 待过滤表,非 null
     * @param expr String 布尔表达式(如 "age > 18 & city == '北京'");非 null
     * @return DataFrame 过滤后的新表(行数 ≤ df.rowCount();同 schema)
     */
    DataFrame query(DataFrame df, String expr);

    /**
     * L2:派生新列(对齐 pandas df.eval)。
     * @param df  DataFrame 输入表,非 null
     * @param expr String 派生表达式(如 "score * 2 as score2");非 null
     * @return DataFrame 新表(在 df 基础上增/改列)
     */
    DataFrame eval(DataFrame df, String expr);

    /**
     * L3:SQL 子集(规范 07 §2.3)。接收者为 SQL 里的 this/DUAL 主表,
     * ${name} 占位按出现顺序绑定到 binds 参数。
     * <p>core 兜底不实现 L3,抛出带安装提示的异常;jian-dsl 的 JianDslEngine 覆盖为完整实现。
     *
     * @param df    DataFrame 主表(SQL 里的 this/DUAL),非 null
     * @param sql   String SQL 语句,含 ${name} 占位符;非 null
     * @param binds DataFrame... 占位符绑定的辅表,按 ${name} 出现顺序对应;允许空(无占位时)
     * @return DataFrame SQL 执行结果
     * @throws ModuleNotLoadedException core 兜底实现总是抛此异常(需引 jian-dsl jar)
     */
    default DataFrame sql(DataFrame df, String sql, DataFrame... binds) {
        throw new ModuleNotLoadedException(
                "df.sql() 需要 jian-dsl 模块,请引入 jian-dsl jar(或改用 Dsl.sql / Jian.sql)");
    }

    /**
     * 引擎名(用于识别/调试)。
     * @return String 引擎名,如 "SimpleDslEngine"/"JianDslEngine";非 null
     */
    String name();

    /** 内置兜底(L1 子集,复用 SimpleQueryParser)。 */
    DslEngine BUILTIN = new SimpleDslEngine();

    /**
     * 取当前可用 DslEngine(优先 jian-dsl,回退内置)。
     *
     * <p><b>Web 安全修复(2026-08-08)</b>:不再用 static ServiceLoader 缓存(导致 Tomcat redeploy 内存泄漏——
     * ServiceLoader 内部缓存引用 WebappClassLoader,卸载 webapp 时 ClassLoader 无法 GC)。
     * 改为每次调用时新建 ServiceLoader,由 GC 自动回收。
     *
     * @return DslEngine 实例:优先返回第一个非 SimpleDslEngine 的实现(jian-dsl 引入时);
     *         否则返回 BUILTIN
     */
    static DslEngine current() {
        // 每次 load(不缓存)——避免 Tomcat redeploy 时 ServiceLoader 持有 WebappClassLoader 导致内存泄漏
        ServiceLoader<DslEngine> loader = ServiceLoader.load(DslEngine.class);
        for (DslEngine e : loader) {
            if (!(e instanceof SimpleDslEngine)) return e;  // 第一个非兜底实现
        }
        return BUILTIN;
    }
}
