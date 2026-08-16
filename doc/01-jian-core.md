# 01 · jian-core 需求说明书

> 版本:v0.3(大面对齐 pandas)· 日期:2026-08-01 · 作者:zc · 依赖:JDK 17(零外部 jar)

---

## 1. 模块定位

### 1.1 一句话定位

jian-core **大面对齐 pandas 3.x 的 DataFrame/Series/GroupBy/窗口 核心数据操作能力**——**DataFrame 主体实测 ~180 unique public 方法名(含重载共 195 处,15 大类),Series 52,GroupBy 9 个,Window 7 个**(口径见 [`doc/api-counts.md`](api-counts.md))。是 jian 所有子模块(io / viz / export)的基石,**零外部依赖**(仅 JDK 17)。

> **口径标注(经源码核实)**:`DataFrame.java` 实测 195 个 public 方法(含重载)/ ~180 unique 名;pandas 进阶能力(resample/tz_convert/stack/unstack/interpolate/explode/reindex/merge_asof 等)**已实现**(见 §3.16)。本分册以下按"已实现 / 规划"二分,不把规划写成已实现。**所有 API/测试数字以 [`doc/api-counts.md`](api-counts.md) 为唯一事实来源**。

### 1.2 范围说明

本分册覆盖 jian-core 实际实现的 **DataFrame/Series/GroupBy/Window 核心数据操作方法**(不含 IO/绘图/样式,那些在 02-04)。为可读性拆成 4 个内部分包:

| 内部分包 | 对应 pandas 内容 |
|---|---|
| `core.frame` | DataFrame 主体(已实现 ~180 unique public 方法,见 §3;口径见 api-counts.md) |
| `core.series` | Series(一维)及其专属方法(str/dt accessor,见 §4) |
| `core.groupby` | GroupBy 对象 + agg/transform/filter(已实现 9 个 public 方法,见 §5) |
| `core.window` | Rolling / Expanding / EWM(7 个聚合)/ Resampler(17 方法,**已实现**,见 §6) |

> 这 4 个分包**合在同一个 jar** `jian-core` 内,不拆 jar(它们强耦合,拆开无意义)。但对外 API 各自独立类。

### 1.3 职责边界

**做**:pandas DataFrame/Series/GroupBy/Window 的全部数据操作(见 §3 全方法清单)。

**不做**:
- IO(CSV/Excel/... → 见 02)。
- 绘图 → 见 03。
- 样式/导出 → 见 04。
- 多层索引 MultiIndex 的复杂场景 —— v1 实现**二级以内**,API 预留但不实现 N 层。
- 稀疏数据结构 SparseDtype —— v2。
- 与 Arrow 的零拷贝互转 —— v2。

### 1.4 依赖关系

```
jian-core  (纯 JDK 17,零外部 jar)
     ▲
     │ (单向依赖,core 不知道谁依赖它)
     │
jian-io-* / jian-viz / jian-export
     │
     └─ (可选) jian-num 通过 SPI 提供 StatsProvider,描述统计走它;找不到 core 内置兜底
```

---

## 2. 数据结构设计

### 2.1 列式存储

```
DataFrame
  ├── Index rowIndex            // 行标签
  ├── List<String> columns      // 列名(有序、可重复→报错)
  └── Map<String, Column> data  // 列名 → 列数据
                │
                └── Column:类型化容器
                     ├── IntColumn / LongColumn / DoubleColumn(dbl 用 NaN 表缺失)
                     ├── BoolColumn / StringColumn
                     ├── DateTimeColumn(LocalDateTime)/ DateColumn(LocalDate)
                     ├── CategoryColumn(分类,有限离散值)
                     └── ObjectColumn(兜底)
```

### 2.2 缺失值约定

| 类型 | 缺失值 |
|---|---|
| double | `Double.NaN`(与 pandas 一致) |
| Integer/Long/Boolean | `null`(装箱) |
| String | `null` 或 `""`(可配) |
| LocalDateTime/LocalDate | `null` |

### 2.3 类型推断

从严到宽扫值:Integer → Long → Double → Boolean → LocalDateTime → String → Object。空列默认 String。可被 `Schema` 显式覆盖。

---

## 3. DataFrame 方法全清单(15 大类,对齐 pandas)

> **口径**:本节按"已实现 / 规划"二分:已实现 = `DataFrame.java` 实测存在;规划 = 列入 §3.16 路线图。各小节括号内数字为**实测已实现数**(经 grep 核实)。

### 3.1 属性与底层数据(已实现 5)

**已实现**:`index` / `columns` / `dtypes` / `size`(经 `Math.multiplyExact` 防溢出)/ `shape` / `columnCount` / `rowCount` / `columnNames` / `columnIndex(name)` / `isEmpty()` / `allowsDuplicateLabels()` / `values`(`toObjectArray`)/ `toString()`。

**规划(列 §3.16)**:`info()` / `select_dtypes(include,exclude)` / `axes` / `ndim`(固定为 2)/ `memory_usage()` / `attrs`(字典元数据)。

### 3.2 类型转换(已实现 2)

**已实现**:`astype(colName, DType)` 支持以下 **8 种** dtype:DOUBLE/LONG/INT/STRING/BOOL/DATE/DATETIME/OBJECT(见 `DataFrame.convertColumn`);**仅 CATEGORY 抛 IllegalArgumentException**(pandas category dtype 是稀疏存储优化,jian 用 StringColumn 覆盖,v2 再评估)/ `copy(deep=true)`(经 `DataFrame.ofColumns` 复制)。

**规划**:`convert_dtypes()` / `infer_objects()` / `to_numpy(dtype)`。

### 3.3 索引与迭代(已实现 7)

**已实现**:
- 标量/区域:`get(row,col)` / `getRow(i)` / `loc(labels...)`(标签选择)/ `iloc(indices...)`(位置选择)/ `takeRows(int[])`。
- 头尾:`head(n)` / `tail(n)` / `slice(start, end)`。
- 迭代:`iterRows()`(返回 `Iterable<Object[]>`)。
- 条件:`query(expr)`(解析 `and/or/not/>/</>=/<=/==/!=/in/not in/notin/算术 + - * / %/between/like/is null/is true/is false`;反引号标识符 `` `col with space` ``;字符串 `''`/双引号/反斜杠三种转义等价)。core 兜底(SimpleQueryParser)与 jian-dsl 主路径(PrattEngine)语法矩阵由 `EngineConformanceTest` 锁定一致;数值不再隐式当布尔(§10.16 第 10 条)。
- 列过滤:`select(cols...)` / `drop(cols...)` / `filter(items/like/regex)`。

**规划**:`at/iat/isetitem`(单单元格)/ `xs(key,axis,level)` / `insert/pop` / `iteritems/itertuples/keys` / `isin/where/mask` / `add_prefix/add_suffix`。

### 3.4 二元运算(已实现 12 个列级 colXxx)

**已实现**(列级二元运算,生成新列,API 形如 `colAdd(newCol, srcA, srcB)`):
- 算术:`colAdd` / `colSub` / `colMul` / `colDiv`(标量版 `colMulScalar` 经 `assign`)。
- 比较:`colLt` / `colGt` / `colLe` / `colGe` / `colNe` / `colEq`(返回 BOOLEAN 掩码列)。
  缺失行语义:`==` 与顺序比较为 **false**、`!=` 为 **true**(对齐 pandas `NaN != x → True`,与 query 双引擎口径一致)。
- 极值:`colMax(cols...)` / `colMin(cols...)`(行向 max/min)。

**语义注记**:`colRound` 为 half-even 银行家舍入(对齐 pandas,大数不饱和);
nunique/valueCounts/is_unique 中 ±0.0 数值等价计 1(§10.16 第 6 条延伸);isin 的 values 含 NaN 时 NaN 行命中。

**规划**:`add/sub/mul/div/mod/pow` 全套反向运算 / `dot`(矩阵)/ `combine/combine_first` / `fill_value` / `axis` 参数。

### 3.5 函数应用、GroupBy、窗口入口(已实现 3 个入口)

**已实现**:
- `groupBy(byCols...)` —— 返回 `GroupBy` 对象(见 §5,实测 9 个 public 方法)。
- `applyNumeric(colName, func)` / `applyStr(colName, func)` —— 按列应用(返回新列)。
- `eval(expr)` / `sql(sql, dfs...)` —— DSL 入口(经 jian-dsl,见 07)。

**规划**:`apply(func,axis=0/1)` / `map(func)` / `pipe(func)` / DataFrame 上的 `agg/transform`(`agg` 仅在 GroupBy 对象上)。

### 3.6 计算 / 描述统计(已实现:colXxx 列统计 + 行式聚合 + describe)

**已实现**(列级统计,返回标量,API 形如 `colSum(colName)`):
- `colSum` / `colMean` / `colMedian` / `colMin` / `colMax` / `colStd` / `colPercentile(colName, q)` / `colVar`(经 StatsProvider SPI,需要 jian-num-bridge)。sum/mean 内核为 Neumaier 补偿求和(误差项独立累加、末步 sum+comp 修正,极端量级混合下比经典 Kahan 更精确,复杂度同 O(n);溢出时保留 ±Infinity 不退化为 NaN)。
- 行式聚合:`sum()` / `mean()` / `min()` / `max()` / `median()` / `std()`(数值列聚合)。
- 描述:`describe()`(返回 8 行统计表 count/mean/std/min/25%/50%/75%/max)。

**规划**:`prod/product` / `abs` / `nunique`(DataFrame 级)/ `mode` / `sem` / `skew/kurt` / `quantile(q)`(DataFrame 级)/ `rank` / `cumsum/cumprod/cummax/cummin` / `diff` / `pct_change` / `corr/corrwith/cov` / `clip/round` / `all/any` / `value_counts`。

### 3.7 重索引 / 选择 / 标签操作(已实现 13+)

> **口径**:本节以代码实测为准(`renameAxis(String)` / `filter(boolean[])` / `takeRows(int[])` 等);`rename(mapper)` / `filter(items/like/regex)` / `equals(other)` / `take(indices)` / `truncate(before,after)` 等未实现,列入下方"规划"。

**已实现(源码实测)**:
- 选择:`drop(cols...)` / `dropDuplicates(subset)` / `filter(boolean[] mask)` / `select(cols...)` / `takeRows(int[])` / `head(n)` / `tail(n)` / `slice(from,to)` / `sample(n, replace, seed)` / `pop(name)`
- 索引操作:`resetIndex()` / `setIndex(col)` / `setAxis(labels)` / `renameAxis(name)` / `reindexLike(other)` / `withIndex(index)`
- 标识:`duplicated(subset, keep)` / `idxmax(col)` / `idxmin(col)`

**规划(未实现)**:`rename(mapper 函数式)` / `filter(items/like/regex 形式)` / `equals(other)` / `take(indices 别名)` / `truncate(before,after)` / `reindex(targetLabels)` / `align(other)` / `first()/last()`。详见 §3.16 路线图集中清单。

### 3.8 缺失值处理(已实现 11+)

**已实现**:`isna()`(返回掩码 DataFrame)/ `notna()` / `isnull()` / `notnull()` / `dropna(how)` / `fillna(value)` / **`fillna(Map<列名,值>)`**(按列填充,对齐 pandas fillna(dict))/ `ffill()` / `bfill()` / `pad()`(ffill 别名)/ `backfill()`(bfill 别名)/ `interpolate(method=linear)` / `where(cond)` / `mask(cond)` / `isin(values)` / `replace(toReplace, value)` / **`renameColumns(Map<旧,新>)`**(列重命名,对齐 pandas df.rename(columns=...))。

**规划**:`interpolate(method=time/index/spline)` / `fillna(method/limit/downcast)`。详见 §3.16。

### 3.9 重塑、排序、转置(已实现 12+)

**已实现**:
- 排序:`sortBy(by, asc)`(TimSort 稳定,nullsLast)/ `sortIndex(asc)`
- Top-N:`nlargest(n, byCol)` / `nsmallest(n, byCol)`
- 重塑:`pivotTable(index, columns, values, aggFn)` / `pivot(index, columns, values)`(简单版,无聚合)/ `melt(idVars, valueVars)` / `transpose()` / `T`(transpose 别名)/ `stack()` / `unstack()` / `explode(col)`(list 列展平)
- 索引层级(MultiIndex):`droplevel(level)` / `swaplevel(i,j)` / `reorder_levels(order)`

**规划**:`squeeze(axis)` / `pivot_table` 多 aggfunc 列表。详见 §3.16。

### 3.10 合并 / 连接(已实现 7+)

**已实现**:`merge(right, how, on)`(how = inner/left/right/outer)/ `mergeAsof(right, on)`(按最近键对齐)/ `concat(objs, axis)` / `join(other, on)` / `assign(newCol, IntFunction)` / `combineFirst(other)` / `compare(colName, op, value)`(返回 BoolColumn,与 pandas `compare` 语义不同,见 §3.16)。

**规划**:`merge_ordered` / `update` / `compare(other, align_axis)`(pandas 风格差异对比)。详见 §3.16。

### 3.11 时间序列(已实现 6+ DatetimeIndex/Frequency 基础设施)

> **口径**:`shift` / `resample` / `atTime` / `betweenTime` / `asof` 经代码实测均已实现(§3.16 已列出),按代码实际描述。

**已实现(源码实测)**:
- DataFrame 级:`shift(colName, periods)` / `shift(colName, periods, newColName)` / `resample(tsCol, rule)`(返回 Resampler,含 18 方法 sum/mean/count/min/max/median/std/var/ohlc/agg/first/last 等)/ `atTime(tsCol, time)` / `betweenTime(tsCol, start, end)` / `asof(label)`
- 基础设施:`DatetimeIndex`(atTime/betweenTime/asofIndex/firstValidIndex/lastValidIndex/inferFreq 等)/ `Frequency`(parse "1D"/"2H"/"1W"/"ME"/"YS" + plus/minus/range/stepsBetween)
- Series 级:`diff(periods)` / `shift(periods)` / `pctChange(periods)`

**规划**:`tshift` / `asfreq(freq)` / `to_period/to_timestamp` / `tz_convert/tz_localize` / `first_valid_index/last_valid_index`(DataFrame 顶层入口,现经 DatetimeIndex 访问)。详见 §3.16。

> 派生方法(`dt` accessor 的 year/month/day 等只读属性)在 Series 上已实现(见 §4.3)。

### 3.12 标志与元数据(已实现 1)

**已实现**:`allowsDuplicateLabels()`(构造时 `setFlags(...)` 设置)。

**规划**:`attrs`(字典元数据)/ 完整 `set_flags` 链。

### 3.13 字符串处理(委托 `.str` accessor,Series 上)

见 §4.2 Series 的 `.str`(lower/upper/title/strip/len/contains/replace/split 等)。

### 3.14 绘图(委托 viz 模块)

`plot/boxplot/hist` —— core 不实现,委托 `jian-viz`(13 种图,见 03 分册)。

### 3.15 IO(委托 io 模块)

所有 `to_xxx` / `read_xxx` 委托 io 子模块(12 格式,见 02 分册)。

### 3.16 路线图(原"规划项"已全部落地)

> 本节原是"规划集中列表";DataFrame 现 ~195 public 方法 / Series 52(口径见 [`api-counts.md`](api-counts.md)),**原"仍规划"项已全部落地**。
> 现仅保留 3 项**真正不做的**(设计决策性排除,非"来不及做")。

#### ✅ 已实现(共 120+ 方法)

| 类别 | 已实现方法 |
|---|---|
| 属性(§3.1)| `info()` / `selectDtypes` / `axes` / `ndim` / `memoryUsage` / `attrs` |
| 类型转换(§3.2)| `astype` 8 种 dtype(仅 CATEGORY 抛异常)/ `inferObjects` / `convertDtypes` / `toNumpy` |
| 索引(§3.3)| `idxmax/idxmin` / `duplicated` / `resetIndex/setIndex` / `at/iat/isetitem` / `insert/pop` / `iterrows/itertuples/items/keys` / `addPrefix/addSuffix` |
| 二元(§3.4)| `add/sub/mul/div ScalarAllColumns` / `dot` / `abs` / `combineFirst` |
| 函数应用(§3.5)| `applyRow` / `pipe` / `selectBy`(谓词选列) |
| record 桥(借鉴 Kotlin DataFrame convertTo)| `toRecords(Class)` 每行转 record / `DataFrame.fromRecords(List)` record 列表建表(组件名↔列名,DType 精确映射) |
| 统计(§3.6)| `colSkew/colKurt/colMad/colSem/colQuantile/colVar/colProd/colNunique/colAll/colAny/colCorr/colCov/colRank/colCumsum/colCummax/colCummin/colCumprod/colDiff/colPctChange/colClip/colRound/corrMatrix/covMatrix/colMode/colValueCounts` |
| 重索引(§3.7)| `sample` / `reindex` / `reindexLike` / `squeeze` / `renameAxis` / `setAxis` / `firstValidIndex/lastValidIndex` |
| 缺失值(§3.8)| `interpolate` / `isin/colIsin` / `where/mask` / `notna/notnull` / `pad/backfill` |
| 重塑(§3.9)| `pivot` / `explode` / `stack/unstack` |
| 合并(§3.10)| `join` / `mergeAsof` |
| 时序(§3.11)| `shift` / `resample`(17方法) / `atTime/betweenTime` / `asof` / `tzLocalize/tzConvert` / DatetimeIndex / Frequency |
| Series(§4.1)| `tolist/to_dict/to_numpy/argmax/argmin/between/is_unique/hasnans/is_monotonic_increasing` |
| SQL L3 | CASE WHEN / CTE / 派生表 / 集合运算 / USING 多列 / CROSS JOIN / 可插拔引擎接口 |
| SQL DML | INSERT / UPDATE / DELETE(返回新 DataFrame) |

#### ❌ 设计决策排除(不做,非"来不及")

| 项 | 原因 |
|---|---|
| `astype CATEGORY` | CATEGORY dtype 语义未设计完整(jian v1 无分类编码层;pandas CATEGORY 底层是 int codes + categories 数组,jian 无此机制) |
| `WINDOW_FUNCTIONS`(SQL OVER PARTITION BY) | 默认引擎不支持;用户可经 `SqlEngines.useCustom()` 接入外部引擎,或用 jian Resampler/colRank/Series.rolling 替代(功能等价) |
| `align(other, join)` | 双表对齐 + reindex 组合;pandas 特有(jian 用 merge + reindex 组合实现等价效果) |

---

## 4. Series 与 accessor

### 4.1 Series 方法(对齐 pandas Series;实测 52 个 public 方法;口径见 api-counts.md)

DataFrame 单列即 Series(`df.getSeries(colName)`)。

> **口径**:`Series.java` 实测 52 个 public 方法(详见下表,口径见 [`api-counts.md`](api-counts.md));`tolist/to_dict/to_numpy/argmax/argmin/between/isna/isnull/is_unique/hasnans/is_monotonic_increasing` 等**已实现**(见下表)。

**已实现**:
- 构造:`Series.of(data, name)`(经 DoubleColumn/LongColumn/StringColumn 等具体列类型)。
- 属性:`size` / `name` / `dtype` / `column`(底层 Column 引用)。
- 索引:`get(i)` / `getDouble(i)` / `isNull(i)` / `head(n)` / `tail(n)` / `slice(start,end)`。
- 转换:`toFrame()` / `toArray()` / `toString()` / `describe()`。
- 统计(返回标量):`count` / `sum` / `mean` / `median` / `min` / `max` / `std` / `percentile(q)`。
- 时序(差分/位移):`diff(periods)` / `shift(periods)` / `pctChange(periods)`。
- 排序:`sortIndicesAscending()` / `sortIndicesDescending()`。
- accessor:`str()`(返回 StrAccessor,见 §4.2)/ `dt()`(返回 DtAccessor,见 §4.3)。

**pandas 同名方法(9 个)**:`tolist()` / `to_dict()` / `to_numpy()` / `argmax()` / `argmin()` / `between(left, right)` / `is_monotonic_increasing()` / `is_unique()` / `hasnans()`。

**规划(列 §3.16)**:`nsmallest/nlargest`(DataFrame 上已有) / `case_when` / `shape` / `squeeze`。

### 4.2 `.str` 字符串 accessor(对齐 pandas.Series.str)

`lower/upper/title/strip/lstrip/rstrip` / `len` / `contains(pat,regex)` / `startswith/endswith` / `replace(pat,repl,regex)` / `split(pat,expand)` / `cat(sep)` / `get(i)` / `extract(pat)` / `findall(pat)` / `contains` / `match` / `pad(width,side)` / `zfill(width)` / `repeat(n)` / `slice(start,stop)` 等。

### 4.3 `.dt` 时间 accessor(已实现只读属性;tz/period 规划)

**已实现**:`year` / `month` / `day` / `hour` / `minute` / `second` / `dayOfWeek` / `dayOfYear`。

**规划**:`date/time` / `weekofyear/quarter` / `is_month_end/is_month_start` / `tz_localize/tz_convert` / `normalize()` / `to_period(freq)`。

### 4.4 `.cat` 分类 accessor(全部规划)

> **诚实标注**:`.cat` accessor 在 jian-core 中**未实现**(grep 0 命中)。CATEGORY dtype 仅在 DType 枚举里保留为占位。规划在 v2。

### 4.5 Index 对象

`Index` / `RangeIndex`(Range 步长)。方法:`size` / `get(i)` / `labels()` / `isRange()` / `toObjectArray()`。

**规划**:`DatetimeIndex` / `MultiIndex`(多级) / `isin` / `get_loc` / `get_indexer` / `unique` / `drop_duplicates` / `union/intersection/difference` / `astype`。

---

## 5. GroupBy 对象(对齐 pandas.core.groupby;实测 9 个 public 方法)

`df.groupBy("dept","level")` 返回 `GroupBy`,实际支持:

**已实现**:
- 聚合:`agg(colName, fn)` / `agg(Map<colName, fn>)`(fn 支持 **11 种**:`count/nunique/sum/mean/min/max/first/last/median/std/var`)。
- 变换:`transform(col, fn) → double[]`(原列就地变换,返 double 数组)/ `transformAsColumn(newColName, col, fn) → DataFrame`(变换结果作为新列追加,返新 DataFrame)。
- 过滤:`filter(colName, fn, predicate)`(组级谓词)。
- 元信息:`size()`(组大小)/ `groupCount()` / `iterGroups()`(返回 `GroupEntry(key, int[] idx)` 迭代器)。

> **NaN/缺失值分组语义**:DOUBLE 列含 NaN 时走 generic 路径,所有 NaN 归入同一组(等价 pandas `dropna=False`);LONG 列 null 归一为字符串 `"<NA>"`。pandas 默认 `dropna=True`(丢弃缺失组),若需该语义,链式调 `df.filter(...)`。详见 `GroupBy.buildGroups` javadoc 与 `EdgeCaseTest.NaN分组键归一组`。

resample/stack/unstack/tz_* 等在 §3.16 "已实现"区。GroupBy 本身的 transform/agg/filter/iterGroups 已够用;未实现的 nth/sem/ohlc/prod 聚合是低频,用户可用 DataFrame 级 colCumsum/colDiff/colClip + Resampler.ohlc 替代。

---

## 6. 窗口与重采样(对齐 pandas 窗口族;实测 7 个聚合)

> `Window.java` 实现 7 个聚合方法;Resampler(17 方法)**已实现**。

### 6.1 Rolling(`Series.rolling(window)`)
**已实现**(7):`mean` / `sum` / `std` / `min` / `max` / `count` / `var`(全部返回 `double[]`)。

**规划**:`median` / `apply(func)` / `agg` / `quantile` / `corr/cov` / `kurt/skew` / `sem` / `rank` / `ewm`(链式 EWM)。

### 6.2 Expanding(`Series.expanding(minPeriods)`)
**已实现**(4):`mean` / `sum` / `min` / `max`(累积式窗口)。

**规划**:同 Rolling 的方法集。

### 6.3 EWM(`Series.ewm(alpha)`)
**已实现**(3):`mean` / `var` / `std`(指数加权)。

**规划**:`corr/cov`。

### 6.4 Resampler(`df.resample(rule)` —— 全部规划)

> Resampler **已实现**(17 方法:sum/mean/count/min/max/median/std/var/ohlc/agg/first/last);DatetimeIndex + Frequency 基础设施已落地。

---

## 7. 核心 API 风格示例

> 本节示例按 jian 实际 API 编写,所有方法均经源码核实可用。

```java
// 链式(不可变优先,每步返回新 DataFrame)
DataFrame result = df
    .drop("unused")
    .query("age > 18 && score < 100")
    .assign("grade", "A")              // 新增常量列;表达式列用 colXxx
    .sortBy("score", false)            // 按 score 降序(TimSort 稳定)
    .groupBy("dept")                   // 返回 GroupBy
    .agg("salary", "mean")             // 聚合:11 种 fn 可选(count/sum/mean/...)
    .head(10);

// 索引
df.loc("Alice");                       // 标签选择(显式 Index 时)
df.iloc(0, 2, 5);                       // 位置选择(行索引 varargs;不接列)
df.query("salary > 10000 && dept is not null");

// 字符串列(经 .str accessor,在 Series 上)
df.getSeries("name").str().upper();
df.getSeries("ts").dt().month();       // .dt 只读属性(year/month/day/...;tz/period 规划)

// 滚动(经 Series.rolling,返回 double[];DataFrame 级 resample 规划)
double[] ma7 = df.getSeries("price").rolling(7).mean();
double[] ema = df.getSeries("price").ewm(0.3).mean();
```

---

## 8. 关键算法伪代码(节选)

### 8.1 groupBy(单/多列)

```
// 伪代码:
//   1. 取 by 列的值数组
//   2. 遍历每行:groupKey = 拼接各 by 列值(多列用元组)
//      groups.computeIfAbsent(groupKey, k -> new IntArrayList()).add(rowIdx)
//   3. 对每个 agg 列、每个 aggFn:遍历 groups 取该列值,应用 agg,写入结果
//   4. as_index=false 时 groupKey 转普通列
//   5. 返回 GroupBy 对象(支持后续 transform/filter,保留分组)
```

### 8.2 merge(join)

```
// 伪代码:
//   1. 选较小侧作 buildSide
//   2. 遍历 buildSide 建 HashMap<key, List<rowIdx>>
//   3. 遍历 probeSide 每行查 hash:
//        INNER 仅匹配产出;LEFT probe 未匹配补 null;RIGHT/OUTER 末尾补 buildSide 未匹配
//   4. suffixes 处理重名列:重名列两边都加后缀(左 _x / 右 _y,对齐 pandas,
//      三条路径 long/double/generic 统一走 mergedNames())
```

> merge 重命名示例:`df1.merge(df2, "inner", "id")` 当两表都有非键列 `v` 时,
> 输出列为 `[id, v_x, v_y]`(与 pandas 完全一致),对照测试 `test_d63` 双锁定(列名 + 值)。
>
> 异名键(leftOn≠rightOn):右表键列**保留输出**
> (`merge(l, r, how, ["k1"], ["k2"], null)` 输出 `[k1, x_x, k2, x_y]`,对齐 pandas);
> outer/right 右表独有行的左键列为 null(pandas 不把右键回填进左键列;同名键仍回填)。
> 回归:`DataFrameMergeTest.异名键merge_右表键列保留_对齐pandas` + `test_d64`。

### 8.3 pivot_table

```
// 伪代码:
//   1. groupBy(index ∪ columns) → agg(value)
//   2. 把 columns 维的值散开成多列(unstack)
//   3. fill_value 填空,margins 加合计行/列
```

### 8.4 rolling(window).agg

```
// 伪代码:
//   for i in [window-1, n):
//       window_data = data.subList(i-window+1, i+1)
//       result[i] = agg(window_data)
//   min_periods 控制前几个是否算
//   time-based window:按时间差而非行数切窗
```

---

## 9. 边界与异常

| 场景 | 处理 |
|---|---|
| 列名重复 + allows_duplicate_labels=false | 抛 `IllegalArgumentException` |
| 列不存在 | `IllegalArgumentException`(消息带现有列提示;独立异常类 v2 规划) |
| 类型不匹配 | `IllegalStateException`(消息带期望/实际;独立异常类 v2 规划) |
| 按需加载缺失(df.sql 未引 jian-dsl 等) | `ModuleNotLoadedException`(带"请引 xxx jar"提示) |
| 行数不一致(二维数组构建) | 抛异常,不静默填 null |
| groupby key 含 null | 归到 `<NA>` 组 |
| merge key 两侧类型不同 | 向上转型对齐;不行抛异常 |
| MultiIndex 超 2 级 | 抛 `UnsupportedOperationException`(v1 限 2 级) |
| 时间序列操作但列非 DateTime | 抛异常并提示用 `to_datetime` |

---

## 10. 与其他模块的接口契约

### 10.1 给 io / viz / export
- `DataFrame.of(Schema, Object[][])` / `Column` 接口 / `Row.get/getDouble/getInt` / `iterableRows()` / `getColumn(name)`。

### 10.2 给 jian-num(SPI,可选)
- core 定义 `StatsProvider` 接口。
- jian-num 实现,通过 `ServiceLoader` 加载。
- 找不到则用内置简单实现(精度略低)。

---

## 11. 工作量与测试

- **代码量**(经源码核实):jian-core main 共 ~10,600 行(DataFrame 1800+ + Series 480 + GroupBy 340 + Window 310 + Resampler 280 + DatetimeIndex 200 + Frequency 180 + MultiIndex 170 + 9 种 Column + DataFrameSort/Missing/Reshape/Merge/Stats/Arith/Filter 伴生类 + DType/Schema/Stats SPI 等);测试 ~6,000 行。
- **测试规模**:jian-core 共 **545 测试全过**(口径见 [`api-counts.md`](api-counts.md))。覆盖:9 种 dtype 列、query(含 in/not in)、groupBy(含 NaN 分组语义,EdgeCaseTest 固化)、merge、pivotTable、melt、sortBy、缺失值、统计、eval、sql、astype 支持 8 种 dtype(仅 CATEGORY 抛异常)。
- **基准对照**:用 pandas 生成同输入的期望输出,作为 Java 实现的回归基准(浮点容差 1e-10)。pandas 对照差分见 `tests-pbt/properties/test_pandas_diff.py`(73 个对照算子 d1-d73)。

---

## 12. 验收标准

1. **§3 各小节标"已实现"的方法**全部可用,API 与 pandas 同义(Java 风格命名)。
2. groupBy 单/多列 + agg/transform/filter 可用;时间分组(resample)**已实现**(Resampler 17 方法,见 §6)。
3. merge 4 种 how + concat + pivotTable + melt + **stack/unstack 已实现**(见 §3.9)。
4. `.str` / `.dt`(只读属性)accessor 可用;`.cat` accessor **设计决策排除**(CATEGORY dtype 未实现完整语义)。
5. 时间序列 DataFrame 级 resample/shift/asfreq/tz_localize/tz_convert/atTime/betweenTime/asof **已实现**;Series 级 diff/shift/pctChange 已实现。
6. 缺失值按 §2.2 统一处理(DOUBLE 内部 NaN,IO 边界 null)。
7. **不引任何外部 jar**,仅 JDK 17 编译运行。
8. 与 pandas 同输入下,数值结果差异 < 1e-10(经 `test_pandas_diff.py` d1-d73 验证)。

---

## 13. 实现说明

> 本节是 M1+M2+v2 全部实现后的回填(groupby/merge/pivot/window/Series/Rolling/EWM/MultiIndex/transform/str/dt accessor 全部已落地)。

### 13.1 已实现的类与文件

| 文件 | 职责 | 行数(含注释) |
|---|---|---|
| `DType.java` | 9 种 dtype 枚举 + promote 提升规则 | ~85 |
| `Column.java` | 列抽象接口 | ~65 |
| `DoubleColumn/LongColumn/IntColumn.java` | 数值列(各自独立文件,整数独立保留精度) | ~150/170/150 |
| `StringColumn.java` | 字符串列 + 内置 .str 批量操作(upper/contains/length 等) | ~170 |
| `BoolColumn/DateTimeColumn/DateColumn/ObjectColumn/CategoryColumn.java` | 其余 5 种列 | ~各 90~150 |
| `Index.java` | 行标签(RangeIndex / 显式标签) | ~95 |
| `Schema.java` | 列名+类型 + 从数据自动推断 | ~205 |
| `DataFrame.java` | 主体:构造/属性/取列/select/drop/filter/head/tail/slice/query/loc/iloc/astype/统计/apply/assign/缺失值/toString | ~620 |
| `SimpleQueryParser.java` | df.query 的 L1 子集解析器(递归下降) | ~340 |
| `DataFrameStats.java` | 描述统计 companion(mean/std/min/max/median/percentile/describe/apply) | ~225 |
| `DataFrameMissing.java` | 缺失值 companion(isna/dropna/fillna/ffill/bfill) | ~205 |
| 测试套件(基础/查询/统计/合并/重塑/边界/窗口/accessor + 蜕变/差分/PBT 等专项) | 数量以 api-counts.md 为准 | ~6,000 |

**编译/测试状态**:`mvn -pl jian/jian-core test` 全过,BUILD SUCCESS(@Test 数以 [api-counts.md](api-counts.md) 为准)。

### 13.2 与需求的偏差(已实现部分)

| 需求写法 | 实际实现 | 原因 |
|---|---|---|
| `df.iloc[0,5]` Python 方括号(行+列) | `df.iloc(0, 5)` 只接行索引 varargs(不接列) | Java 不支持方括号传参;jian 的 iloc 只做行选择,列选择用 `select(cols)` |
| `df.loc("Alice", "Bob")` | 实现 `df.loc(Object...)` / `iloc(int...)` | Java 风格,且避免混 label/位置 |
| `df.str.upper()`(Series accessor) | `StringColumn.upper()` 直接方法 + `df.getStringColumn(name).upper()` | Java 无属性访问器,用方法链 |
| `.str/.dt/.cat` 三 accessor | M1 先实现 `.str`(StringColumn 内置);`.dt/.cat` 留 M2 | 字符串最高频,优先 |
| `df.query` 完整语法 | core 内置 L1 子集(比较/逻辑/between/like/in/is null);完整 SQL 走 jian-dsl(M6) | 规范 07 分工:core 兜底,dsl 增强 |
| 顶层入口 `Jian.readCsv` | 待 M3 io 模块实现 | core 自身不提供 IO |

### 13.3 已实现的 9 种 dtype 列(对齐规范 §2.1)

| DType | 内部存储 | 缺失值 | 对齐 pandas |
|---|---|---|---|
| INT | int[] + nullMask 位图 | nullMask[i]=true | Int32 |
| LONG | long[] + nullMask | nullMask | Int64(大 ID 不丢精度) |
| DOUBLE | double[] | NaN | float64 |
| BOOL | boolean[] + nullMask | nullMask | boolean |
| STRING | String[] | null | object(str) |
| DATETIME | LocalDateTime[] | null | datetime64 |
| DATE | LocalDate[] | null | datetime64[日期] |
| CATEGORY | int[](码) + 值表 | -1 码 | category |
| OBJECT | Object[] | null | object(二进制/嵌套) |

### 13.4 实现状态(全部已落地)

- **已实现**:GroupBy(agg/filter/size/**transform**)、merge(inner/left/right/outer + 多列键)、concat(纵/横)、pivot_table、melt、transpose、sort_values/sort_index、nlargest/nsmallest、drop_duplicates、列级算术(add/sub/mul/div + 标量)。
- **已实现**:Series(独立类,含统计/排序/窗口/.str/.dt accessor/shift/diff/pctChange)、Rolling/Expanding/EWM/Window、MultiIndex(2 级)。
- **已实现**:`.str()` accessor(委托 StringColumn)、`.dt()` accessor(year/month/day/hour/minute/second/dayOfWeek/dayOfYear)。
- **顶层入口**:`Jian.readCsv/readExcel/...` 在 jian-facade 模块(聚合全部 io)。

---

*本分册独立,与 02-06 无耦合。core 单独打包可跑(纯内存变换)。覆盖 pandas DataFrame/Series/GroupBy/Window 全套数据操作。*
### 13.5 查询解析与 SPI 能力

- **`in` / `not in` 谓词**:`df.query("city in ('SH', 'BJ')")`;数值跨类型相等(Long 30 == Double 30.0)。
- **LIKE 字面量匹配**:`like` 模式除 `%` `_` 外全部按字面量匹配(防正则注入)。
- **`ModuleNotLoadedException` 新增**(`jian.core`):按需加载缺失时抛带安装提示的友好异常(规范 §9),`df.sql()` 在未引 jian-dsl 时即抛此类。
- **`df.eval()` / `df.sql()` 新增**(规范 07 §2.2):经 `DslEngine` SPI 路由,引 jian-dsl 后自动升级。
- **StatsProvider SPI 接线**:`DataFrameStats.percentile/describe` 经 `StatsProvider.current()` 计算,引 jian-num-bridge 自动升级为 Commons Math 实现。

---

### 13.6 record 桥与列选择器(借鉴 Kotlin DataFrame)

> 背景:对比分析 JetBrains Kotlin DataFrame(类型安全 DSL)后移植其三点优点(编译器插件级类型追踪/层级列两项**不移植**,理由:Java 无对应机制且 jian 的字符串表达式/SQL 路线对 AI 更友好;嵌套列模型复杂度与 pandas 定位不符)。

| 新 API | 对应 KDF 概念 | 语义要点 |
|---|---|---|
| `df.toRecords(Class<T>)` | `convertTo<T>()` / @DataSchema | record 组件名 ↔ 列名精确匹配;df 多余列忽略(投影),组件缺列报错;类型跨族不隐式转换(先 astype);缺失值语义对齐 §3.5(非 DOUBLE 缺失→null 需包装类型组件,DOUBLE 缺失→NaN 不失真) |
| `DataFrame.fromRecords(List)` | data class → DataFrame | 组件声明类型**精确**定 DType(不推断):String→STRING/int→INT/long→LONG/double→DOUBLE/boolean→BOOL/LocalDate→DATE/LocalDateTime→DATETIME/其它→OBJECT;列表须非空且元素同型 |
| `df.selectBy(Predicate<String>)` | 列选择器 cols(startsWith(..)) | 谓词作用于列名,命中列保持原列序;无命中返回 0 列表(与 `select()` 空参一致) |
| `Jian.generateColumnsSource(df, className)`(facade)| schema 常量化 | 生成列名常量类源码(仅返回不落盘),业务代码引用常量防拼写错;非法标识符列名以注释说明 |

- 实现:`jian.core.RecordBridge`(单一职责,零依赖纯反射);测试 `RecordBridgeTest` 9 例(回环蜕变 df→records→df 形状值不变 + 异常路径)+ `JianTest.generateColumnsSource生成常量类`。

### 13.7 共享工具内聚

> 同一语义保持单一定义(否则 core 兜底与 dsl 完整引擎会漂移),共享工具集中如下:

| 共享工具 | 覆盖语义 | 位置 |
|---|---|---|
| likeToRegex(LIKE 模式 → 正则,单一定义) | SimpleQueryParser 与 PrattEngine 共用 | `jian.core.LikePattern.toRegex()` |
| valueEquals / isCmpOp | 同上两处共用 | `DataFrameCompare` |
| isIntegralNumber | DataFrameCompare 与 PrattEngine 共用 | `DataFrameCompare` |
| toLongArr / toLongArray | GroupBy 与 DataFrameMerge 共用 | `DataFrameTypes.columnToLongArray()` |

- 有意不动:jian-dsl 内 7 处引号感知扫描(括号深度/词边界/`''`转义组合各异,参数化风险大于收益);core 兜底与 dsl 完整引擎双实现(SPI 设计,差分测试覆盖)。

---

*实现完成;当前测试与 API 数字以 [api-counts.md](api-counts.md) 为准。*

---

### 13.8 对齐 pandas 的算子语义细节(现行)

- `setIndex` 多列时构建 MultiIndex;`pivotTable` 对缺失键行按 pandas `dropna` 语义丢弃。
- merge `right` 按右表行序、`outer` 按键首现序输出;输出列保留源 dtype(0 行/全 null 不降级)。
- `spearman` 并列取平均秩;`interpolate` 对无缺失的整型列直通(不降级);`sortBy` 混型键抛 IAE(doc/00 §10.16 第 4 条五入口,对齐 pandas `sort_values` 抛 TypeError);`ohlc` 跳过桶首缺失;`resample("1ME")` 跨短月正确分桶。
- 测试:jian-core @Test **571**(口径见 [api-counts.md](api-counts.md))。

### 13.9 外部 AI 协作复审修复

> 由 AI1 依 ai-code-testing 方法学复审发现(以构造/转换路径的契约一致性问题为主),
> 逐条对源码与 pandas 实测复核修正后修复。配套:Java 回归 `AuditRegressionTest`(26)+ pandas 对照 `d74~d79`(6)。

| # | 修复 | 行为变化 |
|---|---|---|
| 1 | `DType.promote` 数值分支收紧为"两侧均属数值族(INT/LONG/DOUBLE/BOOL)" | BOOL+DATE/DATETIME/CATEGORY 由"误返 INT"改为抛 IAE(与 cmp 混型口径一致) |
| 2 | `DataFrameCompare.cmp/valueEquals` 精确混型比较 | 整数×浮点走 `compareLongVsDouble`(2^53 边界不折叠);BigDecimal/BigInteger 统一 compareTo;**[有意差异 §10.16#17]** jian 精确 vs NumPy 标量折叠 |
| 3 | `DataFrameReshape` 判重键统一 `normUniqueKey` | dropDuplicates/duplicated/pivotTable/pivot 把 ±0.0 归一(NaN 经 Double.equals 本就判重);对齐 pandas |
| 4 | `pivotTable` 输出 dtype 按 aggFn 分派(照抄 GroupBy.agg) | count/nunique→LONG;first/last→源 dtype;sum→BOOL 计数 LONG/字符串拼接 STRING/整数 long 累计 LONG/浮点 DOUBLE |
| 5 | `mergeAsof` 缺失键 fail-fast | 左右任一侧 on 键含 null/NaN(isNull 权威判定)抛 IAE —— 对齐本机 pandas 1.5.3 实测 ValueError;同时修复右表过滤 `get()!=null` 漏 NaN 的连带问题 |
| 6 | 整数列求和 long 累计 | `DataFrameStats.sum`/`Reshape.aggregate`/`Resampler` 对 INT/LONG/BOOL 走 long 精确累计(对齐 pandas int64 sum;API 仍返回 double) |
| 7 | 超长整数字面量 fail-fast(双引擎) | 纯整数(无 `.`/`e/E`)超 Long 范围抛 IAE;科学计数法照常按 double 近似;**[有意差异 §10.16#18]** |
| 8 | `Resampler` 聚合 dtype 对齐 GroupBy | count→LONG;sum 整数族(含 BOOL)→LONG;空桶缺失语义(§10.16#14)不变;first/last BOOL→DOUBLE 为声明差异(§10.16#19) |
| 9 | BOOL 构造/转换契约统一为 `DataFrameConvert.toBoolValue` | `DataFrame.of` 显式 BOOL schema 接受 Boolean/任意 Number(非零即 true,对齐 pandas)/"true"/"1"(trim+不区分大小写);修复 Number 元素 CCE 与 parseBoolean 不认 "1" 的双分裂 |
| 10 | `compareLongVsDouble` javadoc 边界说明 | Long.MAX 与其 double 投影(2^63)严格不等是 IEEE 754 固有限制,文档化并指引用 BigInteger/BigDecimal;无代码行为变化 |
| 11 | DATETIME/DATE 构造与转换统一(`toDateTimeValue`/`toDateValue`) | `DataFrame.of` 显式时间 schema 接受跨类型元素:LocalDate→DATETIME 走 atStartOfDay、LocalDateTime→DATE 走 toLocalDate(原先强转 (String) 抛 CCE);与 astype 完全同口径 |
| 12 | 构造数值列非法字符串报错统一(`toNumberChecked`) | INT/LONG/DOUBLE 构造对非法字符串抛带列名/行号/值的 IAE(原裸 NumberFormatException,与 astype 的教学型报错分裂) |
| 13 | `astype INT` 对 BigInteger/BigDecimal 超 long 域 fail-fast | 超域抛 IAE(原裸 `intValue()` 静默回绕致数据损坏);long 域内超 int 的回绕**对齐 pandas/numpy 静默截断**(实测 5e9→705032704),不制造新差异 |
