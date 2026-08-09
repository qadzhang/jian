# jian-io-json

## 基本信息
- **library**: jian
- **entryClass**: jian.io.json.Json
- **deps**: jian-core;Jackson databind(`ObjectMapper`)

## 摘要
JSON 读写,对齐 pandas.read_json / to_json;支持 pandas 全部 5 种 orient + `json_normalize` 拍平嵌套 JSON。

## 能力
- 读 Json.read(path):builder + `.orient(...)` + `.go()`;无配置直接 `Json.read(path)` 用默认 RECORDS
- 解析字符串 `Json.parse(json, orient)`
- 5 种 orient:RECORDS(默认,`[{...}]`)/ COLUMNS(`{col:[...]}`)/ VALUES(`[[...]]`)/ INDEX / SPLIT(`{columns,index,data}`)
- 写 Json.write(df, path):`.orient(...)`;`Json.toJsonString(df, orient)` 直接拿字符串
- `Json.normalize(json, recordPath)`:对齐 pandas.json_normalize,按 recordPath(如 `"results.items"`)拍平嵌套 JSON 为 DataFrame
- 日期默认 ISO-8601 字符串

## 限制
- 日期解析为字符串语义(M3 不做复杂日期类型还原)
- normalize 仅按单条 recordPath 拍平,不支持 meta/metadata_path 等全部 pandas 高级选项
- 大 JSON 全量载入内存(流式拉取未实现)

## 快速上手
```java
import jian.io.json.Json;
import jian.io.json.Json.Orient;

DataFrame df = Json.read("data.json").orient(Orient.RECORDS).go();
DataFrame cols = Json.read("c.json").orient(Orient.COLUMNS).go();
DataFrame flat = Json.normalize("{\"results\":{\"items\":[{\"a\":1,\"o\":{\"x\":2}}]}}", "results.items");

Json.write(df, "out.json").orient(Orient.RECORDS).go();
String s = Json.toJsonString(df, Orient.VALUES);
```
