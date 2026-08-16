package jian.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

// ┌─ What : DataFrameMeta —— 元信息与矩阵(info/selectDtypes/memoryUsage/corr-cov 矩阵/notna,从 DataFrame.java 拆出)
// │  Why  : 落实 §3.1 ≤600 行红线;元信息簇 ~120 行自包含。
// │  Who  : 由 DataFrame.info/selectDtypes/memoryUsage/corrMatrix/covMatrix/notna 委托调用
// │  When : 表元信息查看/按 dtype 筛列/内存估算/相关协方差矩阵/非缺失掩码
// │  Where: jian-core/DataFrameMeta.java
// │  How  : info:逐列统计非空+内存,格式化表格(Locale.ROOT);memoryUsage:按 dtype 估算(STRING 列
// │         40 头+均长×2,不按一律 8B);buildMatrix:数值列两两 corr/cov → 方阵;notna:isna 反转。
final class DataFrameMeta {
    private DataFrameMeta() {}

    /** info 实现:可读性表格(列名/dtype/非空/内存)。 */
    static String infoImpl(DataFrame df) {
        StringBuilder sb = new StringBuilder();
        sb.append("<DataFrame: ").append(df.rowCount()).append(" 行 × ").append(df.columnCount()).append(" 列>\n");
        sb.append(String.format(java.util.Locale.ROOT, " %-3s %-12s %-11s %5s   %s%n", "#", "列名", "dtype", "非空", "内存"));
        long totalMem = 0;
        List<String> cols = df.columnNames();
        for (int i = 0; i < cols.size(); i++) {
            Column c = df.getColumn(cols.get(i));
            int nonNull = c.size() - c.nullCount();
            long mem = (long) c.size() * 8;
            totalMem += mem;
            String nm = cols.get(i).length() <= 12 ? cols.get(i) : cols.get(i).substring(0, 11) + "…";
            sb.append(String.format(java.util.Locale.ROOT, " %-3d %-12s %-11s %5d   %6d B%n", i, nm, c.dtype(), nonNull, mem));
        }
        sb.append("总内存估算: ").append(totalMem).append(" B");
        return sb.toString();
    }

    /** selectDtypes 实现:按 dtype 筛列(include/exclude 任一为 null 表示不限)。 */
    /** selectBy 实现(§3.1 行数下沉):谓词命中列按原列序 select。 */
    static DataFrame selectByImpl(DataFrame df, java.util.function.Predicate<String> columnPredicate) {
        java.util.List<String> hit = new java.util.ArrayList<>();
        for (String name : df.columnNames()) {
            if (columnPredicate.test(name)) hit.add(name);
        }
        return df.select(hit.toArray(new String[0]));
    }

    static DataFrame selectDtypesImpl(DataFrame df, DType[] include, DType[] exclude) {
        Set<DType> inc = include == null ? null : EnumSet.noneOf(DType.class);
        if (inc != null) inc.addAll(Arrays.asList(include));
        Set<DType> exc = exclude == null ? null : EnumSet.noneOf(DType.class);
        if (exc != null) exc.addAll(Arrays.asList(exclude));
        List<String> kept = new ArrayList<>();
        for (String c : df.columnNames()) {
            DType dt = df.getColumn(c).dtype();
            if (inc != null && !inc.contains(dt)) continue;
            if (exc != null && exc.contains(dt)) continue;
            kept.add(c);
        }
        return kept.isEmpty() ? df.drop(df.columnNames().toArray(new String[0]))
                              : df.select(kept.toArray(new String[0]));
    }

    /** 内存估算(按 dtype 估算,STRING 列 40 头+均长×2,不按一律 8B)。 */
    static long memoryUsage(DataFrame df) {
        long total = 0;
        for (String c : df.columnNames()) {
            Column col = df.getColumn(c);
            int n = col.size();
            long per = switch (col.dtype()) {
                case DOUBLE, LONG, DATETIME -> 8L;
                case INT, DATE -> 4L;
                case BOOL -> 1L;
                case STRING -> {
                    long sum = 0; int cnt = 0;
                    for (int i = 0; i < n; i++) {
                        if (!col.isNull(i)) { sum += col.get(i).toString().length(); cnt++; }
                    }
                    long avg = cnt == 0 ? 0 : sum / cnt;
                    yield 40L + avg * 2L;
                }
                default -> 40L;   // OBJECT/CATEGORY:对象引用估算
            };
            total += per * n;
        }
        return total;
    }

    /**
     * 构建全数值列的相关/协方差矩阵(kind="corr"/"cov";corr 用 method=pearson/spearman)。
     * <p>对角线走 corr(x,x)/cov(x,x) 自然计算(对齐 pandas:pandas 1.5.3 实测常数列 corr 对角线
     * 为 NaN——零方差 0/0,正常列为 1.0;cov 对角线=各列方差;不写死"恒 1.0"),由回归测试锁定。
     */
    static DataFrame buildMatrix(DataFrame df, String kind, String method) {
        List<String> numCols = new ArrayList<>();
        for (String c : df.columnNames()) {
            DType dt = df.getColumn(c).dtype();
            if (dt == DType.DOUBLE || dt == DType.LONG || dt == DType.INT) numCols.add(c);
        }
        int k = numCols.size();
        if (k == 0) return DataFrame.ofColumnsDirect(new ArrayList<>());
        String labelCol = "_index_";
        Object[] schParts = new Object[2 + k * 2];
        schParts[0] = labelCol; schParts[1] = DType.STRING;
        for (int j = 0; j < k; j++) { schParts[2 + j * 2] = numCols.get(j); schParts[3 + j * 2] = DType.DOUBLE; }
        Schema sch = Schema.of(schParts);
        Object[][] rows = new Object[k][];
        for (int i = 0; i < k; i++) {
            Object[] row = new Object[k + 1];
            row[0] = numCols.get(i);
            for (int j = 0; j < k; j++) {
                double v = "corr".equals(kind)
                    ? DataFrameStats.corr(df.getColumn(numCols.get(i)), df.getColumn(numCols.get(j)), method)
                    : DataFrameStats.cov(df.getColumn(numCols.get(i)), df.getColumn(numCols.get(j)));
                row[j + 1] = v;
            }
            rows[i] = row;
        }
        return DataFrame.of(sch, rows);
    }

    /** notna:isna 的反转(返回非缺失掩码 DataFrame,对齐 pandas df.notna)。 */
    static DataFrame notna(DataFrame df) {
        DataFrame na = df.isna();
        List<Column> out = new ArrayList<>();
        for (String c : na.columnNames()) {
            BoolColumn bc = (BoolColumn) na.getColumn(c);
            boolean[] inv = new boolean[bc.size()];
            boolean[] mask = new boolean[bc.size()];
            for (int i = 0; i < bc.size(); i++) {
                if (bc.isNull(i)) { mask[i] = true; }
                else inv[i] = !Boolean.TRUE.equals(bc.get(i));
            }
            out.add(new BoolColumn(c, inv, mask));
        }
        return df.rebuild(out, df.index());
    }

    /** 首个全非空行下标(无则 empty;主类委托,P3 拆分)。 */
    static OptionalInt firstValidIndex(DataFrame df) {
        for (int i = 0; i < df.rowCount(); i++) {
            for (String c : df.columnNames()) {
                if (!df.getColumn(c).isNull(i)) return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /** 最后一个全非空行下标(无则 empty;主类委托,P3 拆分)。 */
    static OptionalInt lastValidIndex(DataFrame df) {
        for (int i = df.rowCount() - 1; i >= 0; i--) {
            for (String c : df.columnNames()) {
                if (!df.getColumn(c).isNull(i)) return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /** 转二维数组(对齐 pandas df.to_numpy;主类委托,P3 拆分)。 */
    static Object[][] toNumpy(DataFrame df) {
        Object[][] out = new Object[df.rowCount()][];
        for (int r = 0; r < out.length; r++) out[r] = df.getRow(r);
        return out;
    }
}
