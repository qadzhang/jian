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

    /** 求和(数值列,skip NaN,Kahan 补偿求和)。 */
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

    /** 均值(数值列,skip NaN,Kahan 累加)。 */
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

    /** 样本标准差 ddof=1(对齐 pandas Series.std 默认)。 */
    public static double std(Column c) {
        return std(c, 1);
    }

    /** 标准差,可指定 ddof。 */
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

    /** 最小值(skip NaN)。 */
    public static double min(Column c) {
        double m = Double.POSITIVE_INFINITY;
        int n = c.size();
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (!c.isNull(i)) { any = true; if (c.getDouble(i) < m) m = c.getDouble(i); }
        }
        return any ? m : Double.NaN;
    }

    /** 最大值(skip NaN)。 */
    public static double max(Column c) {
        double m = Double.NEGATIVE_INFINITY;
        int n = c.size();
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (!c.isNull(i)) { any = true; if (c.getDouble(i) > m) m = c.getDouble(i); }
        }
        return any ? m : Double.NaN;
    }

    /** 中位数(skip NaN)。 */
    public static double median(Column c) {
        List<Double> vals = new ArrayList<>();
        int n = c.size();
        for (int i = 0; i < n; i++) if (!c.isNull(i)) vals.add(c.getDouble(i));
        if (vals.isEmpty()) return Double.NaN;
        vals.sort(Double::compare);
        int sz = vals.size();
        return sz % 2 == 0 ? (vals.get(sz / 2 - 1) + vals.get(sz / 2)) / 2 : vals.get(sz / 2);
    }

    /** 非空计数。 */
    public static int count(Column c) {
        int cnt = 0;
        int n = c.size();
        for (int i = 0; i < n; i++) if (!c.isNull(i)) cnt++;
        return cnt;
    }

    // ======================== 全 DataFrame 统计(对齐 pandas df.sum() / df.mean())========================

    /** 对所有数值列做统计,返回 Map<列名, 值>(跳过非数值列)。 */
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
     *
     * @param colName 数值列名
     * @param fn 一元函数(如 x -> x * 2)
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
     */
    public static StringColumn applyToString(DataFrame df, String colName, Function<Object, String> fn) {
        Column c = df.getColumn(colName);
        String[] out = new String[c.size()];
        for (int i = 0; i < c.size(); i++) {
            Object v = c.get(i);
            out[i] = fn.apply(v);
        }
        return new StringColumn(colName, out);
    }
}
