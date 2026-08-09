# jian-io-orc

## 基本信息
- **library**: jian
- **entryClass**: jian.io.orc.Orc
- **deps**: jian-core;orc-core 1.9.5 + hive-storage-api 2.8.1 + hadoop-client-runtime 3.3.1(shaded woodstox)

## 摘要
ORC 列式二进制读写,对齐 pandas.read_orc / to_orc;基于 orc-core,经 VectorizedRowBatch 列式批量读写,DType 自动映射 ORC 类型。

## 能力
- Orc.read(path):builder + `.go()`;`OrcFile.createReader` + `Reader.rows()` 流式遍历 batch → DataFrame
- Orc.write(df, path):builder + `.go()`;按 DType 建 `TypeDescription` schema → 填 `VectorizedRowBatch` → `Writer.addRowBatch`
- 类型映射:INT→int / LONG→bigint / DOUBLE→double / BOOL→boolean / STRING→string 等
- 异常包装带中文提示(IOException)

## 限制
- 依赖 Hadoop/ORC 一组 jar,体积较大;`hadoop-client-runtime` 必须(否则 NoClassDefFoundError,见 pom 注释)
- DATE/DATETIME 等复杂类型简化处理,OBJECT 列按 string 写出
- 不支持 ORC 高级特性(ACID、索引、谓词下推、compress 配置等)

## 快速上手
```java
import jian.io.orc.Orc;

Orc.write(df, "data.orc").go();
DataFrame r = Orc.read("data.orc").go();
```
