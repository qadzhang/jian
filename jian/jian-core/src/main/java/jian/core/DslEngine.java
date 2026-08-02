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

    /** L1:布尔过滤。 */
    DataFrame query(DataFrame df, String expr);

    /** L2:派生新列。 */
    DataFrame eval(DataFrame df, String expr);

    /**
     * L3:SQL 子集(规范 07 §2.3)。接收者为 SQL 里的 this/DUAL 主表,
     * ${name} 占位按出现顺序绑定到 binds 参数。
     * <p>core 兜底不实现 L3,抛出带安装提示的异常;jian-dsl 的 JianDslEngine 覆盖为完整实现。
     */
    default DataFrame sql(DataFrame df, String sql, DataFrame... binds) {
        throw new ModuleNotLoadedException(
                "df.sql() 需要 jian-dsl 模块,请引入 jian-dsl jar(或改用 Dsl.sql / Jian.sql)");
    }

    /** 引擎名(用于识别)。 */
    String name();

    /** 内置兜底(L1 子集,复用 SimpleQueryParser)。 */
    DslEngine BUILTIN = new SimpleDslEngine();

    /** ServiceLoader 加载器:找到第一个非内置实现就用,否则用 BUILTIN。 */
    ServiceLoader<DslEngine> LOADER = ServiceLoader.load(DslEngine.class);

    /** 取当前可用 DslEngine(优先 jian-dsl,回退内置)。 */
    static DslEngine current() {
        for (DslEngine e : LOADER) {
            if (!(e instanceof SimpleDslEngine)) return e;  // 第一个非兜底实现
        }
        return BUILTIN;
    }
}
