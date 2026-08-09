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
     * 求和(数值列,skip NaN,Kahan 补偿求和,减少大数累加误差)。
     * @param c Column 待求和列,数值类型(INT/LONG/DOUBLE);非 null
     * @return double 非空值之和;全空返回 0.0
     */
    public static double sum(Column c) {
        double sum = 0.0, comp = 0.0;
        int n = c.size();
        for (int i = 0; i < n; i++) {
            if (c.isNull(i)) continue;
            double y = c.getDouble(i) - comp;
            double t = sum + y;
            comp = (t - sum) - y;
            sum = t;
        }
        return sum;
    }

    /**
     * 均值(数值列,skip NaN,Kahan 累加)。
     * @param c Column 待求均值列,数值类型;非 null
     * @return double 非空值的算术平均;**全空返回 NaN**
     */
    public static double mean(Column c) {
        double sum = 0.0, comp = 0.0; int cnt = 0;
        int n = c.size();
        for (int i = 0; i < n; i++) {
            if (!c.isNull(i)) {
                double y = c.getDouble(i) - comp;
                double t = sum + y;
                comp = (t - sum) - y;
                sum = t;
                cnt++;
            }
        }
        if (cnt == 0) return Double.NaN;
        return sum / cnt;
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
     * @param q double 分位点,范围 [0.0, 1.0]
     * @return double 分位数值;全空返回 NaN
     */
    public static double percentile(Column c, double q) {
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
        Column c = df.getColumn(colName);
        String[] out = new String[c.size()];
        for (int i = 0; i < c.size(); i++) {
            // 修复:缺失行(isNull)传 null 给 fn,而不是 Double.NaN(get 现在对 NaN 返回 Double.NaN 不是 null)
            Object v = c.isNull(i) ? null : c.get(i);
            out[i] = fn.apply(v);
        }
        return new StringColumn(colName, out);
    }

    // ======================== 阶段 B 统计扩展(2026-08-09;按 §3.1.1.1 内聚到此类)========================
    //
    // 设计原则:走 StatsProvider SPI(pearson/spearman/covariance/percentile/skewness/kurtosis
    // + 新加 rank/mad/sem),引 jian-num-bridge 时自动升级为 Commons Math 精确实现;
    // 累积/差分类(cumsum/diff/pct_change/clip/round)纯算法,直接实现不走 SPI。

    // ----- 单列 SPI 统计(返回标量,委托 StatsProvider.current())-----

    /** 列偏度(对齐 pandas Series.skew;经 SPI)。 */
    public static double skewness(Column c) {
        return StatsProvider.current().skewness(toDoubleArrSkipNaN(c));
    }
    /** 列峰度(超额,对齐 pandas Series.kurt;经 SPI)。 */
    public static double kurtosis(Column c) {
        return StatsProvider.current().kurtosis(toDoubleArrSkipNaN(c));
    }
    /** 列平均绝对偏差(对齐 pandas Series.mad;经 SPI)。 */
    public static double mad(Column c) {
        return StatsProvider.current().mad(toDoubleArrSkipNaN(c));
    }
    /** 列标准误差(对齐 pandas Series.sem;经 SPI)。 */
    public static double sem(Column c) {
        return StatsProvider.current().sem(toDoubleArrSkipNaN(c));
    }
    /** 列精确分位数(对齐 pandas Series.quantile;经 SPI)。 */
    public static double quantile(Column c, double q) {
        if (q < 0 || q > 1) throw new IllegalArgumentException("quantile q 范围 [0,1],实际:" + q);
        return StatsProvider.current().percentile(toDoubleArrSkipNaN(c), q);
    }

    /** 两列皮尔逊相关(对齐 pandas Series.corr;经 SPI)。 */
    public static double corr(Column x, Column y, String method) {
        double[] a = toDoubleArrSkipNaN(x), b = toDoubleArrSkipNaN(y);
        // 简化:严格配对(同长度,去双 NaN);若长度不一致抛异常
        if (a.length != b.length) {
            throw new IllegalArgumentException("corr 两列长度不一致:" + a.length + " vs " + b.length);
        }
        // 双 NaN 配对去除
        java.util.List<Double> xa = new java.util.ArrayList<>(), yb = new java.util.ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) { xa.add(a[i]); yb.add(b[i]); }
        }
        double[] xx = xa.stream().mapToDouble(Double::doubleValue).toArray();
        double[] yy = yb.stream().mapToDouble(Double::doubleValue).toArray();
        return "spearman".equalsIgnoreCase(method)
            ? StatsProvider.current().spearman(xx, yy)
            : StatsProvider.current().pearson(xx, yy);  // 默认 pearson
    }
    /** 两列协方差(对齐 pandas Series.cov;经 SPI)。 */
    public static double cov(Column x, Column y) {
        double[] a = toDoubleArrSkipNaN(x), b = toDoubleArrSkipNaN(y);
        if (a.length != b.length) {
            throw new IllegalArgumentException("cov 两列长度不一致:" + a.length + " vs " + b.length);
        }
        java.util.List<Double> xa = new java.util.ArrayList<>(), yb = new java.util.ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) { xa.add(a[i]); yb.add(b[i]); }
        }
        double[] xx = xa.stream().mapToDouble(Double::doubleValue).toArray();
        double[] yy = yb.stream().mapToDouble(Double::doubleValue).toArray();
        return StatsProvider.current().covariance(xx, yy);
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
        double[] data = toDoubleArrPreserveNaN(c);  // rank 保留 NaN 位置
        double[] r = StatsProvider.current().rank(data, method);
        return new DoubleColumn(colName, r);
    }

    // ----- 累积运算(cumsum/cummax/cummin/cumprod;直接实现,skip NaN)-----

    /** 列累积和(对齐 pandas Series.cumsum;缺失行保持缺失)。 */
    public static DoubleColumn cumsum(Column c, String colName) {
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

    /** 列累积最大(对齐 pandas Series.cummax;缺失行保持缺失)。 */
    public static DoubleColumn cummax(Column c, String colName) {
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

    /** 列累积最小(对齐 pandas Series.cummin)。 */
    public static DoubleColumn cummin(Column c, String colName) {
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

    /** 列累积积(对齐 pandas Series.cumprod)。 */
    public static DoubleColumn cumprod(Column c, String colName) {
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
     * @return DoubleColumn 同长度;前/后 periods 行为 NaN;公式:(v[i] - v[i-periods]) / |v[i-periods]|
     */
    public static DoubleColumn pctChange(Column c, int periods, String colName) {
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
                        out[i] = (cur - prev) / Math.abs(prev);
                    }
                }
            }
        } else {
            for (int i = 0; i + abs < n; i++) {
                if (!c.isNull(i) && !c.isNull(i + abs)) {
                    double cur = c.getDouble(i), next = c.getDouble(i + abs);
                    if (!Double.isNaN(cur) && !Double.isNaN(next) && next != 0.0) {
                        out[i] = (cur - next) / Math.abs(next);
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
     * 列四舍五入(对齐 pandas Series.round)。
     * @param c        Column 待四舍五入列
     * @param decimals int 小数位数;0=四舍五入到整数;负数=到十/百/千位
     * @param colName  String 新列名
     * @return DoubleColumn 同长度;NaN 保留
     */
    public static DoubleColumn round(Column c, int decimals, String colName) {
        double[] out = new double[c.size()];
        double factor = Math.pow(10, decimals);
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i) || Double.isNaN(c.getDouble(i))) {
                out[i] = Double.NaN;
            } else {
                out[i] = Math.round(c.getDouble(i) * factor) / factor;
            }
        }
        return new DoubleColumn(colName, out);
    }

    // ----- 全缺失/全有效判断(all/any;对齐 pandas DataFrame.all/any)-----

    /** 列 all:所有非缺失值为真(non-zero/non-empty);对齐 pandas Series.all。 */
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

    /** 列 any:任一非缺失值为真;对齐 pandas Series.any。 */
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

    /** 列积(对齐 pandas Series.prod;skip NaN)。 */
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

    /** 列唯一值数(对齐 pandas Series.nunique;skip 缺失)。 */
    public static int nunique(Column c) {
        java.util.Set<Object> seen = new java.util.HashSet<>();
        for (int i = 0; i < c.size(); i++) {
            if (c.isNull(i)) continue;
            seen.add(c.get(i));
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
