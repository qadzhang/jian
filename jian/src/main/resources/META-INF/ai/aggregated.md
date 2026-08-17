# jian-all 聚合 jar · AI 总索引

> 本 jar 是**聚合 jar**(MANIFEST `Ai-Aggregated: true`),内含 jian 数据栈 15 个子模块(列存 Parquet/ORC 独立为 jian-columnar-all 附加制品)。
> 各模块的能力/限制/快速上手见 `META-INF/ai/modules/<artifactId>/module.md`(路径唯一,一模块一份)。
> thin jar(非聚合)的文档在 `META-INF/ai/module.md`。

## 这是什么库

JVM 上对标 pandas 的轻量数据栈:**DataFrame 列式存储 + 12 类格式 IO + 统计/时序 + 绘图 + SQL 表达式引擎**。
pandas 用户的直觉迁移:`read*`/`to*` 顶层函数、链式 `df.filter().select().sortBy()`、`df.query()/df.sql()`。

## 真实场景集

**46 个可照抄的业务场景**(第一轮 S1~S16:销售月度汇总/银行对账/缺失值清洗/日志重采样/RFM 分层/AB 测试/库存预警/
成绩透视/多表合并/订单去重/嵌套 JSON 拍平/区域拆分导出/SQL 直查/汇率就近折算/行为月报/样式导出;
第二轮 S17~S46:抽象口径 30 类——表格⇄数据库(导入校验入库/库分析后带色 Excel)/多 sheet 报表/三方对账/
质量画像/脏数据清洗/访问日志 TopN/性能分位对比/SLA 月报/降采样落库/z-score 异常/滚动窗口/留存漏斗/依赖漏洞审计等):
见 `META-INF/ai/scenarios.md`(断言值全部可手算,完整 JUnit 断言源码随 jar 分发在 `META-INF/ai/scenarios-src/`)。

## 30 秒上手

```java
import jian.Jian;
import jian.core.DataFrame;

DataFrame df = Jian.readExcel("销售明细.xlsx");            // 或 readCsv/readJson/readSql...
DataFrame hot = Jian.sql("SELECT * FROM ${t} WHERE 类别 = '食品'", df);   // SQL:支持中文列名/别名、= / <>、CTE、CASE WHEN
Jian.toExcel(hot, "食品.xlsx");                            // 或 toCsv/toJson/toParquet/toSql...

df.sql("SELECT 类别, sum(金额) AS 合计 FROM this GROUP BY 类别 HAVING 合计 > 10 ORDER BY 合计 DESC LIMIT 5");
```

## 模块清单(详情见 modules/<artifactId>/module.md)

| 模块 | 干什么 | 关键外部依赖 |
|---|---|---|
| jian-facade | 顶层门面,全部 read*/to*/write* 入口(`jian.Jian`) | 无 |
| jian-core | DataFrame 核心:列式存储 + 9 种 dtype + 变换/统计/时序/缺失值 | 无(纯 JDK) |
| jian-dsl | 三档引擎:L1 query / L2 eval / L3 SQL(SELECT/WHERE/GROUP/HAVING/JOIN/UNION/CTE/CASE/DML) | 无(纯 JDK) |
| jian-io-csv | CSV/TSV/FWF 读写(pandas.read_csv 对齐) | commons-csv |
| jian-io-excel | Excel .xls/.xlsx 读写 + ExcelWriter | POI |
| jian-io-json | JSON 读写(5 种 orient) | Jackson |
| jian-io-html | HTML 表格提取(文件/URL/字符串) | Jsoup |
| jian-io-xml | XML 读写 | Jackson XmlMapper |
| jian-io-sql | JDBC 通用读写(PG/MySQL/SQLite/H2/SQLServer/Oracle 方言自适应) | 纯 JDBC |
| jian-io-pickle | .jpk 自定义序列化(DataFrame 持久化) | Jackson |
| jian-io-clipboard | 系统剪贴板读写(xclip/pbcopy/clip 跨平台) | 无 |
| jian-io-latex | LaTeX 表格写出 | 无 |
| jian-viz | df.plot 绘图,13+ 种图,导出 PNG/SVG | XChart |
| jian-export | HTML/Markdown/LaTeX/控制台 渲染与样式(Styler:背景/渐变/数据条/字体颜色/加粗/自动列宽) | 无 |
| jian-num-bridge | jian-num 数值库的 SPI 桥(可选,core 自动发现) | 无 |
| jian-num | 数值库(Ndarray/Matrix/Stats,经 num-bridge 传递入包;独立 fat 见 jian-num-all) | commons-math3 |
| jian-sql-engine / jian-sql-expr / jian-sql-orm / jian-sql-bridge | SQL 引擎栈(经 sql-bridge 传递入包;独立 fat 见 jian-sql-all) | HikariCP/jOOQ |

## 相关库

- `jian-num-all`(独立数值库,对标 numpy 子集:Ndarray/Matrix/Stats/LinearFit,不依赖 jian)
- `jian-sql-all`(数据库引擎栈,对标 sqlalchemy:HikariCP 连接池 + jOOQ 表达式 + 轻量 ORM + DataFrame 桥)
- **`jian-columnar-all`(列存附加,68M)**:Parquet/ORC 读写 + ~45MB Hadoop 生态依赖,本 jar **不含**;
  需要时叠加 `-cp jian-all.jar:jian-columnar-all.jar`(未叠加时 readParquet/readOrc/toParquet/toOrc
  抛 ModuleNotLoadedException 带指引)
