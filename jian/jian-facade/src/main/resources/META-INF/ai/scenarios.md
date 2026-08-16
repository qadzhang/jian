# jian 真实场景集(15 个,AI 可直接照抄起步)

> 每个场景 = 真实业务背景 + jian 能力组合 + **可手算的预期结果**;本表是简版速查。
> **完整可执行标准答案**(构造数据的代码 + 逐条断言)在本 jar 的 `META-INF/ai/scenarios-src/`
> 三个 JUnit 源文件里(ScenarioSalesFinanceTest / ScenarioCleanReshapeTest / ScenarioAnalyticsOps),
> 每次 `mvn test` 都会用它们验证下表预期值 —— 拿不准 API 用法时,优先读这三个文件。
> 场景来源:基于 pandas 真实用例的网络调研(销售分析/对账/清洗/日志重采样/RFM/AB 测试/JSON 拍平等 7 类)。

| # | 场景 | 用到的 jian 能力 | 关键预期(可手算) |
|---|---|---|---|
| S1 | 门店×品类销售月度汇总 | readCsv → groupBy(品类).agg(sum) → sortBy → head | 食品 700 / 饮料 300,双店各 500 |
| S2 | 银行流水 vs 公司账对账 | merge("outer") → query(单边/金额不等) | 差异 3 行(T003/T004/T006),总额差 151 |
| S3 | 问卷缺失值清洗 | isna → dropna → fillna → colValueCounts | 缺失 年龄2/城市1;完整样本 3 行;均值 26.25 |
| S4 | API 日志按小时重采样 | resample("1h").mean()/count() | 10点 mean=150 / 11点 200 / 13点 250;计数守恒 6 |
| S5 | 电商客户 RFM 分层 | groupBy(客户).agg(min/sum) + query 计次 + 规则打分 | 高价值 2 人(555)、流失 2 人(111) |
| S6 | 落地页 A/B 测试 | groupBy(组).agg(sum/count) + colStd | A 0.6 / B 0.8,相对提升 33.33%,std≈1.5811 |
| S7 | 仓库安全库存预警 | query(现<安全) + assign(可售天数) + sortBy | 预警 S2/S3/S6;优先级 S3(0.4天)→S2→S6 |
| S8 | 成绩长宽透视与排名 | pivotTable + melt(roundtrip) + assign 总分 | 数学均 77.5 / 英语 80;melt 回 8 行无损 |
| S9 | HR 员工-部门多表合并 | merge inner/left 对比 + isna 找孤儿 | inner 5 行;left 6 行含 1 孤儿(钱) |
| S10 | 支付重试重复订单去重 | duplicated(subset) + dropDuplicates(keep=last) | 重复 3 条;1400→1000,虚增 400 |
| S11 | 嵌套 JSON 订单拍平 | jsonNormalize(customer.name/items.0.sku 全拍平) | 2 行;A1 GMV=50、A2=30,总 80 |
| S12 | 总表按区域拆分导出 | groupBy 遍历 + toExcel/readExcel 回读校验 | 3 文件、行守恒 6、金额守恒 920 |
| S13 | SQL 直查内存表 | Jian.sql(GROUP BY+HAVING+ORDER BY);写语句被拒 | HAVING 后仅 食品 700;A 店 4 单 |
| S14 | 汇率按生效日就近折算 | mergeAsof(backward) + assign 行级乘积 | 汇率 7.1/7.2/7.3/**7.3**/7.4,合计 3173 |
| S15 | 用户行为统计月报 | colCorr ±1 + colCumsum + colDiff 首行 NA(§3.5) | corr ±1.0;累计 [10..210];diff 首行缺失 |
| S16 | 财务月报样式导出 | Styler 全链:rowBackgroundIf(亏损整行红底)+format 原生千分位+backgroundGradient+boldIf+columnBackground+自动列宽 | POI 读回:亏损行整格红底/营收 NUMERIC 且 "#,##0" 可求和/明星店加粗/列宽>默认 |

## 30 秏上手(与场景同款风格)

```java
DataFrame df = Jian.readCsv("销售明细.csv");                          // S1
DataFrame 报表 = df.groupBy("品类").agg(Map.of("销售额", "sum"))
                   .sortBy("销售额_sum", false).head(2);
DataFrame 预警 = Jian.readExcel("库存.xlsx").query("现库存 < 安全库存")   // S7
                   .assign("可售天数", r -> ...);
```

## SQL 优势展示(SP 区:双写法差分,链式版 vs SQL 版逐行相等)

| # | 场景 | 链式版 | SQL 版(一条语句) |
|---|---|---|---|
| SP1 | 聚合报表 + TopN | groupBy→agg→sortBy→head(4 步 3 中间变量) | `SELECT 品类,sum(销售额) AS 合计 FROM ${t} GROUP BY 品类 HAVING 合计>400 ORDER BY 合计 DESC LIMIT 1` |
| SP2 | 复杂过滤 | query(AND + IN) + select | `SELECT 门店,品类 FROM ${t} WHERE 门店 IN ('A','B') AND 销售额 > 100` |
| SP3 | 连接 + 条件分层 | merge(inner) + assign 三元 | `SELECT 姓名,部门名,CASE WHEN 薪资>=10000 THEN '高' ELSE '常规' END AS 薪档 FROM ${e} JOIN ${d} ON e.部门ID=d.部门ID` |
| SP4 | 多步管道 | query → groupBy(2 个中间变量) | `WITH 大单 AS (SELECT * FROM ${t} WHERE 销售额>=100) SELECT 品类,count(*) AS 单数 FROM ${大单} GROUP BY 品类` |

每对结果**逐行断言相等**(差分测试):任一侧回归都会被另一方抓住。

## 安全写法(重要)

**用户可控值一律参数化,不要拼进表达式/SQL**:`Jian.query(df, "类别 == ${c}", Params.of("c", userInput))`
(引擎对占位值做字面量化 + `''` 翻倍转义);测试里的 `WHERE 类别 = '食品'` 拼接均为自控常量示范。
其它安全行为(XSS 转义 / SQL 标识符白名单 / readUrl 仅 http/https / ThreadLocal 容器警示)
见 `META-INF/ai/scenarios-src/jian/scenario/SecurityAuditTest.java`。

## 能力域覆盖

IO(CSV/Excel/JSON)· groupBy/agg · merge(inner/left/outer/asof)· pivot/melt ·
缺失值(isna/dropna/fillna)· resample · SQL(df.sql/Jian.sql)· 去重/排序/TopN · 派生列/统计(corr/cumsum/diff)
