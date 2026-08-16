package jian.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;

// ┌─ What : DataFrameStats —— DataFrame 的描述统计与函数应用(对齐 pandas §3.5/3.6)
// │  Why  : 规范要求 sum/mean/std/median/min/max/count/describe/apply/agg;DataFrame 主类已长,放 companion
// │  Who  : DataFrame 的 sum/mean/apply 等方法委托此类
// │  When : 数据描述、聚合、逐元素/逐列变换
// │  Where: jian-core/DataFrameStats.java
// │  How  : 数据走向:DataFrame → 取数值列(或指定列)→ 内部直接遍历原生数组 / 调 jian-num SPI → 结果。
// │         关键变量变化:
// │           - 数值列直接用 Column.getDouble 遍历(skip NaN);
// │           - apply 对每行/每列调用户函数,返回新 Column。
// │         逻辑路线:
// │           路径 A(单列数值统计)→ 遍历该列,skip 缺失,算 mean/sum/...;
// │           路径 B(全数值列统计)→ 对每列重复 A,返回 Map<列名, 值>;
// │           路径 C(非数值列调统计)→ 抛 IllegalArgumentException。
/**
 * DataFrame 描述统计与函数应用(对齐 pandas DataFrame 的统计方法)。
 *
 * <p>本类不依赖 jian-num;若需要更精确的分位数/线代,经 SPI 可选加载 jian-num(规范 §4.1)。
 *
 * @see DataFrame#mean()
 * @see DataFrame#sum()
 */
public final class DataFrameStats {

    private DataFrameStats() {}

    // ======================== 单列数值统计 ========================

    /**
     * 求和(数值列,skip NaN)。
     * <p>整数族(INT/LONG/BOOL)走 <b>long 精确累计</b>(对齐 pandas int64 sum,返回 double 签名不变);
     * DOUBLE 列采用 Neumaier 改进型(经典 Kahan 升级)—— 误差项独立累加,最后 {@code sum+comp} 修正。
     * 实测对 [1e16,1,2,-1e16]:经典 Kahan 得 4.0(y=x-comp 预取整放大误差),Neumaier 得精确 3.0;复杂度同为 O(n)。
     * @param c Column 待求和列,数值类型(INT/LONG/DOUBLE/BOOL);非 null
     * @return double 非空值之和;全空返回 0.0
     */
    public static double sum(Column c) {
        // 整数族 long 精确累计(修复:原先进 getDouble 进 Neumaier,单值 < 2^53 但
        // 总和 > 2^53 时静默丢精度;long 域内精确,超出后取最近 double)
        DType dt = c.dtype();
        if (dt == DType.INT || dt == DType.LONG || dt == DType.BOOL) {
            long s = 0;
            for (int i = 0; i < c.size(); i++) {
                if (c.isNull(i)) continue;
                s += (dt == DType.BOOL) ? (((Boolean) c.get(i)) ? 1 : 0) : c.getLong(i);
            }
            return (double) s;
        }
        // 伪代码(Neumaier 补偿求和):
        //   1. 对每个非空 x:t = sum + x
        //   2. |sum| >= |x| 时 comp += (sum - t) + x;否则 comp += (x - t) + sum(低阶位进补偿项)
        //   3. 返回 sum + comp(把累起来的舍入误差一次性补回)
        double sum = 0.0, comp = 0.0;
        int n = c.size();
        for (int i = 0; i < n; i++) {
            if (c.isNull(i)) continue;
            double x = c.getDouble(i);
            double t = sum + x;
            comp += (Math.abs(sum) >= Math.abs(x)) ? (sum - t) + x : (x - t) + sum;
            sum = t;
        }
        // 因为 sum 溢出为 ±Infinity 时,补偿项 comp 会取到反向 Infinity,
        // 直接 sum+comp 得 NaN 污染下游,所以此时放弃补偿、保留 ±Infinity
        //(对齐 pandas sum 溢出 → inf;实测 [MAX,MAX] 得 Infinity 而非 NaN)。
        return Double.isInfinite(sum) ? sum : sum + comp;
    }

    /**
     * 均值(数值列,skip NaN,Neumaier 补偿求和,同 {@link #sum} 的说明)。
     * @param c Column 待求均值列,数值类型;非 null
     * @return double 非空值的算术平均;**全空返回 NaN**
     */
    public static double mean(Column c) {
        double sum = 0.0, comp = 0.0; int cnt = 0;
        int n = c.size();
        for (int i = 0; i < n; i++) {
            if (!c.isNull(i)) {
                double x = c.getDouble(i);
                double t = sum + x;
                comp += (Math.abs(sum) >= Math.abs(x)) ? (sum - t) + x : (x - t) + sum;
                sum = t;
                cnt++;
            }
        }
        if (cnt == 0) return Double.NaN;
        // 溢出同 sum —— 放弃补偿保留 ±Infinity(mean=±Inf/cnt 仍为 ±Inf,对齐 pandas)
        return (Double.isInfinite(sum) ? sum : sum + comp) / cnt;
    }

    /**
     * 样本标准差 ddof=1(对齐 pandas Series.std 默认)。
     * @param c Column 数值列;非 null
     * @return double 样本标准差;非空值 ≤ 1 时返回 NaN
     */
    public static double std(Column c) {
        return std(c, 1);
    }

    /**
     * 标准差,可指定 ddof。
     * @param c    Column 数值列;非 null
     * @param ddof int 自由度修正,0=总体标准差,1=样本标准差(默认)
     * @return double 标准差;cnt-ddof ≤ 0 返回 NaN
     */
    public static double std(Column c, int ddof) {
        double m = mean(c);
        int n = c.size();
        int cnt = 0;
        double s = 0;
        for (int i = 0; i < n; i++) {
            if (!c.isNull(i)) {
                double d = c.getDouble(i) - m;
                s += d * d;
                cnt++;
            }
        }
        if (cnt - ddof <= 0) return Double.NaN;
        return Math.sqrt(s / (cnt - ddof));
    }

    /**
     * 最小值(skip NaN)。
     * @param c Column 数值列;非 null
     * @return double 非空值最小;**全空返回 NaN**
     */
    public static double min(Column c) {
        double m = Double.POSITIVE_INFINITY;
        int n = c.size();
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (!c.isNull(i)) { any = true; if (c.getDouble(i) < m) m = c.getDouble(i); }
        }
        return any ? m : Double.NaN;
    }

    /**
     * 最大值(skip NaN)。
     * @param c Column 数值列;非 null
     * @return double 非空值最大;全空返回 NaN
     */
    public static double max(Column c) {
        double m = Double.NEGATIVE_INFINITY;
        int n = c.size();
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (!c.isNull(i)) { any = true; if (c.getDouble(i) > m) m = c.getDouble(i); }
        }
        return any ? m : Double.NaN;
    }

    /**
     * 中位数(skip NaN)。
     * @param c Column 数值列;非 null
     * @return double 非空值中位数(偶数个取中间两数平均);全空返回 NaN
     */
    public static double median(Column c) {
        List<Double> vals = new ArrayList<>();
        int n = c.size();
        for (int i = 0; i < n; i++) if (!c.isNull(i)) vals.add(c.getDouble(i));
        if (vals.isEmpty()) return Double.NaN;
        vals.sort(Double::compare);
        int sz = vals.size();
        return sz % 2 == 0 ? (vals.get(sz / 2 - 1) + vals.get(sz / 2)) / 2 : vals.get(sz / 2);
    }

    /**
     * 非空计数。
     * @param c Column 任意列;非 null
     * @return int 非空元素个数 ∈ [0, c.size()]
     */
    public static int count(Column c) {
        int cnt = 0;
        int n = c.size();
        for (int i = 0; i < n; i++) if (!c.isNull(i)) cnt++;
        return cnt;
    }

    // ======================== 全 DataFrame 统计(对齐 pandas df.sum() / df.mean())========================

    /**
     * 对所有数值列做统计,返回 Map&lt;列名, 值&gt;(跳过非数值列)。
     * @param df DataFrame 目标表,非 null
     * @param op String 统计类型:"sum"/"mean"/"std"/"min"/"max"/"median";非 null
     * @return Map&lt;String,Double&gt; 数值列名 → 该列 op 统计值;非数值列被跳过
     * @throws IllegalArgumentException op 不在支持范围
     */
    public static Map<String, Double> numericStat(DataFrame df, String op) {
        Map<String, Double> r = new HashMap<>();
        for (Column c : df.columnsInternal()) {
            if (c.dtype().isNumeric()) {
                r.put(c.name(), switch (op) {
                    case "sum" -> sum(c);
                    case "mean" -> mean(c);
                    case "std" -> std(c);
                    case "min" -> min(c);
                    case "max" -> max(c);
                    case "median" -> median(c);
                    default -> throw new IllegalArgumentException("未知统计 op: " + op);
                });
            }
        }
        return r;
    }

    /**
     * describe(对齐 pandas df.describe()):返回 DataFrame,行=统计量,列=数值列。
     * @param df DataFrame 目标表,非 null
     * @return DataFrame 8 行(count/mean/std/min/25%/50%/75%/max)× (1 + 数值列数);
     *         无数值列时返回单行提示 "no numeric columns"
     */
    public static DataFrame describe(DataFrame df) {
        List<String> numCols = new ArrayList<>();
        for (Column c : df.columnsInternal()) if (c.dtype().isNumeric()) numCols.add(c.name());
        if (numCols.isEmpty()) {
            return DataFrame.of(Schema.of("stat", DType.STRING), new Object[][]{{"no numeric columns"}});
        }
        // 8 个统计量:count/mean/std/min/25%/50%/75%/max
        Object[][] rows = new Object[8][numCols.size() + 1];
        String[] stats = {"count", "mean", "std", "min", "25%", "50%", "75%", "max"};
        for (int r = 0; r < 8; r++) {
            rows[r][0] = stats[r];
            for (int c = 0; c < numCols.size(); c++) {
                Column col = df.getColumn(numCols.get(c));
                rows[r][c + 1] = switch (r) {
                    case 0 -> (double) count(col);
                    case 1 -> mean(col);
                    case 2 -> std(col);
                    case 3 -> min(col);
                    case 4 -> percentile(col, 0.25);
                    case 5 -> median(col);
                    case 6 -> percentile(col, 0.75);
                    case 7 -> max(col);
                    default -> Double.NaN;
                };
            }
        }
        // 构造 Schema
        Object[] nameType = new Object[(numCols.size() + 1) * 2];
        nameType[0] = "stat"; nameType[1] = DType.STRING;
        for (int c = 0; c < numCols.size(); c++) {
            nameType[(c + 1) * 2] = numCols.get(c);
            nameType[(c + 1) * 2 + 1] = DType.DOUBLE;
        }
        return DataFrame.of(Schema.of(nameType), rows);
    }

    /**
     * 分位数(R-7 linear,对齐 numpy 默认)。
     *
     * <p>经 {@link StatsProvider} SPI 计算:未引 jian-num-bridge 时用 core 内置 R-7 兜底;
     * 引了 bridge 则升级为 Commons Math 精确实现(规范 01 §10.2 / 06 §1.4)。
     * 数据走向:Column → 去 null 得 double[] → StatsProvider.current().percentile(data, q) → 结果。
     * @param c Column 数值列;非 null
     * @param q double 分位点,范围 [0.0, 1.0];NaN 拒绝
     * @return double 分位数值;全空返回 NaN
     * @throws IllegalArgumentException q 不在 [0,1] 或 q 为 NaN
     */
    public static double percentile(Column c, double q) {
        // 校验 q 范围与 NaN(与 quantile 口径统一):
        // 因为 NaN 与任何数比较恒 false,范围校验对 NaN 会静默放行,所以显式拒绝
        if (Double.isNaN(q) || q < 0 || q > 1) {
            throw new IllegalArgumentException("percentile q 范围 [0,1],实际:" + q);
        }
        int n = c.size();
        double[] vals = new double[n];
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (!c.isNull(i)) vals[cnt++] = c.getDouble(i);
        }
        if (cnt == 0) return Double.NaN;
        if (cnt < vals.length) vals = java.util.Arrays.copyOf(vals, cnt);
        return StatsProvider.current().percentile(vals, q);
    }

    // ======================== 函数应用(对齐 pandas apply / map)========================

    /**
     * 对某列的每个数值元素应用函数,返回新列(对齐 pandas Series.apply)。
     * @param df      DataFrame 目标表,非 null
     * @param colName String 数值列名,必须存在且数值类型;非 null
     * @param fn      java.util.function.DoubleUnaryOperator 一元函数(如 x -> x * 2);非 null
     * @return DoubleColumn 同长度新列;缺失行结果为 NaN
     * @throws IllegalArgumentException colName 不存在,或列非数值类型
     */
    public static DoubleColumn applyNumeric(DataFrame df, String colName, DoubleUnaryOperator fn) {
        java.util.Objects.requireNonNull(colName, "colName 不能为 null");
        Column c = df.getColumn(colName);
        if (!c.dtype().isNumeric()) {
            throw new IllegalArgumentException("applyNumeric 要求数值列,列 \"" + colName + "\" 是 " + c.dtype());
        }
        double[] out = new double[c.size()];
        for (int i = 0; i < c.size(); i++) {
            out[i] = c.isNull(i) ? Double.NaN : fn.applyAsDouble(c.getDouble(i));
        }
        return new DoubleColumn(colName, out);
    }

    /**
     * 对某列每个元素(Object)应用函数,返回新 String 列(对齐 pandas Series.apply(str))。
     * @param df      DataFrame 目标表,非 null
     * @param colName String 列名,必须存在;非 null
     * @param fn      java.util.function.Function&lt;Object,String&gt; 转字符串函数;非 null;入参可能为 null(缺失)
     * @return StringColumn 同长度新列;每元素为 fn 应用结果
     */
    public static StringColumn applyToString(DataFrame df, String colName, Function<Object, String> fn) {
        java.util.Objects.requireNonNull(colName, "colName 不能为 null");
        Column c = df.getColumn(colName);
        String[] out = new String[c.size()];
        for (int i = 0; i < c.size(); i++) {
            // 因为 get 对 NaN 返回 Double.NaN(不是 null),所以缺失行(isNull)统一传 null 给 fn
            Object v = c.isNull(i) ? null : c.get(i);
            out[i] = fn.apply(v);
        }
        return new StringColumn(colName, out);
    }

    // ======================== 统计扩展(按 §3.1.1.1 内聚到此类)========================
    //
    // 设计原则:走 StatsProvider SPI(pearson/spearman/covariance/percentile/skewness/kurtosis
    // + 新加 rank/mad/sem),引 jian-num-bridge 时自动升级为 Commons Math 精确实现;
    // 累积/差分类(cumsum/diff/pct_change/clip/round)纯算法,直接实现不走 SPI。

    // ----- 单列 SPI 统计(返回标量,委托 StatsProvider.current())-----

    /**
     * 列偏度(对齐 pandas Series.skew;经 SPI)。
     * @param c Column 列
     */
    public static double skewness(Column c) {
        return StatsProvider.current().skewness(toDoubleArrSkipNaN(c));
    }
    /**
     * 列峰度(超额,对齐 pandas Series.kurt;经 SPI)。
     * @param c Column 列
     */
    public static double kurtosis(Column c) {
        return StatsProvider.current().kurtosis(toDoubleArrSkipNaN(c));
    }
    /**
     * 列平均绝对偏差(对齐 pandas Series.mad;经 SPI)。
     * @param c Column 列
     */
    public static double mad(Column c) {
        return StatsProvider.current().mad(toDoubleArrSkipNaN(c));
    }
    /**
     * 列标准误差(对齐 pandas Series.sem;经 SPI)。
     * @param c Column 列
     */
    public static double sem(Column c) {
        return StatsProvider.current().sem(toDoubleArrSkipNaN(c));
    }
    /**
     * 列精确分位数(对齐 pandas Series.quantile;经 SPI)。
     * @param c Column 列
     * @param q double 分位数[0,1]
     */
    public static double quantile(Column c, double q) {
        // q=NaN 显式拒(范围校验对 NaN 双 false 会静默放行;与 jian-num percentile 口径一致)
        if (Double.isNaN(q) || q < 0 || q > 1) throw new IllegalArgumentException("quantile q 范围 [0,1],实际:" + q);
        return StatsProvider.current().percentile(toDoubleArrSkipNaN(c), q);
    }

    /**
     * 两列皮尔逊相关(对齐 pandas Series.corr;经 SPI)。
     * <p>配对逻辑:因为各自 skipNaN 后再对齐,会在错位 NaN 时
     * (a=[1,NaN,3] vs b=[1,2,NaN])两列剩余数恰等则【错位配对】直接算出错误相关系数,
     * 剩余数不等则误抛"长度不一致",所以采用【同下标】双非 NaN 配对过滤
     * (与 pandas 逐对删除等价,与 jian-num pairFilterNaN 同法),长度按原始列长校验。
     * @param x 参数;非 null
     * @param y 参数;非 null
     * @param method String 方法(pearson/spearman)
     */
    public static double corr(Column x, Column y, String method) {
        double[][] paired = pairedNonNaN(x, y, "corr");
        return "spearman".equalsIgnoreCase(method)
            ? StatsProvider.current().spearman(paired[0], paired[1])
            : StatsProvider.current().pearson(paired[0], paired[1]);  // 默认 pearson
    }
    /**
     * 两列协方差(对齐 pandas Series.cov;经 SPI)。
     * <p>同 corr 的同下标配对(不做各自 skipNaN,见 corr 注释)。
     * @param x 参数;非 null
     * @param y 参数;非 null
     */
    public static double cov(Column x, Column y) {
        double[][] paired = pairedNonNaN(x, y, "cov");
        return StatsProvider.current().covariance(paired[0], paired[1]);
    }

    /**
     * 同下标双非 NaN 配对过滤(corr/cov 共用)。
     * 数据走向:x/y 两列 → 逐原始下标 i 取值 → 双方均非缺失才保留 → [xx, yy] 两个等长数组。
     * 逻辑路线:路径 A(原始长度不等)→ 抛 IAE(这是调用方数据错误,不是 NaN 问题);
     * 路径 B(正常)→ 返回配对数组(可能为空,空数组交由 SPI 返回 NaN)。
     */
    private static double[][] pairedNonNaN(Column x, Column y, String op) {
        if (x.size() != y.size()) {
            throw new IllegalArgumentException(op + " 两列长度不一致:" + x.size() + " vs " + y.size());
        }
        int n = x.size();
        double[] a = new double[n], b = new double[n];
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            boolean xMiss = x.isNull(i) || Double.isNaN(x.getDouble(i));
            boolean yMiss = y.isNull(i) || Double.isNaN(y.getDouble(i));
            if (!xMiss && !yMiss) { a[cnt] = x.getDouble(i); b[cnt] = y.getDouble(i); cnt++; }
        }
        return new double[][] {
            cnt == n ? a : java.util.Arrays.copyOf(a, cnt),
            cnt == n ? b : java.util.Arrays.copyOf(b, cnt)
        };
    }

    /**
     * 新列名兜底:9 个"派生新列"算子的 newColName 参数为 null/空时回退
     * {@code {源列}_{op}}(如 v_cumsum),不透传 null 产出"列名为 null"的列
     * (下游 toString 即 NPE)。
     * @param src      String 源列名(取 c.name()),非 null
     * @param op       String 算子短名(cumsum/diff/pct_change/clip/round/rank/...)
     * @param requested String 调用方请求的列名;null/空触发回退
     * @return String 最终列名,恒非 null
     */
    static String deriveNewName(String src, String op, String requested) {
        if (requested != null && !requested.isEmpty()) return requested;
        return src + "_" + op;
    }

    // ----- 列内秩(返回同长度新列;经 SPI rank)-----

    /**
     * 列内秩(对齐 pandas Series.rank;经 SPI)。
     * @param c      Column 待排秩列;非 null
     * @param method String "average"/"min"/"max"/"first"/"dense";null 视为 "average"
     * @param colName String 新列名
     * @return DoubleColumn 同长度秩列;NaN 位置保留 NaN
     */
    public static DoubleColumn rank(Column c, String method, String colName) {
        colName = deriveNewName(c.name(), "rank", colName);
        // 非数值列(STRING/OBJECT,含桥构造的字符串列)rank 走自然序
        // (pandas Series.rank 对 str 支持,实测 [1,3,2]);按 String.valueOf 比较,行为确定
        if (!c.dtype().isNumeric()) return rankByStringOrder(c, method, colName);
        double[] data = toDoubleArrPreserveNaN(c);  // rank 保留 NaN 位置
        double[] r = StatsProvider.current().rank(data, method);
        return new DoubleColumn(colName, r);
    }

    /**
     * 非数值列(STRING/OBJECT)的自然序 rank:对齐 pandas Series.rank 对
     * 字符串的支持(pd.Series(['a','c','b']).rank() → [1,3,2]);值经 String.valueOf 归一比较。
     * 数值算子误用于字符串列时,经此处给出正确结果而非抛"String 列不能转 double"的底层错。
     * <p>数据走向:非缺失 (原下标, 字符串值) 对 → 按值字典序稳定排序 → 按 method 分配 1 基秩
     * → 写回原下标(缺失位 NaN)。
     * <p>method 语义与数值 rank 相同:average(同值平均,默认)/min/max/first(出现顺序)/dense(去重序)。
     * @param c    Column STRING 列;非 null
     * @param method String 秩方法;null 视为 average
     * @param colName String 新列名(已兜底)
     * @return DoubleColumn 同长度秩列;缺失位 NaN
     */
    private static DoubleColumn rankByStringOrder(Column c, String method, String colName) {
        String m = method == null ? "average" : method.toLowerCase();
        int n = c.size();
        int cnt = 0;
        for (int i = 0; i < n; i++) if (!c.isNull(i)) cnt++;
        Integer[] present = new Integer[cnt];
        int w = 0;
        for (int i = 0; i < n; i++) if (!c.isNull(i)) present[w++] = i;
        // 稳定排序:同值保持原相对顺序(first 语义依赖)
        java.util.Arrays.sort(present, (p, q) -> {
            int cmp = c.get(p).toString().compareTo(c.get(q).toString());
            return cmp != 0 ? cmp : Integer.compare(p, q);
        });
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        int k = present.length;
        int denseCnt = 0;
        int i = 0;
        while (i < k) {
            // 找同值段 [i, j);段推进时遇到新值 dense 计数 +1
            int j = i;
            while (j < k && c.get(present[j]).toString().equals(c.get(present[i]).toString())) j++;
            denseCnt++;
            double avg = (i + 1 + j) / 2.0;          // average:1 基,段内均值
            for (int t = i; t < j; t++) {
                double rk = switch (m) {
                    case "min" -> (double) (i + 1);
                    case "max" -> (double) j;
                    case "first" -> (double) (t + 1);
                    case "dense" -> (double) denseCnt;
                    default -> avg;                   // average(默认)
                };
                out[present[t]] = rk;
            }
            i = j;
        }
        return new DoubleColumn(colName, out);
    }

    // ----- 累积运算(cumsum/cummax/cummin/cumprod;直接实现,skip NaN)-----

    /**
     * 列累积和(对齐 pandas Series.cumsum;缺失行保持缺失)。
     * @param c Column 列
     * @param colName String 列名,必须存在;非 null
     */
    public static DoubleColumn cumsum(Column c, String colName) {
        colName = deriveNewName(c.name(), "cumsum", colName);
        double[] out = new double[c.size()];
        double acc = 0.0;
        boolean seenValid = false;
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i) || Double.isNaN(c.getDouble(i))) {
                out[i] = Double.NaN;  // 缺失保持缺失(不参与累加)
            } else {
                acc = seenValid ? acc + c.getDouble(i) : c.getDouble(i);
                out[i] = acc;
                seenValid = true;
            }
        }
        return new DoubleColumn(colName, out);
    }

    /**
     * 列累积最大(对齐 pandas Series.cummax;缺失行保持缺失)。
     * @param c Column 列
     * @param colName String 列名,必须存在;非 null
     */
    public static DoubleColumn cummax(Column c, String colName) {
        colName = deriveNewName(c.name(), "cummax", colName);
        double[] out = new double[c.size()];
        Double cur = null;
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i) || Double.isNaN(c.getDouble(i))) {
                out[i] = Double.NaN;
            } else {
                double v = c.getDouble(i);
                cur = (cur == null) ? v : Math.max(cur, v);
                out[i] = cur;
            }
        }
        return new DoubleColumn(colName, out);
    }

    /**
     * 列累积最小(对齐 pandas Series.cummin)。
     * @param c Column 列
     * @param colName String 列名,必须存在;非 null
     */
    public static DoubleColumn cummin(Column c, String colName) {
        colName = deriveNewName(c.name(), "cummin", colName);
        double[] out = new double[c.size()];
        Double cur = null;
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i) || Double.isNaN(c.getDouble(i))) {
                out[i] = Double.NaN;
            } else {
                double v = c.getDouble(i);
                cur = (cur == null) ? v : Math.min(cur, v);
                out[i] = cur;
            }
        }
        return new DoubleColumn(colName, out);
    }

    /**
     * 列累积积(对齐 pandas Series.cumprod)。
     * @param c Column 列
     * @param colName String 列名,必须存在;非 null
     */
    public static DoubleColumn cumprod(Column c, String colName) {
        colName = deriveNewName(c.name(), "cumprod", colName);
        double[] out = new double[c.size()];
        Double acc = null;
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i) || Double.isNaN(c.getDouble(i))) {
                out[i] = Double.NaN;
            } else {
                double v = c.getDouble(i);
                acc = (acc == null) ? v : acc * v;
                out[i] = acc;
            }
        }
        return new DoubleColumn(colName, out);
    }

    // ----- 差分类(直接实现)-----

    /**
     * 列差分(对齐 pandas Series.diff(periods);periods 正向前、负向后)。
     * @param c       Column 待差分列
     * @param periods int 步长,0 抛异常;正=与前 periods 行差,负=与后 |periods| 行差
     * @param colName String 新列名
     * @return DoubleColumn 同长度;前/后 periods 行为 NaN
     */
    public static DoubleColumn diff(Column c, int periods, String colName) {
        colName = deriveNewName(c.name(), "diff", colName);
        if (periods == 0) throw new IllegalArgumentException("diff periods 不能为 0");
        int n = c.size();
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        int abs = Math.abs(periods);
        if (periods > 0) {
            for (int i = abs; i < n; i++) {
                if (!c.isNull(i) && !c.isNull(i - abs)
                    && !Double.isNaN(c.getDouble(i)) && !Double.isNaN(c.getDouble(i - abs))) {
                    out[i] = c.getDouble(i) - c.getDouble(i - abs);
                }
            }
        } else {
            for (int i = 0; i + abs < n; i++) {
                if (!c.isNull(i) && !c.isNull(i + abs)
                    && !Double.isNaN(c.getDouble(i)) && !Double.isNaN(c.getDouble(i + abs))) {
                    out[i] = c.getDouble(i) - c.getDouble(i + abs);
                }
            }
        }
        return new DoubleColumn(colName, out);
    }

    /**
     * 列百分比变化(对齐 pandas Series.pct_change(periods))。
     * 因为 (v-prev)/|prev| 在 prev 为负时符号反转(pandas 例:prev=-1, v=3 → pandas=-4),
     * 所以对齐 pandas 直接除 prev。
     * 设计差异(文档声明,doc/00-overview.md §10):prev==0 时 pandas 返回 ±inf(带警告),
     * jian 返回 NaN —— NaN 不污染后续聚合,更符合 jian 缺失语义。
     * @return DoubleColumn 同长度;前/后 periods 行为 NaN;公式:(v[i] - v[i-periods]) / v[i-periods]
     * @param c Column 列;非 null
     * @param periods int 位移步数
     * @param colName String 列名,必须存在;非 null
     */
    public static DoubleColumn pctChange(Column c, int periods, String colName) {
        colName = deriveNewName(c.name(), "pct_change", colName);
        if (periods == 0) throw new IllegalArgumentException("pct_change periods 不能为 0");
        int n = c.size();
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        int abs = Math.abs(periods);
        if (periods > 0) {
            for (int i = abs; i < n; i++) {
                if (!c.isNull(i) && !c.isNull(i - abs)) {
                    double cur = c.getDouble(i), prev = c.getDouble(i - abs);
                    if (!Double.isNaN(cur) && !Double.isNaN(prev) && prev != 0.0) {
                        out[i] = (cur - prev) / prev;
                    }
                }
            }
        } else {
            for (int i = 0; i + abs < n; i++) {
                if (!c.isNull(i) && !c.isNull(i + abs)) {
                    double cur = c.getDouble(i), next = c.getDouble(i + abs);
                    if (!Double.isNaN(cur) && !Double.isNaN(next) && next != 0.0) {
                        out[i] = (cur - next) / next;
                    }
                }
            }
        }
        return new DoubleColumn(colName, out);
    }

    // ----- 裁剪 / 四舍五入(直接实现)-----

    /**
     * 列裁剪到 [lower, upper](对齐 pandas Series.clip)。
     * @param c       Column 待裁剪列
     * @param lower   double 下界;Double.NEGATIVE_INFINITY 表示不限下界
     * @param upper   double 上界;Double.POSITIVE_INFINITY 表示不限上界
     * @param colName String 新列名
     * @return DoubleColumn 同长度;< lower 处变为 lower,> upper 变为 upper;NaN 保留
     */
    public static DoubleColumn clip(Column c, double lower, double upper, String colName) {
        colName = deriveNewName(c.name(), "clip", colName);
        double[] out = new double[c.size()];
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i) || Double.isNaN(c.getDouble(i))) {
                out[i] = Double.NaN;
            } else {
                double v = c.getDouble(i);
                if (v < lower) out[i] = lower;
                else if (v > upper) out[i] = upper;
                else out[i] = v;
            }
        }
        return new DoubleColumn(colName, out);
    }

    /**
     * 列舍入(对齐 pandas Series.round:round-half-to-even 银行家舍入)。
     * <p>因为 {@code Math.round}(half-up,向正无穷)在精确 .5 边界与 pandas 相反
     * (2.5→3 vs pandas 2.0)、且返回 long 在 |v|≥9.2e18 饱和到 Long.MAX
     * (1e300 舍入得 9.22e18 完全错误),所以用 {@code Math.rint}
     * (half-even,返回 double 无饱和)。
     * @param c        Column 待舍入列
     * @param decimals int 小数位数;0=舍入到整数;负数=到十/百/千位
     * @param colName  String 新列名
     * @return DoubleColumn 同长度;NaN 保留
     */
    public static DoubleColumn round(Column c, int decimals, String colName) {
        colName = deriveNewName(c.name(), "round", colName);
        double[] out = new double[c.size()];
        double factor = Math.pow(10, decimals);
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i) || Double.isNaN(c.getDouble(i))) {
                out[i] = Double.NaN;
            } else {
                // rint:half-even(2.5→2.0、-3.5→-4.0、0.5→0.0)+ 返回 double,大数不饱和
                out[i] = Math.rint(c.getDouble(i) * factor) / factor;
            }
        }
        return new DoubleColumn(colName, out);
    }

    // ----- 全缺失/全有效判断(all/any;对齐 pandas DataFrame.all/any)-----

    /**
     * 列 all:所有非缺失值为真(non-zero/non-empty);对齐 pandas Series.all。
     * @param c Column 列
     */
    public static boolean all(Column c) {
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i)) continue;
            Object v = c.get(i);
            if (v instanceof Number n && n.doubleValue() == 0) return false;
            if (v instanceof Boolean b && !b) return false;
            if (v instanceof String s && s.isEmpty()) return false;
        }
        return true;
    }

    /**
     * 列 any:任一非缺失值为真;对齐 pandas Series.any。
     * @param c Column 列
     */
    public static boolean any(Column c) {
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i)) continue;
            Object v = c.get(i);
            if (v instanceof Number n && n.doubleValue() != 0) return true;
            if (v instanceof Boolean b && b) return true;
            if (v instanceof String s && !s.isEmpty()) return true;
        }
        return false;
    }

    /**
     * 列积(对齐 pandas Series.prod;skip NaN)。
     * @param c Column 列
     */
    public static double prod(Column c) {
        double p = 1.0;
        boolean any = false;
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i) || Double.isNaN(c.getDouble(i))) continue;
            p *= c.getDouble(i);
            any = true;
        }
        return any ? p : 1.0;  // 全缺失或空返回 1.0(对齐 pandas)
    }

    /**
     * 去重键归一:-0.0 归一为 +0.0,其余原样。
     * <p>背景:{@code Double.equals(+0.0, -0.0)} 为 false,HashSet/Object 键去重把 ±0.0 计为
     * 两个值;pandas/numpy 按数值相等(0.0 == -0.0)计 1。§10.16 第 6 条已声明 ±0.0 数值等价
     * (groupBy/merge/loc 键归一),本 helper 把同一立场延伸到 nunique/valueCounts/is_unique
     * 全部去重入口(共 6 处调用)。
     * @param v Object 待去重值(非 null)
     * @return Object 归一后的去重键(仅 Double 的 ±0.0 → +0.0)
     */
    static Object normUniqueKey(Object v) {
        return (v instanceof Double d && d.doubleValue() == 0.0) ? Double.valueOf(0.0) : v;
    }

    /**
     * 列唯一值数(对齐 pandas Series.nunique;skip 缺失;±0.0 数值等价计 1)。
     * @param c Column 列
     */
    public static int nunique(Column c) {
        java.util.Set<Object> seen = new java.util.HashSet<>();
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i)) continue;
            seen.add(normUniqueKey(c.get(i)));
        }
        return seen.size();
    }

    // ----- 内部辅助:把 Column 转 double[](skip NaN / preserve NaN)-----

    /** 转 double[],skip NaN/null(SPI 统计方法用)。 */
    private static double[] toDoubleArrSkipNaN(Column c) {
        java.util.List<Double> vals = new java.util.ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            if (!c.isNull(i) && !Double.isNaN(c.getDouble(i))) vals.add(c.getDouble(i));
        }
        return vals.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** 转 double[],保留 NaN 位置(rank 类用)。 */
    private static double[] toDoubleArrPreserveNaN(Column c) {
        double[] out = new double[c.size()];
        for (int i = 0; i < c.size(); i++) {
            out[i] = c.isNull(i) ? Double.NaN : c.getDouble(i);
        }
        return out;
    }
}
