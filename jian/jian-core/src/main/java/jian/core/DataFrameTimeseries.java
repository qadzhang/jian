package jian.core;

import java.util.ArrayList;
import java.util.List;

// ┌─ What : DataFrameTimeseries —— 时间序列算子(shift/atTime/betweenTime/asof/tz×2,从 DataFrame.java 拆出)
// │  Why  : 落实 §3.1 ≤600 行红线;时序组 ~75 行自包含(全用 public API:getColumn/get/rowCount/takeRows)。
// │  Who  : 由 DataFrame.shift/atTime/betweenTime/asof/tzLocalize/tzConvert 委托调用
// │  When : 时间列的位移/筛选/asof/时区转换
// │  Where: jian-core/DataFrameTimeseries.java
// │  How  : 数据走向:df 取时间列 → 逐行扫描(位移/时刻匹配/区间/asof 全扫描)→ takeRows/rebuild 新表。
// │         tz×2 经 columnsInternal()+rebuild() 构造(不触 private 字段)。
final class DataFrameTimeseries {
    private DataFrameTimeseries() {}

    /** 行位移(periods>0 向下,首 periods 行 NaN;periods<0 向上)。 */
    static DoubleColumn shift(DataFrame df, String colName, int periods, String newColName) {
        Column c = df.getColumn(colName);
        if (periods == 0) throw new IllegalArgumentException("shift periods 不能为 0");
        int n = c.size();
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = 0; i < n; i++) {
            int src = i - periods;
            if (src >= 0 && src < n && !c.isNull(src) && !Double.isNaN(c.getDouble(src))) {
                out[i] = c.getDouble(src);
            }
        }
        return new DoubleColumn(newColName, out);
    }

    /** at_time:返回行时间 == time 的行(非 LocalDateTime 行静默跳过,对齐 pandas)。 */
    static DataFrame atTime(DataFrame df, String tsCol, java.time.LocalTime time) {
        List<Integer> picked = new ArrayList<>();
        for (int i = 0; i < df.rowCount(); i++) {
            Object v = df.get(i, tsCol);
            if (v instanceof java.time.LocalDateTime lt && lt.toLocalTime().equals(time)) {
                picked.add(i);
            }
        }
        return df.takeRows(picked.stream().mapToInt(Integer::intValue).toArray());
    }

    /** between_time:行时间 ∈ [start, end](start > end 跨午夜)。 */
    static DataFrame betweenTime(DataFrame df, String tsCol, java.time.LocalTime start, java.time.LocalTime end) {
        boolean crossMidnight = start.isAfter(end);
        List<Integer> picked = new ArrayList<>();
        for (int i = 0; i < df.rowCount(); i++) {
            Object v = df.get(i, tsCol);
            if (!(v instanceof java.time.LocalDateTime lt)) continue;
            java.time.LocalTime t = lt.toLocalTime();
            boolean inRange = crossMidnight
                ? (t.compareTo(start) >= 0 || t.compareTo(end) <= 0)
                : (t.compareTo(start) >= 0 && t.compareTo(end) <= 0);
            if (inRange) picked.add(i);
        }
        return df.takeRows(picked.stream().mapToInt(Integer::intValue).toArray());
    }

    /** asof:≤ label 的最后一个非空观测行(全扫描,不依赖升序)。 */
    static DataFrame asof(DataFrame df, String tsCol, java.time.LocalDateTime label) {
        int found = -1;
        for (int i = 0; i < df.rowCount(); i++) {
            Object v = df.get(i, tsCol);
            if (v instanceof java.time.LocalDateTime lt && !lt.isAfter(label)) {
                found = i;   // 全扫描:取最后一个 ≤ label 的行
            }
        }
        if (found < 0) return df.takeRows(new int[0]);
        return df.takeRows(new int[]{found});
    }

    /** tz_localize:DATETIME 列挂时区 → OBJECT 列(ZonedDateTime 元素)。 */
    static DataFrame tzLocalize(DataFrame df, String colName, String zoneId) {
        java.time.ZoneId zid = java.time.ZoneId.of(zoneId);
        Column src = df.getColumn(colName);
        Object[] arr = new Object[src.size()];
        for (int i = 0; i < src.size(); i++) {
            if (src.isNull(i)) { arr[i] = null; continue; }
            Object v = src.get(i);
            if (v instanceof java.time.LocalDateTime lt) arr[i] = lt.atZone(zid);
            else arr[i] = v;
        }
        Column newCol = new ObjectColumn(colName + "_tz", arr);
        List<Column> newCols = new ArrayList<>(df.columnsInternal());
        newCols.set(df.columnIndex(colName), newCol.rename(colName));
        return df.rebuild(newCols, df.index());
    }

    /** tz_convert:转换 ZonedDateTime 列的时区(要求先 tz_localize,否则 IAE)。 */
    static DataFrame tzConvert(DataFrame df, String colName, String zoneId) {
        java.time.ZoneId target = java.time.ZoneId.of(zoneId);
        Column src = df.getColumn(colName);
        Object[] arr = new Object[src.size()];
        for (int i = 0; i < src.size(); i++) {
            if (src.isNull(i)) { arr[i] = null; continue; }
            Object v = src.get(i);
            if (v instanceof java.time.ZonedDateTime zdt) {
                arr[i] = zdt.withZoneSameInstant(target);
            } else {
                throw new IllegalArgumentException("tz_convert 要求列 " + colName
                    + " 含 ZonedDateTime(先 tz_localize);实际第 " + i + " 行:" + (v == null ? "null" : v.getClass().getSimpleName()));
            }
        }
        List<Column> newCols = new ArrayList<>(df.columnsInternal());
        newCols.set(df.columnIndex(colName), new ObjectColumn(colName, arr));
        return df.rebuild(newCols, df.index());
    }
}
