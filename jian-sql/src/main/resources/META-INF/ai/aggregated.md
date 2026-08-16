# jian-sql-all 聚合 jar · AI 总索引

> 本 jar 是**聚合 jar**(MANIFEST `Ai-Aggregated: true`),内含 jian-sql 数据库引擎栈 4 个子模块。
> 各模块文档:`META-INF/ai/modules/<artifactId>/module.md`;thin jar 的文档在 `META-INF/ai/module.md`。

## 这是什么库

JVM 上对标 sqlalchemy 的数据库引擎栈(不依赖 jian):**连接池引擎 + 类型安全 SQL 表达式 + 轻量 ORM + ResultSet→DataFrame 单向桥**。

## 30 秒上手

```java
import jian.sql.engine.Engine;
import jian.sql.engine.EngineConfig;
import jian.sql.orm.Session;

Engine engine = Engine.create(DbType.MYSQL, EngineConfig.fromEnv());  // 连接配置从环境变量/.env 读(零硬编码)
engine.sql("SELECT id, name FROM users WHERE id = ?", 1).fetch();     // 参数化查询(jOOQ Result)

// ORM(注解实体:@Table/@Column/@Id)
try (Session<User> s = new Session<>(engine, User.class)) {
    User u = s.findById(1L);
}
```

## 模块清单(详情见 modules/<artifactId>/module.md)

| 模块 | 干什么 | 关键外部依赖 |
|---|---|---|
| jian-sql-engine | 连接管理引擎(HikariCP 池 + DbType 方言 + 只读模式安全拦截) | HikariCP |
| jian-sql-expr | 类型安全 SQL 表达式构建(对齐 sqlalchemy 表达式) | jOOQ 运行时 DSL |
| jian-sql-orm | 轻量 ORM:`@Table`/`@Column`/`@Id` 注解实体 + Session CRUD | 无(纯 JDK) |
| jian-sql-bridge | ResultSet / jOOQ 结果 → jian.core.DataFrame 单向桥(可选引 jian-core) | 无 |
| jian-core | DataFrame 核心(经 bridge 传递入包;独立 fat 见 jian-all) | 无(纯 JDK) |

## 相关库

- `jian-all`(数据栈;引 jian-sql-bridge 后 SQL 结果直接变 DataFrame)
- `jian-num-all`(独立数值库)
