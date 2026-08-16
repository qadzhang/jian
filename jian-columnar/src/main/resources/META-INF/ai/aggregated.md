# jian-columnar-all 聚合 jar · AI 总索引

> 本 jar 是**列存附加聚合 jar**(MANIFEST `Ai-Aggregated: true`),内含 Parquet/ORC 列存读写能力及其 Hadoop 生态依赖(约 46MB)。
> **用法:与 `jian-all.jar` 叠加**( `-cp jian-all.jar:jian-columnar-all.jar` );只引 jian-all 时调列存 API 会抛 `ModuleNotLoadedException`。
> 模块文档:`META-INF/ai/modules/<artifactId>/module.md`。

## 为什么单独一个 jar

Parquet/ORC 的官方 Java 实现(parquet-mr / orc-core)API 压在 Hadoop 的 IO 抽象上,连带 hadoop-common / mapreduce-client-core / hadoop-client-runtime 等约 **45MB** 依赖。多数数据分析场景(CSV/Excel/JSON/SQL)用不到列存,故主 fat 不含;大数据量或与 Spark/Hive/DuckDB 交换文件时叠加本 jar。

## 30 秒上手

```java
// classpath: jian-all.jar + jian-columnar-all.jar
jian.Jian.toParquet(df, "销售明细.parquet");     // 10 倍于 CSV 的压缩(列存 + 字典编码)
jian.Jian.toOrc(df, "销售明细.orc");
DataFrame back = jian.Jian.readParquet("销售明细.parquet");
```

## 模块清单(详情见 modules/<artifactId>/module.md)

| 模块 | 干什么 | 关键外部依赖 |
|---|---|---|
| jian-io-parquet | Parquet 列存读写(对齐 pandas.to_parquet) | parquet-avro + hadoop-common/mapreduce(本地 IO 子集) |
| jian-io-orc | ORC 列存读写(对齐 pandas.to_orc) | orc-core + hive-storage-api + hadoop-client-runtime |
| jian-core | DataFrame 核心(经列存模块传递入包;通常与 jian-all 搭配,重复类无害) | 无(纯 JDK) |

## 相关库

- `jian-all`(主 fat,不含列存,~45MB)/ `jian-num-all` / `jian-sql-all`
- thin jar 按需:`jian:jian-io-parquet:1.0.1` / `jian:jian-io-orc:1.0.1`
