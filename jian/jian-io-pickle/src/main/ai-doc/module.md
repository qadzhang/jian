# jian-io-pickle

## 基本信息
- **library**: jian
- **entryClass**: jian.io.pickle.Pickle
- **deps**: jian-core;jian-io-json(records orient 复用);纯 JDK(CRC32)
- **tests**: 6

## 摘要
jian 自定义 `.jpk` 序列化格式,对齐 pandas.to_pickle 诉求(DataFrame 落盘再加载);"魔数 + JSON(records) + CRC32" 结构,可 debug、无 RCE 风险。

## 能力
- Pickle.write(df, path):DataFrame → records orient JSON → `[魔数 JPK2][长度][JSON][CRC32]` 落盘
- Pickle.read(path):校验魔数 + CRC32 → 取 JSON → records orient 解析(复用 jian-io-json)→ DataFrame
- 安全:反序列化仅读 JSON 数据,不实例化任意类,规避 JDK 序列化(已废弃)与 Kryo(CVE)的 RCE 风险
- 损坏检测:魔数不符或 CRC 不匹配抛 IOException("文件损坏")

### 行为细节
- payload 长度 long 提升校验(不再 int 溢出绕过)

## 限制
- 不与 Python pickle 互通(规范已说明,仅满足 DataFrame 落盘核心诉求)
- 类型保真度受 records orient JSON 限制(DATE/DATETIME 等按 ISO 字符串,需读侧再解析)
- 单文件单 DataFrame,不支持集合/字典等任意对象图

## 快速上手
```java
import jian.io.pickle.Pickle;

Pickle.write(df, "data.jpk");
DataFrame loaded = Pickle.read("data.jpk");  // round-trip 一致
```
