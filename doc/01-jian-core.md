# 01 · jian-core 需求说明书

> 版本:v0.3(大面对齐 pandas)· 日期:2026-08-01 · 作者:zc · 依赖:JDK 17(零外部 jar)

---

## 1. 模块定位

### 1.1 一句话定位

jian-core **大面对齐 pandas 3.x 的 DataFrame/Series/GroupBy/窗口/Resampler 全套数据操作能力**——**15 大类、200+ 方法**。是 jian 所有子模块(io / viz / export)的基石,**零外部依赖**(仅 JDK 17)。

### 1.2 范围说明

本分册覆盖 pandas DataFrame 的**全部数据操作方法**(不含 IO/绘图/样式,那些在 02-04)。为可读性拆成 4 个内部分包:

| 内部分包 | 对应 pandas 内容 |
|---|---|
| `core.frame` | DataFrame 主体 + 15 大类方法 |
| `core.series` | Series(一维)及其专属方法(str/cat/dt accessor) |
| `core.groupby` | GroupBy 对象 + agg/transform/filter |
| `core.window` | Rolling / Expanding / EWM / Resampler |

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

### 3.1 属性与底层数据(13)

`index` / `columns` / `dtypes` / `info()` / `select_dtypes(include,exclude)` / `values`(转二维 Object 数组)/ `axes` / `ndim` / `size` / `shape` / `memory_usage()` / `empty` / `attrs`。

### 3.2 类型转换(5)

`astype(dtype)` / `convert_dtypes()`(转 nullable)/ `infer_objects()` / `copy(deep=true)` / `to_numpy(dtype)`。

### 3.3 索引与迭代(20)

- 标量/区域:`at[row,col]` / `iat[i,j]` / `loc[...]`(标签)/ `iloc[...]`(位置)/ `xs(key,axis,level)`。
- 增删:`insert(loc,col,value)` / `pop(col)` / `get(key,default)` / `isetitem(loc,value)`。
- 迭代:`iterrows()` / `itertuples()` / `items()`(列迭代)/ `keys()` / `__iter__`(列名迭代)。
- 头尾:`head(n)` / `tail(n)`。
- 条件:`isin(values)` / `where(cond,other)` / `mask(cond,other)`(where 取反)/ `query("expr")`(解析简单表达式)。
- 重命名前缀:`add_prefix("p_")` / `add_suffix("_s")`。

### 3.4 二元运算(28)

- 算术:`add/sub/mul/div/truediv/floordiv/mod/pow` 及其反向 `radd/rsub/.../rpow`。
- 矩阵:`dot(other)`。
- 比较:`lt/gt/le/ge/ne/eq`。
- 合并:`combine(other,func)` / `combine_first(other)`(用 other 填 null)。
- 全部支持 `fill_value`(NaN 填充)与 `axis` 参数。

### 3.5 函数应用、GroupBy、窗口(11)

- `apply(func,axis=0/1)` —— 按行/按列应用。
- `map(func)` —— 逐单元格应用(pandas 2.x 新)。
- `applymap(func)` —— 旧逐单元格(兼容)。
- `pipe(func)` —— 链式管道(`df.pipe(f1).pipe(f2)`)。
- `agg(funcs)` / `aggregate(funcs)` —— 多聚合。
- `transform(func)` —— 聚合后广播回原形状。
- `groupby(by,as_index,sort,group_keys)` —— 返回 GroupBy 对象(见 §5)。
- `rolling(window)` / `expanding(min_periods)` / `ewm(com/span/halflife,alpha)` —— 返回窗口对象(见 §6)。

### 3.6 计算 / 描述统计(35)

- 基础:`sum/prod/product/mean/median/min/max` / `abs` / `count`(非空)/ `nunique` / `mode`。
- 离散:`std/var/sem`(标准误)/ `skew` / `kurt/kurtosis`。
- 分位:`quantile(q)` / `rank(method,ascending)`。
- 累积:`cumsum/cumprod/cummax/cummin`。
- 差分:`diff(periods)` / `pct_change(periods)`。
- 相关:`corr(method)` / `corrwith(other)` / `cov`。
- 描述:`describe(percentiles,include,exclude)`。
- 裁剪:`clip(lower,upper)` / `round(decimals)`。
- 其他:`all/any` / `eval("expr")` / `value_counts(subset,normalize)`。

### 3.7 重索引 / 选择 / 标签操作(20)

- 删:`drop(labels,axis)` / `drop_duplicates(subset,keep)` / `duplicated(subset,keep)`。
- 极值位置:`idxmax/idxmin`。
- 重塑标签:`reindex(labels)` / `reindex_like(other)` / `rename(mapper)` / `rename_axis(name)` / `reset_index(drop)` / `set_index(cols)` / `set_axis(labels,axis)` / `take(indices)` / `truncate(before,after)`。
- 对齐:`align(other,join)`。
- 过滤:`filter(items,like,regex)` / `equals(other)`。
- 采样:`sample(n/frac,replace,weights,random_state)` / `first(offset)` / `last(offset)`。

### 3.8 缺失值处理(11)

`isna/isnull` / `notna/notnull` / `dropna(axis,how,thresh,subset)` / `fillna(value/method/limit)` / `ffill()` / `bfill/backfill()` / `interpolate(method=linear/time/.../)` / `replace(to_replace,value,regex)` / `pad`(ffill 别名)。

### 3.9 重塑、排序、转置(17)

- 排序:`sort_values(by,ascending,na_position,inplace,kind)` / `sort_index(axis,ascending)`。
- TopN:`nlargest(n,cols)` / `nsmallest(n,cols)`。
- 透视:`pivot(index,columns,values)` / `pivot_table(values,index,columns,aggfunc,fill_value,margins)`。
- 长宽转换:`melt(id_vars,value_vars)` / `stack(level)` / `unstack(level)` / `explode(col)`(list 列展平)。
- 索引层级:`droplevel(level)` / `swaplevel(i,j)` / `reorder_levels(order)`。
- 形状:`T` / `transpose()` / `squeeze(axis)`。

### 3.10 合并 / 连接(5)

- `merge(right,how,on,left_on,right_on,suffixes,indicator)` —— 关系 join。
- `join(other,on,how,lsuffix,rsuffix)` —— 索引 join。
- `assign(**kwargs)` —— 链式新增列(`df.assign(x=...,y=...)`)。
- `compare(other,align_axis)` —— 差异对比(返回差异 DataFrame)。
- `update(other)` —— 原地用 other 覆盖。
- 顶层 `concat(objs,axis,join,ignore_index)` + `merge_ordered` + `merge_asof`(按最近键对齐)。

### 3.11 时间序列(12)

`shift(periods,freq)` / `tshift` / `asfreq(freq)` / `asof(label)` / `resample(rule)`(返回 Resampler,见 §6)/ `to_period(freq)` / `to_timestamp(freq)` / `tz_convert(tz)` / `tz_localize(tz)` / `at_time(time)` / `between_time(start,end)` / `first_valid_index()` / `last_valid_index()`。

### 3.12 标志与元数据(2)

`attrs`(字典)/ `set_flags(...)`(allows_duplicate_labels)。

### 3.13 字符串处理(通过 `.str` accessor,Series 上)

见 §4 Series 的 `.str`。

### 3.14 绘图(委托 viz 模块)

`plot` / `boxplot` / `hist` —— core 不实现,委托 `jian-viz`(未引则 `ModuleNotLoadedException`)。

### 3.15 IO(委托 io 模块)

所有 `to_xxx` / `read_xxx` 委托 io 子模块。

---

## 4. Series 与 accessor

### 4.1 Series 方法(对齐 pandas Series)

DataFrame 单列即 Series。方法分同类(属性/转换/索引/二元/应用/统计/缺失/重塑/时间序列/绘图),约 100+ 个,这里仅列**与 DataFrame 不同或专属**的部分:

- 构造:`Series.of(data, index, name, dtype)`。
- 属性:`shape`(1D)/ `name` / `dtype` / `hasnans` / `is_monotonic_increasing` / `is_unique`。
- 转换:`tolist()` / `to_dict()` / `to_frame()` / `to_numpy()`。
- 重塑:`unstack()` → DataFrame / `squeeze()` → 标量。
- 统计:`argmax/argmin` / `nsmallest/nlargest` / `between(left,right)` / `case_when(...)`。

### 4.2 `.str` 字符串 accessor(对齐 pandas.Series.str)

`lower/upper/title/strip/lstrip/rstrip` / `len` / `contains(pat,regex)` / `startswith/endswith` / `replace(pat,repl,regex)` / `split(pat,expand)` / `cat(sep)` / `get(i)` / `extract(pat)` / `findall(pat)` / `contains` / `match` / `pad(width,side)` / `zfill(width)` / `repeat(n)` / `slice(start,stop)` 等。

### 4.3 `.dt` 时间 accessor

`year/month/day/hour/minute/second` / `date` / `time` / `dayofweek/dayofyear/weekofyear/quarter` / `is_month_end/is_month_start` / `tz_localize/tz_convert` / `normalize()` / `to_period(freq)` 等。

### 4.4 `.cat` 分类 accessor

`categories` / `ordered` / `codes` / `add_categories/remove_categories/rename_categories/inorder/as_ordered/as_unordered` / `set_categories`。

### 4.5 Index 对象

`Index` / `RangeIndex`(Range 步长)/ `DatetimeIndex` / `MultiIndex`(v1 限 2 级)。方法:`isin` / `get_loc` / `get_indexer` / `unique` / `drop_duplicates` / `union/intersection/difference` / `to_list` / `astype` 等。

---

## 5. GroupBy 对象(对齐 pandas.core.groupby)

`df.groupBy("dept","level")` 返回 `GroupBy`,方法:

- 聚合:`agg/aggregate(funcs)` / 各统计的快捷方法(`sum/mean/count/size/min/max/median/std/var/first/last/nth/sem/ohlc/prod`)。
- 变换:`transform(func)` / `ffill/bfill` / `cumcount/cumsum/cummax/cummin/cumprod` / `shift(periods)` / `pct_change` / `rank` / `diff`。
- 过滤:`filter(func)` —— 组级谓词。
- 应用:`apply(func)`。
- 遍历:`__iter__`(返回 (key, sub_df))。
- 时间分组:`resample(rule)`(Resampler 子类)/ `rolling(window)`。
- 高级:`ngroup` / `cumcount` / `tail/head(n)` / `nunique` / `value_counts` / `corr/cov` / `describe` / `boxplot`(委托 viz)。

---

## 6. 窗口与重采样(对齐 pandas 窗口族)

### 6.1 Rolling(`df.rolling(window)`)
`sum/mean/median/min/max/std/var/count/apply(func)/agg/quantile/corr/cov/kurt/skew/sem/rank/ewm`。

### 6.2 Expanding(`df.expanding(min_periods)`)
同 Rolling 的方法集,累积式窗口。

### 6.3 EWM(`df.ewm(com/span/halflife,alpha)`)
指数加权:`mean/var/std/corr/cov`。

### 6.4 Resampler(`df.resample(rule)`)
`sum/mean/median/min/max/count/ohlc/agg/apply/transform/interpolate/ffill/bfill/asfreq/pipe/sem/std/var/quantile` + `upsampling` 填充策略。

---

## 7. 核心 API 风格示例

```java
// 链式(不可变优先)
DataFrame result = df
    .drop("unused")
    .query("age > 18 && score < 100")
    .assign(Map.of("grade", r -> grade(r.getDouble("score"))))
    .sortValues(List.of("dept","score"), List.of(ASC, DESC))
    .groupby("dept")
    .agg(Map.of("salary", Agg.MEAN, "name", Agg.COUNT))
    .resetIndex()
    .head(10);

// 索引
df.loc("Alice", "Bob");
df.iloc(0, 5);
df.query("salary > 10000");

// 字符串列
df.getColumn("name").str.upper().str.contains("A.*");
df.getColumn("ts").dt.month();
df.getColumn("city").cat.categories();

// 滚动
df.getColumn("price").rolling(7).mean();
df.resample("1D").sum();
df.ewm(0.3).mean();
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
//   4. suffixes 处理重名列
```

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
| 按需加载缺失(df.sql 未引 jian-dsl 等) | `ModuleNotLoadedException`(带"请引 xxx jar"提示,已实现 2026-08-02) |
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

- **代码量**:自写约 14,000 行(DataFrame 主体 5000 + Series+accessor 3000 + GroupBy 2500 + 窗口/Resampler 2000 + Index 1000 + 共享工具 500),测试 8,000 行。**合计 ~22,000 行**。
- **测试覆盖率**:核心算子行覆盖 ≥ 80%;groupby/merge/pivot/rolling/resample 必须有充足用例。
- **基准对照**:用 pandas 生成同输入的期望输出,作为 Java 实现的回归基准(浮点容差 1e-10)。

---

## 12. 验收标准

1. **15 大类**全部方法可用,与 pandas 同名同参(Java 风格命名)。
2. groupby 单/多列 + agg/transform/filter + 时间分组(resample/rolling)可用。
3. merge 4 种 how + concat + pivot_table + melt/stack/unstack 可用。
4. `.str` / `.dt` / `.cat` 三个 accessor 全套可用。
5. 时间序列 shift/resample/asfreq/tz_* 可用。
6. 缺失值按 §2.2 统一处理。
7. **不引任何外部 jar**,仅 JDK 17 编译运行。
8. 与 pandas 同输入下,数值结果差异 < 1e-10。

---

## 13. 实现说明(M1 基础已实现,2026-08-01)

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
| 测试 9 个测试类(DataFrameBasic/Query/Stats/Merge/Reshape/Advanced/EdgeCase/SeriesWindow/TransformAccessor) | 107 用例 | ~2,100 |

**编译/测试状态**:`mvn -pl jian/jian-core test` → 107/107 全过,BUILD SUCCESS。

### 13.2 与需求的偏差(已实现部分)

| 需求写法 | 实际实现 | 原因 |
|---|---|---|
| `df.iloc[0,5]` Python 方括号 | `df.iloc(0, 5)` Java 方法 | Java 不支持方括号传参 |
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
### 13.5 2026-08-02 全项目审查修复

- **`in` / `not in` 谓词补全**:`df.query("city in ('SH', 'BJ')")`(core 兜底解析器此前 javadoc 声称支持但实际未实现,会误报"列 in 不存在");数值跨类型相等(Long 30 == Double 30.0)。
- **LIKE 正则注入修复**:`like` 模式除 `%` `_` 外全部按字面量匹配(防正则注入)。
- **`ModuleNotLoadedException` 新增**(`jian.core`):按需加载缺失时抛带安装提示的友好异常(规范 §9),`df.sql()` 在未引 jian-dsl 时即抛此类。
- **`df.eval()` / `df.sql()` 新增**(规范 07 §2.2):经 `DslEngine` SPI 路由,引 jian-dsl 后自动升级。
- **StatsProvider SPI 接线**:`DataFrameStats.percentile/describe` 经 `StatsProvider.current()` 计算,引 jian-num-bridge 自动升级为 Commons Math 实现(此前 SPI 无生产消费方,处于休眠状态)。

---

*M1 基础 + M2 高级(groupby/merge/pivot/reshape/sort)实现完成于 2026-08-01;2026-08-02 全项目审查后 107 测试全过。*
