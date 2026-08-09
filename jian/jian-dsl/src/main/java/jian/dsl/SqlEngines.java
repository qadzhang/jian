package jian.dsl;

import java.util.Set;

// ┌─ What : SqlEngines —— L3 SQL 引擎注册中心(可插拔切换,库无关)
// │  Why  : 配合 SqlEngineInterface 通用接口,让用户在多种引擎间切换
// │  Who  : 由 Dsl.sql/Dsl.update 入口调 current();用户主动调 useCustom 切换
// │  When : 引擎选择是线程局部(ThreadLocal),Web 场景多请求互不影响
// │  Where: jian-dsl/SqlEngines.java
/**
 * L3 SQL 引擎注册中心(库无关,可插拔)。
 *
 * <p>核心 API:
 * <ul>
 *   <li>{@link #current()} —— 取当前线程的引擎(默认 SqlRegexEngine)</li>
 *   <li>{@link #useCustom(SqlEngineInterface)} —— 接入任意自定义引擎(通用入口)</li>
 *   <li>{@link #useRegex()} —— 切回默认正则引擎</li>
 *   <li>{@link #reset()} —— 重置为默认引擎</li>
 * </ul>
 *
 * <p>切换示例:
 * <pre>{@code
 * // 1. 默认(正则引擎,纯 JDK)
 * Dsl.sql("SELECT * FROM ${t} WHERE age > 18", df);
 *
 * // 2. 接入自定义引擎(用户实现的 SqlEngineInterface)
 * SqlEngines.useCustom(new MyEngine());
 *
 * // 3. 重置默认
 * SqlEngines.reset();
 * }</pre>
 *
 * <p>线程安全:ThreadLocal 隔离每个线程的引擎选择。
 */
public final class SqlEngines {

    private SqlEngines() {}

    private static final SqlEngineInterface REGEX_DEFAULT = new SqlRegexEngine();

    private static final ThreadLocal<SqlEngineInterface> CURRENT = new ThreadLocal<>() {
        @Override protected SqlEngineInterface initialValue() { return REGEX_DEFAULT; }
    };

    public static SqlEngineInterface current() { return CURRENT.get(); }

    public static void useRegex() { CURRENT.set(REGEX_DEFAULT); }

    public static void useCustom(SqlEngineInterface engine) {
        if (engine == null) throw new IllegalArgumentException("useCustom engine 不能为 null");
        CURRENT.set(engine);
    }

    public static void reset() { CURRENT.remove(); }

    public static Set<SqlEngineInterface.Capability> currentCapabilities() {
        return CURRENT.get().capabilities();
    }

    public static boolean currentSupports(SqlEngineInterface.Capability cap) {
        return CURRENT.get().supports(cap);
    }
}
