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

    Series(Column column) { this.column = column; }

    /** 从 Column 创建。 */
    public static Series of(Column column) { return new Series(column); }

    /** 长度。 */
    public int size() { return column.size(); }

    /** dtype。 */
    public DType dtype() { return column.dtype(); }

    /** 列名。 */
    public String name() { return column.name(); }

    /** 取第 i 个值(Object)。 */
    public Object get(int i) { return column.get(i); }

    /** 取 double(数值列)。 */
    public double getDouble(int i) { return column.getDouble(i); }

    /** 是否缺失。 */
    public boolean isNull(int i) { return column.isNull(i); }

    /** 非空个数。 */
    public int count() { return column.size() - column.nullCount(); }

    /** 底层 Column。 */
    public Column column() { return column; }

    // ===== 统计(委托 DataFrameStats) =====

    public double sum() { return DataFrameStats.sum(column); }
    public double mean() { return DataFrameStats.mean(column); }
    public double min() { return DataFrameStats.min(column); }
    public double max() { return DataFrameStats.max(column); }
    public double median() { return DataFrameStats.median(column); }
    public double std() { return DataFrameStats.std(column); }
    public double percentile(double q) { return DataFrameStats.percentile(column, q); }

    /** describe 摘要(返回 double[8]:count/mean/std/min/Q1/median/Q3/max)。 */
    public double[] describe() {
        return new double[] {
                count(), mean(), std(), min(),
                percentile(0.25), median(), percentile(0.75), max()
        };
    }

    // ===== 排序 =====

    /** 升序排序后的行下标(返回 int[],不改数据)。 */
    public int[] sortIndicesAscending() {
        return sortIndices(true);
    }

    public int[] sortIndicesDescending() {
        return sortIndices(false);
    }

    @SuppressWarnings("unchecked")
    private int[] sortIndices(boolean asc) {
        int n = column.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            Object va = column.get(a), vb = column.get(b);
            if (va == null && vb == null) return 0;
            if (va == null) return 1;  // null 排末尾
            if (vb == null) return -1;
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

    /** 取前 n 个,返回新 Series。 */
    public Series head(int n) {
        n = Math.min(n, column.size());
        return new Series(column.slice(0, n));
    }

    public Series tail(int n) {
        int sz = column.size();
        n = Math.min(n, sz);
        return new Series(column.slice(sz - n, sz));
    }

    /** 切片 [start, end)。 */
    public Series slice(int start, int end) {
        return new Series(column.slice(start, end));
    }

    // ===== .str accessor(字符串列专属操作,委托 StringColumn) =====

    /**
     * 字符串操作入口(对齐 pandas Series.str accessor)。
     * 要求 STRING 列;非 STRING 列先 astype 再操作。
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
     * 要求 DATETIME/DATE 列;返回 Series 的 year/month/day 等。
     */
    public Dt dt() {
        if (!column.dtype().isTemporal()) {
            throw new IllegalStateException("dt() 仅时间列(DATETIME/DATE)可用,当前 " + column.dtype());
        }
        return new Dt(this);
    }

    /** .dt accessor 结果(各种时间属性提取为 double[])。 */
    public static final class Dt {
        private final Series series;
        Dt(Series s) { this.series = s; }

        /** 年。 */
        public double[] year() { return extractTemporal("year"); }
        /** 月(1-12)。 */
        public double[] month() { return extractTemporal("month"); }
        /** 日(1-31)。 */
        public double[] day() { return extractTemporal("day"); }
        /** 小时(0-23)。 */
        public double[] hour() { return extractTemporal("hour"); }
        /** 分钟(0-59)。 */
        public double[] minute() { return extractTemporal("minute"); }
        /** 秒(0-59)。 */
        public double[] second() { return extractTemporal("second"); }
        /** 星期几(1=周一,7=周日)。 */
        public double[] dayOfWeek() { return extractTemporal("dow"); }
        /** 一年中的第几天(1-366)。 */
        public double[] dayOfYear() { return extractTemporal("doy"); }

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

    /** Rolling 窗口(对齐 pandas.Series.rolling(n))。 */
    public Window.Rolling rolling(int window) { return new Window.Rolling(this, window); }

    /** Expanding 累积窗口。 */
    public Window.Expanding expanding() { return new Window.Expanding(this); }

    /** EWM 指数加权(alpha ∈ (0,1])。 */
    public Window.EWM ewm(double alpha) { return new Window.EWM(this, alpha); }

    /** 偏移(对齐 pandas shift;返回 double[] 简化)。 */
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

    /** 差分(对齐 pandas diff)。 */
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

    /** pct_change(对齐 pandas)。 */
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

    /** 转 DataFrame(单列)。 */
    public DataFrame toFrame() {
        java.util.Map<String, Object[]> m = new java.util.LinkedHashMap<>();
        m.put(column.name(), column.toObjectArray());
        return DataFrame.ofColumns(m);
    }

    /** 转对象数组。 */
    public Object[] toArray() { return column.toObjectArray(); }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("Series[" + name() + ", " + dtype() + ", len=" + size() + "]\n");
        int cap = Math.min(size(), 10);
        for (int i = 0; i < cap; i++) {
            sb.append(i).append("\t").append(get(i)).append('\n');
        }
        if (size() > cap) sb.append("...\n");
        return sb.toString();
    }
}
