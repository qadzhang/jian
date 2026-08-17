# jian-io-sql

## 基本信息
- **library**: jian
- **entryClass**: jian.io.sql.Sql
- **deps**: jian-core;纯 JDK(仅用 JDBC API;数据库驱动由用户按需引入)
- **tests**: 47

## 摘要
JDBC 通用读写,对齐 pandas.read_sql / to_sql;一套代码适配 PostgreSQL / MySQL / Doris / SQLite / H2 / Oracle / Access 七库,方言自适应类型映射。

## 能力
- 读 `Sql.readQuery(conn, sql, params...)`:PreparedStatement + ? 占位,ResultSet → DataFrame
- 读 `Sql.readTable(conn, table)`:整表 `SELECT * FROM table`
- `Sql.resultSetToDataFrame(rs)`:从 ResultSetMetaData 推断列名/类型;特殊类型规范化(Clob→String、Blob→byte[]、BigDecimal→Double、Date/Timestamp 保留)
- 写 `Sql.write(df, conn, table, Mode)`:Mode = OVERWRITE / APPEND / CREATE_OR_REPLACE / FAIL_IF_EXISTS;批量 INSERT(默认 batchSize 1000)
- CREATE TABLE 列类型按方言自适应(PG/MySQL/SQLite/H2/SQL Server/Oracle),VARCHAR 阈值 4000(Oracle VARCHAR2 公共安全上限)
- 表存在判定走 `meta.getTables`,大小写不敏感,不写死方言


### 行为细节
- write 异常自动 rollback(不悬挂半程批次);schema.table 两参探测;readQuery fetchSize=1000 hint
- APPEND 失败附 CREATE_OR_REPLACE 指引;标识符按需加引号(简单 ASCII 不加引号走库默认折叠,中文/特殊字符以库引号符包裹 + 双写转义,严格保真往返 —— "AA_a啊" 原样建列;注入元字符被引号化为字面量,控制字符仍硬拒绝)

### 行为细节(续 1)
- tableExists 精确匹配(表名含 `_` 不被当 SQL 通配符误判)
- Oracle VARCHAR2(n CHAR) 字符语义(按字符数而非字节数限长)

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
