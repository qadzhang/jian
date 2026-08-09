# jian-io-csv

## 基本信息
- **library**: jian
- **entryClass**: jian.io.csv.Csv
- **deps**: jian-core;commons-csv(Apache)

## 摘要
CSV/TSV/FWF 读写,对齐 pandas.read_csv / to_csv;基于 commons-csv,builder 链式配置 + 默认自动类型推断,内置 CSV 公式注入防护。

## 能力
- 读 Csv.read(path):可配置 delimiter/header/encoding/schema/allString/naValues;自动推断 INT/LONG/DOUBLE/BOOL/STRING
- 读 TSV:`.delimiter('\t')`
- 读 FWF(定宽):`Csv.readFwf(path).widths(5,10,8).go()`
- 写 Csv.write(df, path):可配置 delimiter/header/encoding/naRep/quoteMode
- CSV 公式注入防护(OWASP):`= + - @` 开头的值自动加单引号前缀,默认开启(`sanitizeFormulas(true/false)`)

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
