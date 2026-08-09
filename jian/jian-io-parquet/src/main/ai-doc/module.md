# jian-io-parquet

## 基本信息
- **library**: jian
- **entryClass**: jian.io.parquet.Parquet
- **deps**: jian-core;parquet-avro 1.14.4 + Avro(经 `LocalInputFile/OutputFile`,去 Hadoop 化,纯 JDK)

## 摘要
Parquet 列式二进制读写,对齐 pandas.read_parquet / to_parquet;基于 parquet-avro,使用 `LocalInputFile/OutputFile` 本地文件直读直写,无需 Hadoop 环境。

## 能力
- Parquet.read(path):builder + `.go()`;`AvroParquetReader` 流式读 `GenericRecord` → DataFrame
- Parquet.write(df, path):builder + `.go()`;按 DType 建 Avro Schema → `GenericData.Record` 列表 → `AvroParquetWriter`
- 类型映射:LONG→long / DOUBLE→double / BOOL→boolean / STRING→string / INT→int
- 去 Hadoop 化:本地文件 IO,不依赖 Hadoop 配置或运行时

## 限制
- DATETIME/DATE 简化为 string(millis),OBJECT 列按 string 写出(复杂类型不在 Avro Schema 简单映射范围)
- 不支持分区写出、Parquet 加密、谓词下推、压缩算法配置(用 parquet-avro 默认 SNAPPY/GZIP)
- 全量内存载入,无谓词过滤的行组跳过

## 快速上手
```java
import jian.io.parquet.Parquet;

Parquet.write(df, "data.parquet").go();
DataFrame r = Parquet.read("data.parquet").go();
```
