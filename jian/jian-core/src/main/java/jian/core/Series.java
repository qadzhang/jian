package jian.core;

import java.util.ArrayList;
import java.util.List;

// ┌─ What : Series —— DataFrame 单列的包装类(对齐 pandas.Series)
// │  Why  : 规范 01 §4;很多操作在单列上有语义(排序/统计/字符串/时间);DataFrame 单列返回 Series 更自然
// │  Who  : 用户经 df.getColumn(name).asSeries() 或 DataFrame.getSeries(name) 获取
// │  When : 单列操作场景
// │  Where: jian-core/Series.java
/**
 * Series,对齐 pandas.Series。包装单个 {@link Column},提供统计/排序/变换等单列操作。
 *
 * <p>用法:
 * <pre>{@code
 * Series s = df.getSeries("salary");
 * double mean = s.mean();
 * Series sorted = s.sortAscending();
 * Series upper = df.getSeries("name").str().upper().asSeries();
 * }</pre>
 */
public final class Series {

    private final Column column;

    /**
     * 包级构造(由 DataFrame 内部调用)。
     * @param column Column 被包装的列,非 null
     */
    Series(Column column) { this.column = column; }

    /**
     * 工厂:从 Column 创建 Series。
     * @param column Column 被包装的列,非 null
     * @return Series 包装 column 的新实例
     */
    public static Series of(Column column) { return new Series(column); }

    /** @return int 长度 == column.size() */
    public int size() { return column.size(); }

    /** @return DType 列类型 */
    public DType dtype() { return column.dtype(); }

    /** @return String 列名 */
    public String name() { return column.name(); }

    /**
     * @param i int 行下标 ∈ [0, size())
     * @return Object 第 i 个值(可能为 null)
     */
    public Object get(int i) { return column.get(i); }

    /**
     * @param i int 行下标
     * @return double 数值列返回 getDouble;类型不匹配抛异常
     */
    public double getDouble(int i) { return column.getDouble(i); }

    /**
     * @param i int 行下标
     * @return boolean true=该行缺失
     */
    public boolean isNull(int i) { return column.isNull(i); }

    /**
     * 整体缺失谓词(对齐 pandas Series.isna):返回每行是否缺失的 boolean[]。
     * 与 {@link #isNull(int)} 区别:本方法一次性返回整体掩码,后者按索引查单值。
     *
     * @return boolean[] 长度 == size();true=该行缺失
     */
    public boolean[] isna() {
        boolean[] r = new boolean[column.size()];
        for (int i = 0; i < r.length; i++) r[i] = column.isNull(i);
        return r;
    }

    /**
     * isna 的别名(对齐 pandas Series.isnull,两者在 pandas 中完全等价)。
     *
     * @return boolean[] 同 {@link #isna()}
     */
    public boolean[] isnull() { return isna(); }

    /** @return int 非空个数 = size() - nullCount() */
    public int count() { return column.size() - column.nullCount(); }

    /** @return Column 底层列引用(直接引用,不克隆) */
    public Column column() { return column; }

    // ===== 统计(委托 DataFrameStats) =====

    /** @return double 求和(skip NaN) */
    public double sum() { return DataFrameStats.sum(column); }
    /** @return double 均值;全空 NaN */
    public double mean() { return DataFrameStats.mean(column); }
    /** @return double 最小;全空 NaN */
    public double min() { return DataFrameStats.min(column); }
    /** @return double 最大;全空 NaN */
    public double max() { return DataFrameStats.max(column); }
    /** @return double 中位数;全空 NaN */
    public double median() { return DataFrameStats.median(column); }
    /** @return double 样本标准差(ddof=1) */
    public double std() { return DataFrameStats.std(column); }
    /**
     * @param q double 分位点 ∈ [0.0, 1.0]
     * @return double 分位数值
     */
    public double percentile(double q) { return DataFrameStats.percentile(column, q); }

    /**
     * describe 摘要。
     * @return double[8] 顺序:count/mean/std/min/Q1/median/Q3/max
     */
    public double[] describe() {
        return new double[] {
                count(), mean(), std(), min(),
                percentile(0.25), median(), percentile(0.75), max()
        };
    }

    // ===== 排序 =====

    /**
     * 升序排序后的行下标(不改数据,只返回下标顺序)。
     * @return int[] 长度 == size();元素为原行下标,按值升序排列;null 排末尾
     */
    public int[] sortIndicesAscending() {
        return sortIndices(true);
    }

    /**
     * 降序排序后的行下标。
     * @return int[] 同上,降序
     */
    public int[] sortIndicesDescending() {
        return sortIndices(false);
    }

    /**
     * 排序下标(内部)。
     * @param asc boolean true=升序;false=降序
     * @return int[] 排序后的行下标数组
     */
    @SuppressWarnings("unchecked")
    private int[] sortIndices(boolean asc) {
        int n = column.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            // 修复:用 isNull 判断缺失(get() 对 NaN 现在返回 Double.NaN 不是 null)
            boolean aNull = column.isNull(a), bNull = column.isNull(b);
            if (aNull && bNull) return 0;
            if (aNull) return 1;  // 缺失排末尾
            if (bNull) return -1;
            Object va = column.get(a), vb = column.get(b);
            int cmp;
            if (va instanceof Number && vb instanceof Number) {
                cmp = Double.compare(((Number) va).doubleValue(), ((Number) vb).doubleValue());
            } else {
                cmp = ((Comparable<Object>) va).compareTo(vb);
            }
            return asc ? cmp : -cmp;
        });
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = idx[i];
        return r;
    }

    // ===== 变换(返回新 Series) =====

    /**
     * 取前 n 个。
     * @param n int 取前 n 行,≥ 0;n &gt; size() 时取全部
     * @return Series 前 n 行的新 Series
     */
    public Series head(int n) {
        n = Math.min(n, column.size());
        return new Series(column.slice(0, n));
    }

    /**
     * 取末尾 n 个。
     * @param n int 取末尾 n 行,≥ 0
     * @return Series 末尾 n 行的新 Series
     */
    public Series tail(int n) {
        int sz = column.size();
        n = Math.min(n, sz);
        return new Series(column.slice(sz - n, sz));
    }

    /**
     * 切片 [start, end)。
     * @param start int 起始(含) ∈ [0, size()]
     * @param end   int 结束(不含) ∈ [start, size()]
     * @return Series 长度 = end-start 的新 Series
     */
    public Series slice(int start, int end) {
        return new Series(column.slice(start, end));
    }

    // ===== .str accessor(字符串列专属操作,委托 StringColumn) =====

    /**
     * 字符串操作入口(对齐 pandas Series.str accessor)。
     * 要求 STRING 列;非 STRING 列先 toString 转 String[] 再返回。
     * @return StringColumn 当前列(STRING 时直接返回)或新 StringColumn(非 STRING 时)
     */
    public StringColumn str() {
        if (column.dtype() == DType.STRING) return (StringColumn) column;
        // 非 STRING 列:逐个 toString 转 String[](不用 Arrays.copyOf 避免类型异常)
        int n = column.size();
        String[] vals = new String[n];
        for (int i = 0; i < n; i++) {
            Object v = column.get(i);
            vals[i] = v == null ? null : v.toString();
        }
        return new StringColumn(column.name(), vals);
    }

    // ===== .dt accessor(时间列专属操作,委托 DateTimeColumn / DateColumn) =====

    /**
     * 时间操作入口(对齐 pandas Series.dt accessor)。
     * @return Dt 时间属性访问器
     * @throws IllegalStateException 列非时间类型(DATETIME/DATE)
     */
    public Dt dt() {
        if (!column.dtype().isTemporal()) {
            throw new IllegalStateException("dt() 仅时间列(DATETIME/DATE)可用,当前 " + column.dtype());
        }
        return new Dt(this);
    }

    /**
     * .dt accessor 结果(各种时间属性提取为 double[])。
     */
    public static final class Dt {
        private final Series series;

        /**
         * @param s Series 时间列(必须 DATETIME/DATE)
         */
        Dt(Series s) { this.series = s; }

        /** @return double[] 各行的年份;缺失为 NaN */
        public double[] year() { return extractTemporal("year"); }
        /** @return double[] 各行的月份 ∈ [1,12];缺失为 NaN */
        public double[] month() { return extractTemporal("month"); }
        /** @return double[] 各行的日 ∈ [1,31];缺失为 NaN */
        public double[] day() { return extractTemporal("day"); }
        /** @return double[] 各行的小时 ∈ [0,23](DATE 列恒 0);缺失为 NaN */
        public double[] hour() { return extractTemporal("hour"); }
        /** @return double[] 各行的分钟 ∈ [0,59];缺失为 NaN */
        public double[] minute() { return extractTemporal("minute"); }
        /** @return double[] 各行的秒 ∈ [0,59];缺失为 NaN */
        public double[] second() { return extractTemporal("second"); }
        /** @return double[] 各行的星期几 ∈ [1,7](1=周一,7=周日);缺失为 NaN */
        public double[] dayOfWeek() { return extractTemporal("dow"); }
        /** @return double[] 各行在一年中的第几天 ∈ [1,366];缺失为 NaN */
        public double[] dayOfYear() { return extractTemporal("doy"); }

        /**
         * 提取时间字段(内部)。
         * @param field String 字段名 year/month/day/hour/minute/second/dow/doy
         * @return double[] 各行对应字段的 double 值;缺失行为 NaN
         */
        @SuppressWarnings("unchecked")
        private double[] extractTemporal(String field) {
            int n = series.size();
            double[] r = new double[n];
            for (int i = 0; i < n; i++) {
                Object v = series.get(i);
                if (v == null) { r[i] = Double.NaN; continue; }
                java.time.temporal.TemporalAccessor t;
                if (v instanceof java.time.LocalDateTime) t = (java.time.LocalDateTime) v;
                else if (v instanceof java.time.LocalDate) t = (java.time.LocalDate) v;
                else { r[i] = Double.NaN; continue; }
                r[i] = switch (field) {
                    case "year" -> t.get(java.time.temporal.ChronoField.YEAR);
                    case "month" -> t.get(java.time.temporal.ChronoField.MONTH_OF_YEAR);
                    case "day" -> t.get(java.time.temporal.ChronoField.DAY_OF_MONTH);
                    case "hour" -> t.isSupported(java.time.temporal.ChronoField.HOUR_OF_DAY)
                            ? t.get(java.time.temporal.ChronoField.HOUR_OF_DAY) : 0;
                    case "minute" -> t.isSupported(java.time.temporal.ChronoField.MINUTE_OF_HOUR)
                            ? t.get(java.time.temporal.ChronoField.MINUTE_OF_HOUR) : 0;
                    case "second" -> t.isSupported(java.time.temporal.ChronoField.SECOND_OF_MINUTE)
                            ? t.get(java.time.temporal.ChronoField.SECOND_OF_MINUTE) : 0;
                    case "dow" -> t.get(java.time.temporal.ChronoField.DAY_OF_WEEK);
                    case "doy" -> t.get(java.time.temporal.ChronoField.DAY_OF_YEAR);
                    default -> Double.NaN;
                };
            }
            return r;
        }
    }

    // ===== 窗口操作(委托 Window,对齐 pandas 窗口族) =====

    /**
     * Rolling 窗口(对齐 pandas.Series.rolling(n))。
     * @param window int 窗口大小,≥ 1
     * @return Window.Rolling 可继续调 mean/std/min/max/count
     */
    public Window.Rolling rolling(int window) { return new Window.Rolling(this, window); }

    /**
     * Expanding 累积窗口。
     * @return Window.Expanding
     */
    public Window.Expanding expanding() { return new Window.Expanding(this); }

    /**
     * EWM 指数加权。
     * @param alpha double 平滑系数 ∈ (0.0, 1.0]
     * @return Window.EWM
     */
    public Window.EWM ewm(double alpha) { return new Window.EWM(this, alpha); }

    /**
     * 偏移(对齐 pandas shift;返回 double[])。
     * @param periods int 偏移期数;正=向后(取前 periods 期的值);负=向前
     * @return double[] 与原列等长;越界/源缺失的位置为 NaN
     */
    public double[] shift(int periods) {
        int n = column.size();
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            int src = i - periods;
            if (src < 0 || src >= n || column.isNull(src)) r[i] = Double.NaN;
            else r[i] = column.getDouble(src);
        }
        return r;
    }

    /**
     * 差分(对齐 pandas diff)。
     * @param periods int 差分期数;正=当前减前 periods 期
     * @return double[] data[i] - data[i-periods];任一缺失为 NaN
     */
    public double[] diff(int periods) {
        int n = column.size();
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            int src = i - periods;
            if (src < 0 || column.isNull(i) || column.isNull(src)) r[i] = Double.NaN;
            else r[i] = column.getDouble(i) - column.getDouble(src);
        }
        return r;
    }

    /**
     * pct_change(对齐 pandas,百分比变化)。
     * @param periods int 间隔期数
     * @return double[] (data[i]-data[i-periods])/data[i-periods];源为 0 或缺失时 NaN
     */
    public double[] pctChange(int periods) {
        int n = column.size();
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            int src = i - periods;
            if (src < 0 || column.isNull(i) || column.isNull(src) || column.getDouble(src) == 0)
                r[i] = Double.NaN;
            else r[i] = (column.getDouble(i) - column.getDouble(src)) / column.getDouble(src);
        }
        return r;
    }

    // ===== 类型转换 =====

    /**
     * 转 DataFrame(单列)。
     * @return DataFrame 单列 DataFrame,列名/类型同本 Series
     */
    public DataFrame toFrame() {
        java.util.Map<String, Object[]> m = new java.util.LinkedHashMap<>();
        m.put(column.name(), column.toObjectArray());
        return DataFrame.ofColumns(m);
    }

    /**
     * 转对象数组。
     * @return Object[] column.toObjectArray() 的结果(缺失为 null)
     */
    public Object[] toArray() { return column.toObjectArray(); }

    /**
     * @return String 多行格式,最多前 10 行 + "...";每行 "i\t值"
     */
    @Override public String toString() {
        StringBuilder sb = new StringBuilder("Series[" + name() + ", " + dtype() + ", len=" + size() + "]\n");
        int cap = Math.min(size(), 10);
        for (int i = 0; i < cap; i++) {
            sb.append(i).append("\t").append(get(i)).append('\n');
        }
        if (size() > cap) sb.append("...\n");
        return sb.toString();
    }

    // ======================== pandas 同名方法补全(2026-08-09)========================

    /** 转为 Java List(对齐 pandas Series.tolist)。 */
    public List<Object> tolist() {
        List<Object> out = new ArrayList<>(size());
        for (int i = 0; i < size(); i++) out.add(get(i));
        return out;
    }

    /** 转为字典 {index: value}(对齐 pandas Series.to_dict)。 */
    public java.util.Map<Integer, Object> to_dict() {
        java.util.Map<Integer, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < size(); i++) m.put(i, get(i));
        return m;
    }

    /** 转为原始数组(对齐 pandas Series.to_numpy;等价 toArray)。 */
    public Object[] to_numpy() { return toArray(); }

    /** 最大值的行号(对齐 pandas Series.argmax);空/全缺失返回 -1。 */
    public int argmax() {
        int bestIdx = -1; double best = Double.NaN;
        for (int i = 0; i < size(); i++) {
            if (column.isNull(i)) continue;
            double v = column.getDouble(i);
            if (Double.isNaN(v)) continue;
            if (bestIdx < 0 || v > best) { best = v; bestIdx = i; }
        }
        return bestIdx;
    }

    /** 最小值的行号(对齐 pandas Series.argmin);空/全缺失返回 -1。 */
    public int argmin() {
        int bestIdx = -1; double best = Double.NaN;
        for (int i = 0; i < size(); i++) {
            if (column.isNull(i)) continue;
            double v = column.getDouble(i);
            if (Double.isNaN(v)) continue;
            if (bestIdx < 0 || v < best) { best = v; bestIdx = i; }
        }
        return bestIdx;
    }

    /** 值 ∈ [left, right] 的布尔掩码(对齐 pandas Series.between)。 */
    public boolean[] between(double left, double right) {
        boolean[] out = new boolean[size()];
        for (int i = 0; i < size(); i++) {
            if (column.isNull(i)) continue;
            double v = column.getDouble(i);
            out[i] = !Double.isNaN(v) && v >= left && v <= right;
        }
        return out;
    }

    /** 是否单调递增(对齐 pandas Series.is_monotonic_increasing)。 */
    public boolean is_monotonic_increasing() {
        for (int i = 1; i < size(); i++) {
            if (column.isNull(i - 1) || column.isNull(i)) continue;
            double a = column.getDouble(i - 1), b = column.getDouble(i);
            if (Double.isNaN(a) || Double.isNaN(b)) continue;
            if (b < a) return false;
        }
        return true;
    }

    /** 是否所有值唯一(对齐 pandas Series.is_unique)。 */
    public boolean is_unique() {
        java.util.Set<Object> seen = new java.util.HashSet<>();
        for (int i = 0; i < size(); i++) {
            Object v = get(i);
            if (v == null) continue;
            if (!seen.add(v)) return false;
        }
        return true;
    }

    /** 是否含缺失(对齐 pandas Series.hasnans)。 */
    public boolean hasnans() {
        for (int i = 0; i < size(); i++) if (column.isNull(i)) return true;
        return false;
    }
}
