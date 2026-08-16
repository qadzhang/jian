# jian-io-csv

## 基本信息
- **library**: jian
- **entryClass**: jian.io.csv.Csv
- **deps**: jian-core;commons-csv(Apache)
- **tests**: 46(含 CsvAdversarialFuzzTest 对抗模糊)

## 摘要
CSV/TSV/FWF 读写,对齐 pandas.read_csv / to_csv;基于 commons-csv,builder 链式配置 + 默认自动类型推断,内置 CSV 公式注入防护。

## 能力
- 读 Csv.read(path):可配置 delimiter/header/encoding/schema/allString/naValues;自动推断 INT/LONG/DOUBLE/BOOL/STRING
- 读 TSV:`.delimiter('\t')`
- 读 FWF(定宽):`Csv.readFwf(path).widths(5,10,8).go()`
- 写 Csv.write(df, path):可配置 delimiter/header/encoding/naRep/quoteMode
- CSV 公式注入防护(OWASP):`= + - @` 开头的值自动加单引号前缀,默认开启(`sanitizeFormulas(true/false)`)


### 行为细节
- 空文件读回 0 行 0 列;数据行多字段保留截断 + 一次性 stderr 告警(warnExtraCols(false) 可关)
- 公式注入防护跳过集含 NUL/BOM;超大整数(>int64)读入归 STRING(对齐 pandas object);仅 UTF-8 BOM 自动剥离

### 行为细节(续 1)
- CSV 表头列名同样走公式注入防护

### 行为细节(续 2)
- 重复表头自动改名加 `_1` 后缀(`name, name` → `name, name_1`;与 Excel 模块 dedupNames 统一,pandas 用 `name.1`,见 doc/00 §10.16 第 16 条)
- FWF 读支持 BOM

## 限制
- 不支持嵌入式换行/复杂引号转义之外的多行记录格式扩展(以 commons-csv 标准语义为准)
- FWF 读为按固定宽度切片,不支持多字节字符对齐的列宽修正
- 仅做写入侧公式注入防护;读取侧不再回放原值(已被引号转义)

## 快速上手
```java
import jian.io.csv.Csv;

// 读
DataFrame df = Csv.read("data.csv").delimiter(',').header(true).go();
DataFrame tsv = Csv.read("data.tsv").delimiter('\t').go();
DataFrame fwf = Csv.readFwf("data.fwf").widths(5, 10, 8).go();

// 写(默认开启公式注入防护)
Csv.write(df, "out.csv").go();
Csv.write(df, "out.tsv").delimiter('\t').go();
```
