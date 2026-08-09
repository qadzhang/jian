# jian-sql-engine

## 基本信息
- **library**: jian-sql
- **entryClass**: jian.sql.engine.Engine
- **deps**: HikariCP(连接池);JDBC API;七库驱动由用户按需引入(反射探测 driverClassName)

## 摘要
数据库连接管理引擎,对齐 sqlalchemy.create_engine;基于 HikariCP,DbType 覆盖 7 库,EngineConfig 走 .env 零本机绑定,readOnly 模式拦截写 SQL。

## 能力
- DbType 七库:POSTGRESQL / MYSQL / DORIS(MySQL 协议)/ SQLITE / H2 / ORACLE / ACCESS;各持 jdbcUrlPrefix/defaultPort/driverClassName/driverHint
- `Engine.create(dbType, config)`:HikariDataSource 连接池单例
- `Engine.fromUrl(sqlalchemyUrl)`:解析 `scheme://user:pass@host:port/db`,password 支持 `${ENV_VAR}` 占位
- `EngineConfig.fromEnv()` / builder:读 DB_HOST/DB_PORT/DB_USER/DB_PASSWORD/DB_NAME 等;poolSize 默认 10;readOnly 标志
- `connect()`:借连接(自动提交);`begin()`:借连接 + setAutoCommit(false)(try-with-resources 自动提交/回滚)
- 只读安全:readOnly 模式下 DROP/DELETE/TRUNCATE 等写 SQL 抛 SecurityException
- 驱动缺失友好报错:抛 ModuleNotLoadedException 并给出 driverHint(如 `org.postgresql:postgresql`)

## 限制
- jOOQ OSS Edition 不含 Oracle/DB2 等商业方言,Engine 仍可连但类型安全 SQL 受限(见 jian-sql-expr)
- 不做 SQL 语法解析的只读判定,用正则匹配写关键字(覆盖常见 DROP/DELETE/UPDATE/TRUNCATE)
- 连接池由 Engine 持有,须 close() 释放;未提供连接重用/事务编排高级 API(用 begin())

## 快速上手
```java
import jian.sql.engine.Engine;
import jian.sql.engine.DbType;
import jian.sql.engine.EngineConfig;

Engine engine = Engine.create(DbType.POSTGRESQL, EngineConfig.fromEnv());
// 或:Engine.fromUrl("postgresql://user:${DB_PASSWORD}@host:5432/db");

try (Connection conn = engine.begin()) {  // 自动提交/回滚
    try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
        ps.setLong(1, 1L);
        try (ResultSet rs = ps.executeQuery()) { /* ... */ }
    }
}
engine.close();   // 关闭连接池
```
