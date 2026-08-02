package jian.core;

import java.util.ArrayList;
import java.util.List;

// ┌─ What : DataFrameMissing —— DataFrame 缺失值处理(对齐 pandas §3.8:isna/dropna/fillna/ffill/bfill)
// │  Why  : 规范要求统一缺失处理;companion 模式避免 DataFrame 主类超长
// │  Who  : DataFrame 的 isna/dropna/fillna 委托此类
// │  When : 数据清洗
// │  Where: jian-core/DataFrameMissing.java
// │  How  : 数据走向:逐列按其缺失约定(NaN/null/nullMask)生成 mask → 行级 drop 或 fill。
// │         关键变量变化:
// │           - mask:布尔数组,true 表示该行/单元格缺失;
// │           - how=any:任一指定列缺失即 drop;how=all:全部缺失才 drop。
/**
 * DataFrame 缺失值处理,对齐 pandas 的 isna/dropna/fillna/ffill/bfill。
 */
public final class DataFrameMissing {

    private DataFrameMissing() {}

    /** 是否每行为缺失(对齐 pandas df.isna,逐单元格;此处返回 mask DataFrame)。 */
    public static DataFrame isna(DataFrame df) {
        List<Column> cols = new ArrayList<>();
        for (Column c : df.columnsInternal()) {
            int n = c.size();
            boolean[] mask = new boolean[n];
            boolean[] nullMask = new boolean[n];
            for (int i = 0; i < n; i++) {
                mask[i] = c.isNull(i);
                nullMask[i] = false;  // isna 结果本身无缺失
            }
            cols.add(new BoolColumn(c.name(), mask, nullMask));
        }
        return df.rebuild(cols, df.index());
    }

    /**
     * 丢弃缺失行(对齐 pandas df.dropna)。
     *
     * @param how "any" 任一列缺失即丢;"all" 全部缺失才丢
     * @param subset 仅考虑这些列(null = 全部列)
     */
    public static DataFrame dropna(DataFrame df, String how, String[] subset) {
        int n = df.rowCount();
        List<Column> all = df.columnsInternal();
        List<Integer> targetCols = new ArrayList<>();
        if (subset == null) {
            for (int i = 0; i < all.size(); i++) targetCols.add(i);
        } else {
            for (String name : subset) {
                int idx = df.columnIndex(name);
                if (idx < 0) throw new IllegalArgumentException("subset 列不存在:" + name);
                targetCols.add(idx);
            }
        }
        boolean[] keep = new boolean[n];
        for (int r = 0; r < n; r++) {
            boolean anyMissing = false;
            boolean allMissing = true;
            for (int ci : targetCols) {
                boolean missing = all.get(ci).isNull(r);
                if (missing) anyMissing = true;
                else allMissing = false;
            }
            keep[r] = how.equals("any") ? !anyMissing : !allMissing;
        }
        return df.filter(keep);
    }

    /**
     * 用常量填充缺失(对齐 pandas df.fillna(value))。
     *
     * @param value 数值列填 Number;字符串列填 String;按列类型自动适配。
     */
    public static DataFrame fillna(DataFrame df, Object value) {
        List<Column> out = new ArrayList<>();
        for (Column c : df.columnsInternal()) {
            out.add(fillColumn(c, value));
        }
        return df.rebuild(out, df.index());
    }

    private static Column fillColumn(Column c, Object value) {
        int n = c.size();
        switch (c.dtype()) {
            case DOUBLE: {
                double[] d = new double[n];
                double fv = value instanceof Number ? ((Number) value).doubleValue() : 0.0;
                for (int i = 0; i < n; i++) d[i] = c.isNull(i) ? fv : c.getDouble(i);
                return new DoubleColumn(c.name(), d);
            }
            case LONG: {
                long[] d = new long[n];
                boolean[] mask = new boolean[n];
                long fv = value instanceof Number ? ((Number) value).longValue() : 0L;
                for (int i = 0; i < n; i++) {
                    if (c.isNull(i)) { d[i] = fv; }
                    else { d[i] = c.getLong(i); }
                }
                return new LongColumn(c.name(), d, mask);
            }
            case INT: {
                int[] d = new int[n];
                boolean[] mask = new boolean[n];
                int fv = value instanceof Number ? ((Number) value).intValue() : 0;
                for (int i = 0; i < n; i++) {
                    if (c.isNull(i)) { d[i] = fv; }
                    else { d[i] = (int) c.getLong(i); }
                }
                return new IntColumn(c.name(), d, mask);
            }
            case STRING: {
                String[] d = new String[n];
                String fv = value == null ? "" : value.toString();
                for (int i = 0; i < n; i++) d[i] = c.isNull(i) ? fv : (String) c.get(i);
                return new StringColumn(c.name(), d);
            }
            case BOOL: {
                boolean[] d = new boolean[n];
                boolean[] mask = new boolean[n];
                boolean fv = value instanceof Boolean ? (Boolean) value : false;
                for (int i = 0; i < n; i++) {
                    if (c.isNull(i)) { d[i] = fv; }
                    else d[i] = ((BoolColumn) c).getBool(i);
                }
                return new BoolColumn(c.name(), d, mask);
            }
            case DATETIME:
            case DATE:
            case OBJECT:
            default: {
                Object[] d = new Object[n];
                for (int i = 0; i < n; i++) d[i] = c.isNull(i) ? value : c.get(i);
                return new ObjectColumn(c.name(), d);
            }
        }
    }

    /** 前向填充(对齐 pandas ffill):用前一非空值填当前空。 */
    public static DataFrame ffill(DataFrame df) {
        List<Column> out = new ArrayList<>();
        for (Column c : df.columnsInternal()) out.add(ffillColumn(c));
        return df.rebuild(out, df.index());
    }

    /** 后向填充(对齐 pandas bfill):用后一非空值填当前空。 */
    public static DataFrame bfill(DataFrame df) {
        List<Column> out = new ArrayList<>();
        for (Column c : df.columnsInternal()) out.add(bfillColumn(c));
        return df.rebuild(out, df.index());
    }

    /** 列级 ffill:基于 Object[] 中转(实现简单;性能不是 M1 关注点)。 */
    private static Column ffillColumn(Column c) {
        int n = c.size();
        Object[] d = new Object[n];
        Object last = null;
        for (int i = 0; i < n; i++) {
            Object v = c.get(i);
            d[i] = v == null ? last : v;
            if (v != null) last = v;
        }
        return rebuildFromObjects(c, d);
    }

    private static Column bfillColumn(Column c) {
        int n = c.size();
        Object[] d = new Object[n];
        Object next = null;
        for (int i = n - 1; i >= 0; i--) {
            Object v = c.get(i);
            d[i] = v == null ? next : v;
            if (v != null) next = v;
        }
        return rebuildFromObjects(c, d);
    }

    /** 从 Object[] 按原列 dtype 重建(数值还原为数值列)。 */
    private static Column rebuildFromObjects(Column c, Object[] d) {
        switch (c.dtype()) {
            case DOUBLE: {
                double[] a = new double[d.length];
                for (int i = 0; i < d.length; i++) a[i] = d[i] == null ? Double.NaN : ((Number) d[i]).doubleValue();
                return new DoubleColumn(c.name(), a);
            }
            case STRING: {
                String[] a = new String[d.length];
                for (int i = 0; i < d.length; i++) a[i] = (String) d[i];
                return new StringColumn(c.name(), a);
            }
            case OBJECT: default:
                return new ObjectColumn(c.name(), d);
        }
    }
}
