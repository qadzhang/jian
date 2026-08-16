# jian-core

## 基本信息
- **library**: jian
- **entryClass**: jian.core.DataFrame
- **version**: 1.0.1
- **deps**: 纯 JDK 17(零外部依赖)
- **methods**: DataFrame ~180 unique public 方法名(含重载 195 处) + Series 52 public 方法(口径见 doc/api-counts.md)
- **tests**: 545(JUnit 5 @Test,口径见 doc/api-counts.md)

## 摘要
jian DataFrame 核心:列式存储 + 9 种 dtype + 变换 + 统计 + 时序。jian 库的基石模块,所有其它子模块依赖它。不可变优先(每次变换返回新 DataFrame,线程安全/Web 友好)。

## 能力清单

### record 强类型桥 + 列选择器(借鉴 Kotlin DataFrame convertTo/cols)
- `df.toRecords(Order.class)`:每行转 record 实例(组件名↔列名精确匹配,df 多余列忽略,组件缺列报错;类型跨族不隐式转换,先 astype)
- `DataFrame.fromRecords(List.of(new Order(...)))`:record 列表建表,组件声明类型精确定 DType(String/int/long/double/boolean/LocalDate/LocalDateTime/其它→OBJECT)
- `df.selectBy(col -> col.startsWith("q"))`:谓词选列,无命中返回 0 列表

### 对齐 pandas 的语义细节
- colNe/compare 族缺失行语义:== 与顺序比较 false、**!= true**(对齐 pandas NaN!=x 与 query 双引擎)
- merge 重名列**两边都加**后缀(自连接输出 [id, v_x, v_y],对齐 pandas)
- colSum/colMean 内核为 **Neumaier** 补偿求和(极端量级混合更精确,[1e16,1,2,-1e16] 得精确 3.0;溢出保留 ±Infinity)
- merge 异名键(leftOn≠rightOn)右表键列保留输出 `[k1, x_x, k2, x_y]`,右表独有行左键 null(对齐 pandas)
- ffill/bfill/pad/backfill 与 where/mask **全 dtype 保真**(不降 OBJECT);colRound 为 **half-even** 银行家舍入(大数不饱和);nunique/valueCounts/is_unique 的 **±0.0 计 1**;isin 的 values 含 NaN 时 **NaN 行命中**

### DataFrame 变换(链式,不可变)
- **基础**:filter/sortBy/sortIndex/merge(4 how)/concat/pivotTable/melt/transpose/T/assign/astype(8种 dtype)/slice/head/tail/select/drop/takeRows/iloc/loc
- **索引/成员/采样**:idxmax/idxmin/duplicated(subset,keep)/resetIndex/setIndex/sample(n,replace,seed)/pipe(fn)/applyRow(newCol,fn)/isin/colIsin/where(mask,other)/mask(mask,other)/info()/selectDtypes(include,exclude)
- **扩展统计**:colSkew/colKurt/colMad/colSem/colQuantile/colVar/colProd/colNunique/colAll/colAny/colCorr/colCov/colRank(average/min/max/first/dense)/colCumsum/colCummax/colCummin/colCumprod/colDiff/colPctChange/colClip/colRound/corrMatrix/covMatrix
- **重塑与连接**:pivot(简单版,无聚合)/explode(col,list展平)/stack(idCols,valueCols)/unstack(idCol,keyCol,valCol)/join(on,how)/mergeAsof(on,按最近键)/addScalarAllColumns/sub/mul/div
- **时序**:shift(col,periods)/resample(tsCol,rule)→Resampler(sum/mean/count/min/max/median/std/var/ohlc/agg/first/last)/atTime/betweenTime/asof
- **缺失值补充**:interpolate(线性插值)/notna/notnull/pad/backfill(别名)/astype 扩 BOOL+DATETIME+DATE/tzLocalize(col,zoneId)/tzConvert(col,zoneId)

### Series(单列,52 方法,口径见 doc/api-counts.md)
- 统计:count/sum/mean/median/min/max/std/percentile/diff/shift/pctChange
- 排序:sortIndicesAscending/Descending
- accessor:str()/dt()(year/month/day/hour/minute/second/dayOfWeek/dayOfYear)
- **pandas 同名方法**:tolist/to_dict/to_numpy/argmax/argmin/between(left,right)/is_monotonic_increasing/is_unique/hasnans

### 缺失值(统一语义)
- NaN 不失真(DOUBLE 内部 NaN,IO 边界转 null)
- fillna(单值 或 Map 按列,对齐 pandas fillna(dict))/dropna/ffill/bfill/isna/notna/notnull/interpolate(线性)/where/mask/pad/backfill

### 查询(SQL 经 SPI)
- query(L1 布尔表达式:>/</>=/<=/==/!=/and/or/not/in/not in/notin/**算术 + - * / %**/between/like/is null/**is true/is false**;反引号标识符 `` `col with space` ``;字符串 `''`/双引号/反斜杠三种转义等价;数值不再隐式当布尔)
- core 兜底(SimpleQueryParser)与 jian-dsl 主路径(PrattEngine)语法矩阵由 EngineConformanceTest 互证一致
- renameColumns(Map 对齐 pandas df.rename(columns=...))/eval(L2 派生列)/sql(L3 SQL 子集,需 jian-dsl)

### 统计语义(对齐 pandas)
- 9 个派生新列算子(cumsum/diff/pct_change/clip/round/rank 等)newColName=null 时兜底 `{col}_{op}`
- corr/cov:同下标双非 NaN 配对(错位 NaN 不错配);N<2 或全常量列返 NaN(pandas 一致)
- 非数值列 rank 走自然序(pandas str rank 一致);groupBy sum 对非数值列拼接(pandas 一致)

### 性能
- ColumnarHashMap(开放寻址,JOIN/GroupBy 单列数值 key fast path,快 9-17x)
- 不可变 + 线程安全(Web/Tomcat 友好)

### 基础设施
- MultiIndex(N 级,droplevel/swaplevel/reorder_levels)
- DatetimeIndex(freq/atTime/betweenTime/asofIndex/firstValidIndex/lastValidIndex/inferFreq)
- Frequency(parse("1D"/"2H"/"1W"/"ME"/"YS"...),plus/minus/range/stepsBetween)
- StatsProvider SPI(pearson/spearman/covariance/percentile/skewness/kurtosis/rank/mad/sem)
- Resampler(17 方法:sum/mean/count/min/max/median/std/var/ohlc/agg/first/last)

### 行为细节
- 整数字面量按 long 精确比较(双引擎:query/eval 的 >2^53 字面量不经 double 舍入)
- 字符串拼接遇缺失行传播缺失(null)
- 显式 LONG/INT schema 装超范围值抛 IAE(不静默截断;推断路径自动归 STRING)
- renameColumns/isetitem 实现体位于 DataFrameChain 伴生类(主类满足 §3.1 行数红线)

- groupBy 含 null 的 size 不抛 NFE;setIndex 唯一列返回 0 列 N 行;withColumnClone 入口;0 行 IO round-trip 保列

### 行为细节(续 1)
- setIndex 多列构建 MultiIndex;pivotTable 缺失键行按 pandas dropna 丢弃
- merge:right/outer 行序对齐 pandas(right 按右表序 / outer 按键首现序);输出列保留源 dtype(0 行/全 null 不降级)
- spearman 并列取平均秩;interpolate 对无缺失的整型列直通(不降级)
- sortBy 混型键抛 IAE(doc/00 §10.16 第 4 条五入口);ohlc 跳过桶首缺失;resample("1ME") 跨短月正确分桶

## 限制
- 单机内存(不追求分布式/集群)
- CATEGORY dtype 仅枚举占位(未完整语义;astype CATEGORY 抛 IAE)
- 窗口函数 OVER/PARTITION BY 在 L3 SQL 不支持(用 Resampler/colRank/Series.rolling 替代,或经 SqlEngines.useCustom() 接入外部引擎)
- DML(INSERT/UPDATE/DELETE)经 SqlDml 返回新 DataFrame(不原地修改)

## 快速上手
```java
import jian.core.DataFrame;
import jian.core.Schema;
import jian.core.DType;

DataFrame df = DataFrame.of(
    Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),
    new Object[][]{
        {1L, "alice", 90.5},
        {2L, "bob", 85.0},
        {3L, "carol", 76.5}
    });

// 基础变换(链式,不可变)
DataFrame r = df.filter(df.colGt("score", 80.0))
                .sortBy("score", false)
                .select("name", "score");

// 统计
double mean = df.colMean("score");  // 84.0
double skew = df.colSkew("score");  // 偏度(经 SPI)
double corr = df.colCorr("id", "score");  // 皮尔逊相关

// 采样 + 掩码
DataFrame sample = df.sample(2, false, 42L);  // 无放回采样 2 行,种子 42
boolean[] mask = df.isin(1L, 2L);  // 行级成员判断

// 时序(Resampler)
DataFrame daily = df.resample("ts", "1D").sum();  // 日聚合
DataFrame ohlc = df.resample("ts", "1D").ohlc("price");  // K 线

// 重塑
DataFrame stacked = df.stack(new String[]{"id"}, new String[]{"score"});
DataFrame pivoted = df.pivot("date", "city", "temp");
```
