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
 *
 * <p><b>Web 容器警示</b>:Tomcat/Spring Boot 的线程池会
 * <b>跨请求复用线程</b>——某请求 {@code useCustom(engine)} 后未 {@code reset()},
 * 同一线程的下一个请求会<b>继承该引擎</b>(状态泄漏,非数据泄漏但行为漂移)。容器中
 * useCustom 必须 try-finally 包裹:
 * <pre>{@code
 * try { SqlEngines.useCustom(myEngine); ... } finally { SqlEngines.reset(); }
 * }</pre>
 * 且自定义引擎不应持有 WebappClassLoader 可达的强引用实例字段(redeploy 类卸载受阻)。
 */
public final class SqlEngines {

    private SqlEngines() {}

    private static final SqlEngineInterface REGEX_DEFAULT = new SqlRegexEngine();

    private static final ThreadLocal<SqlEngineInterface> CURRENT = new ThreadLocal<>() {
        @Override protected SqlEngineInterface initialValue() { return REGEX_DEFAULT; }
    };

    public static SqlEngineInterface current() { return CURRENT.get(); }

    public static void useRegex() { CURRENT.set(REGEX_DEFAULT); }

    /**
     * @param engine 参数;非 null
     */
    public static void useCustom(SqlEngineInterface engine) {
        if (engine == null) throw new IllegalArgumentException("useCustom engine 不能为 null");
        CURRENT.set(engine);
    }

    public static void reset() { CURRENT.remove(); }

    public static Set<SqlEngineInterface.Capability> currentCapabilities() {
        return CURRENT.get().capabilities();
    }

    /**
     * @param cap 参数;非 null
     */
    public static boolean currentSupports(SqlEngineInterface.Capability cap) {
        return CURRENT.get().supports(cap);
    }
}
