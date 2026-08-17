# jian-facade

## 基本信息
- **library**: jian
- **entryClass**: jian.Jian
- **deps**: jian-core + 全部 io 子模块(csv/excel/html/json/parquet/orc/pickle/xml/latex/sql/clipboard)+ jian-export;带入全部传递依赖
- **tests**: 93

## 摘要
jian 顶层门面,聚合全部 read*/to*/write* 入口;用户 `import jian.Jian` 后单点访问,无需记各 io 类名(对齐 pandas 顶层 pd.*)。

## 能力

- `Jian.generateColumnsSource(df, "OrderCols")`:生成列名常量类源码(业务代码引用常量防拼写错,借鉴 KDF schema 常量化;仅返回不落盘)
- 通用 `read(path)` / `write(df, path)`:按文件扩展名(.csv/.tsv/.json/.xlsx/.html/.xml/.parquet/.jpk)自动分发
- 专用读:readCsv / readTable / readFwf / readJson / readExcel / readXml / readParquet / readOrc / readPickle / readClipboard / readSql(Query/Table)
- 专用写:toCsv / toTable / toJson / toExcel / toParquet / toOrc / toPickle / toHtml / toXml / toLatex / toMarkdown / toClipboard / toSql
- 不支持的扩展名抛 IllegalArgumentException 并列出支持格式清单
- `write` 自动创建父目录 + 入参 null 防御;`generateColumnsSource` 对关键字列名生成合法标识符(如 `class` → `class_`)

## 限制

- 列存(Parquet/ORC)默认不构建、不进 jian-all:需叠加 `jian-columnar-all.jar`(fat)或引
  `jian-io-parquet`/`jian-io-orc` thin jar;缺失时相关 API 抛 ModuleNotLoadedException(带指引,反射探测)
- 重量级依赖聚合:引此模块即带入全部 io 传递依赖(POI/Hadoop/Parquet/Jackson 等)
- 若要按需加载,请直接引具体 io 模块用其类(如 `Csv.read`),不经此门面

## 快速上手

> **46 个真实场景速查**(第一轮 S1~S16:销售汇总/对账/清洗/重采样/RFM/AB 测试/透视/去重/JSON 拍平/SQL 直查/merge_asof...;
> 第二轮 S17~S46:抽象口径 30 类——表格⇄数据库导入导出/多 sheet 报表/三方对账/质量画像/脏数据清洗/
> 访问日志 TopN/性能分位对比/SLA 月报/降采样落库/z-score 异常/滚动窗口/留存漏斗/依赖漏洞审计等):
> 见 jar 内 `META-INF/ai/scenarios.md`,JUnit 实现在 `src/test/java/jian/scenario/`(随 jar 分发完整断言源码)。
```java
import jian.Jian;
import jian.core.DataFrame;

// 通用读(按扩展名分发)
DataFrame df = Jian.read("users.csv");
DataFrame j  = Jian.read("data.json");

// 通用写
Jian.write(df, "out.xlsx");
Jian.write(df, "out.json");

// SQL
try (Connection c = DriverManager.getConnection(url, user, pwd)) {
    DataFrame r = Jian.readSqlQuery(c, "SELECT * FROM t WHERE id > ?", 100);
    Jian.toSql(df, c, "target_table");
}
```
