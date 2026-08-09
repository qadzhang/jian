# jian-core

## 基本信息
- **library**: jian
- **entryClass**: jian.core.DataFrame
- **version**: 1.0.0(2026-08-09 阶段 A-F + L1-L10 完整实现后)
- **deps**: 纯 JDK 17(零外部依赖)
- **methods**: DataFrame ~180 unique public 方法名(含重载 195 处) + Series 52 public 方法(口径见 doc/api-counts.md)
- **tests**: 412(JUnit 5 @Test,口径见 doc/api-counts.md)

## 摘要
jian DataFrame 核心:列式存储 + 9 种 dtype + 变换 + 统计 + 时序。jian 库的基石模块,所有其它子模块依赖它。不可变优先(每次变换返回新 DataFrame,线程安全/Web 友好)。

## 能力(2026-08-09 完整清单)

### DataFrame 变换(链式,不可变)
- **基础**:filter/sortBy/sortIndex/merge(4 how)/concat/pivotTable/melt/transpose/T/assign/astype(7种 dtype)/slice/head/tail/select/drop/takeRows/iloc/loc
- **阶段 A 新增**:idxmax/idxmin/duplicated(subset,keep)/resetIndex/setIndex/sample(n,replace,seed)/pipe(fn)/applyRow(newCol,fn)/isin/colIsin/where(mask,other)/mask(mask,other)/info()/selectDtypes(include,exclude)
- **阶段 B 新增**:colSkew/colKurt/colMad/colSem/colQuantile/colVar/colProd/colNunique/colAll/colAny/colCorr/colCov/colRank(average/min/max/first/dense)/colCumsum/colCummax/colCummin/colCumprod/colDiff/colPctChange/colClip/colRound/corrMatrix/covMatrix
- **阶段 C 新增**:pivot(简单版,无聚合)/explode(col,list展平)/stack(idCols,valueCols)/unstack(idCol,keyCol,valCol)/join(on,how)/mergeAsof(on,按最近键)/addScalarAllColumns/sub/mul/div
- **阶段 D 新增**:shift(col,periods)/resample(tsCol,rule)→Resampler(sum/mean/count/min/max/median/std/var/ohlc/agg/first/last)/atTime/betweenTime/asof
- **阶段 F 新增**:interpolate(线性插值)/notna/notnull/pad/backfill(别名)/astype 扩 BOOL+DATETIME+DATE/tzLocalize(col,zoneId)/tzConvert(col,zoneId)

### Series(单列,52 方法,口径见 doc/api-counts.md)
- 统计:count/sum/mean/median/min/max/std/percentile/diff/shift/pctChange
- 排序:sortIndicesAscending/Descending
- accessor:str()/dt()(year/month/day/hour/minute/second/dayOfWeek/dayOfYear)
- **新增**:tolist/to_dict/to_numpy/argmax/argmin/between(left,right)/is_monotonic_increasing/is_unique/hasnans

### 缺失值(统一语义)
- NaN 不失真(DOUBLE 内部 NaN,IO 边界转 null)
- fillna/dropna/ffill/bfill/isna/notna/notnull/interpolate(线性)/where/mask/pad/backfill

### 查询(SQL 经 SPI)
- query(L1 布尔表达式:>/</>=/<=/==/!=/and/or/not/in/between/like/is null)
- eval(L2 派生列)/sql(L3 SQL 子集,需 jian-dsl)

### 性能
- ColumnarHashMap(开放寻址,JOIN/GroupBy 单列数值 key fast path,快 9-17x)
- 不可变 + 线程安全(Web/Tomcat 友好)

### 基础设施
- MultiIndex(N 级,droplevel/swaplevel/reorder_levels)
- DatetimeIndex(freq/atTime/betweenTime/asofIndex/firstValidIndex/lastValidIndex/inferFreq)
- Frequency(parse("1D"/"2H"/"1W"/"ME"/"YS"...),plus/minus/range/stepsBetween)
- StatsProvider SPI(pearson/spearman/covariance/percentile/skewness/kurtosis/rank/mad/sem)
- Resampler(17 方法:sum/mean/count/min/max/median/std/var/ohlc/agg/first/last)

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
