# jian-sql-expr

## 基本信息
- **library**: jian-sql
- **entryClass**: jian.sql.expr.SqlBuilder
- **deps**: jOOQ 3.21.6(OSS Edition,运行时模式 / DSL);JDBC DataSource

## 摘要
类型安全 SQL 表达式构建,对齐规范 §2.2;基于 jOOQ 运行时 DSL,暴露 DSLContext 让用户用 jOOQ 原生链式构建参数化 SQL,防注入。

## 能力
- `SqlBuilder.create(dataSource, Dialect)`:绑定数据源 + jOOQ SQLDialect
- 8 种 Dialect:POSTGRESQL/MYSQL/DORIS/H2/SQLITE/ORACLE/ACCESS/DEFAULT(映射 jOOQ SQLDialect;Oracle/Access 因 OSS 限制用 DEFAULT)
- `qb.ctx()`:暴露 jOOQ DSLContext,支持 selectFrom/where/order by/group by/join 等类型安全链式
- `qb.fetch(sql, params...)`:原生参数化 SQL(? 占位,PreparedStatement 绑定,防注入)
- `withConnection(conn)`:复用外部 Connection(不自行关闭)
- AutoCloseable:用完关自取的 Connection

## 限制
- 运行时模式(动态 schema / 无代码生成),不享受 jOOQ 代码生成的强类型表/列对象
- jOOQ OSS Edition 不含 Oracle/DB2 等商业方言,相关库走 DEFAULT 通用 SQL(部分方言特性不可用)
- 不内置结果 → DataFrame(用 jian-sql-bridge 的 SqlBridge.toDataFrame(jOOQ Result))

## 快速上手
```java
import jian.sql.expr.SqlBuilder;
import static org.jooq.impl.DSL.field;

SqlBuilder qb = SqlBuilder.create(dataSource, SqlBuilder.Dialect.H2);

// 类型安全 DSL
Result<Record> r = qb.ctx().selectFrom("users")
    .where("age > ?", 18)
    .orderBy(field("age").desc())
    .limit(10)
    .fetch();

// 原生参数化 SQL
Result<Record> r2 = qb.fetch("SELECT * FROM users WHERE name = ?", "alice");
qb.close();
```
