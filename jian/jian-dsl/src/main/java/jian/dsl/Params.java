package jian.dsl;

import java.util.LinkedHashMap;
import java.util.Map;

// ┌─ What : Params —— DSL 表达式的命名参数绑定(对齐规范 07 §2.1 替代 @var 引用)
// │  Why  : df.query("age > ${threshold}", Params.of("threshold", 18)) 显式传参,安全可控
// │  Who  : 由 PrattEngine.query/eval 接收,展开 ${name} 占位
// │  When : 表达式含动态参数
/**
 * DSL 命名参数。用法:
 * <pre>{@code
 * Params p = Params.of("threshold", 18).with("city", "SH");
 * Dsl.query(df, "age > ${threshold} && city == ${city}", p);
 * }</pre>
 */
public final class Params {

    /** 空参数。 */
    public static final Params EMPTY = new Params(Map.of());

    private final Map<String, Object> bindings;

    private Params(Map<String, Object> b) { this.bindings = b; }

    /**
     * 创建单参数 Params。
     *
     * @param name String 参数名,非 null
     * @param value Object 参数值,可为 null
     * @return Params 含单个绑定的新 Params
     */
    public static Params of(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(name, value);
        return new Params(m);
    }

    /**
     * 追加一个参数(不可变,返回新 Params)。
     *
     * @param name String 参数名,非 null
     * @param value Object 参数值,可为 null
     * @return Params 含原绑定 + 新绑定的新 Params(原 Params 不变)
     */
    public Params with(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(bindings);
        m.put(name, value);
        return new Params(m);
    }

    /**
     * 取参数值;不存在返回 null。
     *
     * @param name String 参数名,非 null
     * @return Object 参数值;不存在时返回 null
     */
    public Object get(String name) { return bindings.get(name); }

    /**
     * 是否含某参数。
     *
     * @param name String 参数名,非 null
     * @return boolean true 含此参数,false 不含
     */
    public boolean has(String name) { return bindings.containsKey(name); }

    Map<String, Object> all() { return bindings; }
}
