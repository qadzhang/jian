package jian.sql.engine;

// ┌─ What : ModuleNotLoadedException —— jian-sql 驱动缺失友好异常(对齐规范 05 §4)
// │  Why  : 规范 §4.2 按需加载:用户未引对应 JDBC 驱动 jar 时,不能抛难排查的
// │         NoClassDefFoundError,而应抛带"请引 xxx jar"安装提示的友好异常
// │  Who  : Engine 构造时探测驱动后抛出
// │  When : 用户创建 Engine 但 classpath 缺对应数据库驱动
// │  Where: jian-sql-engine/ModuleNotLoadedException.java
// │
// │ 与 jian-core 的同名类相互独立:jian-sql 不依赖 jian-core(规范 §4.1),各自定义一份,语义一致。
public class ModuleNotLoadedException extends RuntimeException {

    public ModuleNotLoadedException(String message) {
        super(message);
    }

    public ModuleNotLoadedException(String message, Throwable cause) {
        super(message, cause);
    }
}
