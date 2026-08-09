# jian-io-sql

## 基本信息
- **library**: jian
- **entryClass**: jian.io.sql.Sql
- **deps**: jian-core;纯 JDK(仅用 JDBC API;数据库驱动由用户按需引入)

## 摘要
JDBC 通用读写,对齐 pandas.read_sql / to_sql;一套代码适配 PostgreSQL / MySQL / Doris / SQLite / H2 / Oracle / Access 七库,方言自适应类型映射。

## 能力
- 读 `Sql.readQuery(conn, sql, params...)`:PreparedStatement + ? 占位,ResultSet → DataFrame
- 读 `Sql.readTable(conn, table)`:整表 `SELECT * FROM table`
- `Sql.resultSetToDataFrame(rs)`:从 ResultSetMetaData 推断列名/类型;特殊类型规范化(Clob→String、Blob→byte[]、BigDecimal→Double、Date/Timestamp 保留)
- 写 `Sql.write(df, conn, table, Mode)`:Mode = OVERWRITE / APPEND / CREATE_OR_REPLACE / FAIL_IF_EXISTS;批量 INSERT(默认 batchSize 1000)
- CREATE TABLE 列类型按方言自适应(PG/MySQL/SQLite/H2/SQL Server/Oracle),VARCHAR 阈值 4000(Oracle VARCHAR2 公共安全上限)
- 表存在判定走 `meta.getTables`,大小写不敏感,不写死方言

## 限制
- 仅做读写,不管理连接池(由调用方提供 Connection,如需池化用 jian-sql-engine 的 HikariCP)
- 不支持 ORM、关系映射、复杂事务编排(那些在 jian-sql-orm)
- Oracle DATE 含时间部分、Oracle FLOAT 内部为 NUMBER,读回可能有精度/截断差异

## 快速上手
```java
import jian.io.sql.Sql;
import java.sql.Connection;
import java.sql.DriverManager;

try (Connection conn = DriverManager.getConnection(url, user, pwd)) {
    DataFrame df  = Sql.readQuery(conn, "SELECT * FROM users WHERE age > ?", 18);
    DataFrame all = Sql.readTable(conn, "users");
    Sql.write(df, conn, "users_copy", Sql.Mode.CREATE_OR_REPLACE);
}
```
