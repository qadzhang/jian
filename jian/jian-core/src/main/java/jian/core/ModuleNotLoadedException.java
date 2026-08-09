package jian.core;

// ┌─ What : ModuleNotLoadedException —— 按需加载缺失时抛出的友好异常(对齐规范 01 §9 / 02 §5.1 / 04 §5)
// │  Why  : 规范 §4.2 按需加载:用户未引某 jar 时,调用对应功能不能抛 NoClassDefFoundError(难排查),
// │         而应抛带"请引入 xxx jar"安装提示的受检友好异常
// │  Who  : DataFrame.sql / DslEngine 兜底 / 各 io 模块驱动探测等调用
// │  When : 用户调用了依赖未加载 jar 的功能时
// │  Where: jian-core/ModuleNotLoadedException.java
// │  How  : 数据走向:功能入口 → 探测依赖类是否可加载 → 不可加载则 new ModuleNotLoadedException(提示)抛出。
// │         关键变量变化:
// │           - message:含模块名(groupId:artifactId)与安装提示,可直接展示给用户。
// │         逻辑路线:
// │           路径 A(依赖已加载)→ 正常执行,不抛;
// │           路径 B(依赖缺失)→ 抛 ModuleNotLoadedException,提示引哪个 jar。
// │
// │         与 jian-sql-engine 的同名类相互独立:jian-sql 不依赖 jian-core(规范 §4.1),
// │         因此 jian-sql 自己定义一份;两处语义一致,只是所在库不同。
public class ModuleNotLoadedException extends RuntimeException {

    /**
     * 构造(仅消息)。
     * @param message String 异常消息,**应包含缺失模块的 groupId:artifactId 与安装提示**(可直接展示给用户);非 null
     */
    public ModuleNotLoadedException(String message) {
        super(message);
    }

    /**
     * 构造(消息 + 原因)。
     * @param message String 异常消息,含安装提示;非 null
     * @param cause   Throwable 原始异常(通常是探测时捕获的 ClassNotFoundException/NoClassDefFoundError);允许 null
     */
    public ModuleNotLoadedException(String message, Throwable cause) {
        super(message, cause);
    }
}
