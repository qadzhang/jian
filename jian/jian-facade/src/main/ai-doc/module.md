# jian-facade

## 基本信息
- **library**: jian
- **entryClass**: jian.Jian
- **deps**: jian-core + 全部 io 子模块(csv/excel/html/json/parquet/orc/pickle/xml/latex/sql/clipboard)+ jian-export;带入全部传递依赖

## 摘要
jian 顶层门面,聚合全部 read*/to*/write* 入口;用户 `import jian.Jian` 后单点访问,无需记各 io 类名(对齐 pandas 顶层 pd.*)。

## 能力
- 通用 `read(path)` / `write(df, path)`:按文件扩展名(.csv/.tsv/.json/.xlsx/.html/.xml/.parquet/.jpk)自动分发
- 专用读:readCsv / readTable / readFwf / readJson / readExcel / readXml / readParquet / readOrc / readPickle / readClipboard / readSql(Query/Table)
- 专用写:toCsv / toTable / toJson / toExcel / toParquet / toOrc / toPickle / toHtml / toXml / toLatex / toMarkdown / toClipboard / toSql
- 不支持的扩展名抛 IllegalArgumentException 并列出支持格式清单

## 限制
- 重量级依赖聚合:引此模块即带入全部 io 传递依赖(POI/Hadoop/Parquet/Jackson 等)
- 若要按需加载,请直接引具体 io 模块用其类(如 `Csv.read`),不经此门面

## 快速上手
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
