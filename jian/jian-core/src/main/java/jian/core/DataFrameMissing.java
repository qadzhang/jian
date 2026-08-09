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

    /**
     * 是否每行为缺失(对齐 pandas df.isna,逐单元格;此处返回 mask DataFrame)。
     * @param df DataFrame 目标表,非 null
     * @return DataFrame 同结构,每单元格为 BoolColumn(true=该格缺失,false=有值);结果本身无缺失
     */
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
     * @param df     DataFrame 目标表,非 null
     * @param how    String 丢弃策略:"any"=任一指定列缺失即丢;"all"=全部指定列缺失才丢;非 null
     * @param subset String[] 仅考虑这些列名;null=全部列;数组中列名必须存在
     * @return DataFrame 删除缺失行后的新表(行数 ≤ df.rowCount();列不变)
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
     * 填充缺失(对齐 pandas df.fillna,统一填同一值)。
     * @param df    DataFrame 目标表,非 null
     * @param value Object 填充值:数值列期望 Number;字符串列期望 String/任意(toString);非 null
     * @return DataFrame 同结构,所有缺失单元格替换为 value;类型转换按列 dtype 自动适配
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

    /**
     * 前向填充(对齐 pandas ffill):用前一非空值填当前空。**首行若为空,保持空**。
     * @param df DataFrame 目标表,非 null
     * @return DataFrame 同结构,每列缺失行填前一个非空值;首段缺失保持缺失
     */
    public static DataFrame ffill(DataFrame df) {
        List<Column> out = new ArrayList<>();
        for (Column c : df.columnsInternal()) out.add(ffillColumn(c));
        return df.rebuild(out, df.index());
    }

    /**
     * 后向填充(对齐 pandas bfill):用后一非空值填当前空。**末行若为空,保持空**。
     * @param df DataFrame 目标表,非 null
     * @return DataFrame 同结构,每列缺失行填后一个非空值;末段缺失保持缺失
     */
    public static DataFrame bfill(DataFrame df) {
        List<Column> out = new ArrayList<>();
        for (Column c : df.columnsInternal()) out.add(bfillColumn(c));
        return df.rebuild(out, df.index());
    }

    /**
     * 列级 ffill:基于 Object[] 中转。
     * 修复:用 isNull(i) 替代 get()==null 判断缺失——
     * DoubleColumn.get(NaN) 现在返回 Double.NaN(不是 null),get()==null 不再识别缺失。
     */
    private static Column ffillColumn(Column c) {
        int n = c.size();
        Object[] d = new Object[n];
        Object last = null;
        for (int i = 0; i < n; i++) {
            if (c.isNull(i)) {
                d[i] = last;
            } else {
                Object v = c.get(i);
                d[i] = v;
                last = v;
            }
        }
        return rebuildFromObjects(c, d);
    }

    /**
     * 列级 bfill(同 ffill,从后往前)。同样用 isNull 替代 get()==null。
     */
    private static Column bfillColumn(Column c) {
        int n = c.size();
        Object[] d = new Object[n];
        Object next = null;
        for (int i = n - 1; i >= 0; i--) {
            if (c.isNull(i)) {
                d[i] = next;
            } else {
                Object v = c.get(i);
                d[i] = v;
                next = v;
            }
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

    // ======================== 阶段 A 掩码与值替换(2026-08-09,与 isna/fillna 同源)========================

    // ┌─ What : isin / colIsin / where / mask —— 条件掩码生成与反向填充
    // │  Why  : 与 isna(掩码)/fillna(值替换)同源语义,按 AGENTS.md §3.1.1.1 内聚到此
    // │  Who  : 由 DataFrame.isin / colIsin / where / mask 单行委托
    // │  When : 2026-08-09 阶段 A
    // │  How  : isin 用 HashSet O(n+m);where(cond,other) 是 cond==false 替换;mask 是 cond==true 替换

    /**
     * 行级成员判断(对齐 pandas DataFrame.isin):任一列的值在 values 中,该行返回 true。
     * <p><b>数值比较语义</b>:Number 类型的值走 {@code doubleValue() ==}(数值相等),
     * 避免 IEEE 754 +0.0/-0.0、Integer/Long/Double 跨类型导致 equals 失配(对齐 pandas 数值隐式比较)。
     * @param df     DataFrame 目标表,非 null
     * @param values Object[] 候选值;null 元素忽略
     * @return boolean[] 长度 == rowCount();true 表示该行至少一列命中
     */
    public static boolean[] isin(DataFrame df, Object[] values) {
        int n = df.rowCount();
        // 拆 values 为数值桶 + 对象桶,数值走 doubleValue 比较,对象走 equals
        double[] numValues = new double[values.length];
        int numCount = 0;
        java.util.Set<Object> objSet = new java.util.HashSet<>();
        for (Object v : values) {
            if (v == null) continue;
            if (v instanceof Number num) {
                numValues[numCount++] = num.doubleValue();
            } else {
                objSet.add(v);
            }
        }
        boolean[] out = new boolean[n];
        java.util.List<String> cols = df.columnNames();
        for (int i = 0; i < n; i++) {
            for (String c : cols) {
                Object v = df.get(i, c);
                if (v == null) continue;
                if (v instanceof Number num) {
                    double dv = num.doubleValue();
                    for (int k = 0; k < numCount; k++) {
                        if (numValues[k] == dv) { out[i] = true; break; }
                    }
                } else if (objSet.contains(v)) {
                    out[i] = true;
                }
                if (out[i]) break;
            }
        }
        return out;
    }

    /**
     * 列级成员判断(对齐 pandas Series.isin):某行该列值在 values 中则 true。
     * 数值走 {@code doubleValue() ==}(同 isin 语义)。
     */
    public static boolean[] colIsin(DataFrame df, String col, Object[] values) {
        Column c = df.getColumn(col);
        int n = df.rowCount();
        double[] numValues = new double[values.length];
        int numCount = 0;
        java.util.Set<Object> objSet = new java.util.HashSet<>();
        for (Object v : values) {
            if (v == null) continue;
            if (v instanceof Number num) numValues[numCount++] = num.doubleValue();
            else objSet.add(v);
        }
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            Object v = c.get(i);
            if (v == null) continue;
            if (v instanceof Number num) {
                double dv = num.doubleValue();
                for (int k = 0; k < numCount; k++) {
                    if (numValues[k] == dv) { out[i] = true; break; }
                }
            } else {
                out[i] = objSet.contains(v);
            }
        }
        return out;
    }

    /**
     * 条件保留(对齐 pandas DataFrame.where):cond==false 处用 other 替换;cond==true 保留原值。
     * @param df    DataFrame,非 null
     * @param cond  boolean[] 行掩码,长度 == rowCount()
     * @param other Object 替换值(类型需兼容列 dtype)
     * @return DataFrame 新表
     */
    public static DataFrame where(DataFrame df, boolean[] cond, Object other) {
        return fillByCond(df, cond, other, false);
    }

    /**
     * 条件替换(对齐 pandas DataFrame.mask):cond==true 处用 other 替换。与 {@link #where} 互补。
     */
    public static DataFrame mask(DataFrame df, boolean[] cond, Object other) {
        return fillByCond(df, cond, other, true);
    }

    /** 通用按行条件填充。fillOnTrue=true 是 mask 语义;false 是 where 语义。 */
    private static DataFrame fillByCond(DataFrame df, boolean[] cond, Object other, boolean fillOnTrue) {
        int n = df.rowCount();
        if (cond.length != n) {
            throw new IllegalArgumentException(
                (fillOnTrue ? "mask" : "where") + " cond 长度 " + cond.length + " ≠ rowCount " + n);
        }
        java.util.List<Column> newCols = new java.util.ArrayList<>(df.columnCount());
        for (String name : df.columnNames()) {
            Column src = df.getColumn(name);
            Object[] arr = new Object[n];
            for (int i = 0; i < n; i++) {
                boolean fill = fillOnTrue ? cond[i] : !cond[i];
                arr[i] = fill ? other : src.get(i);
            }
            newCols.add(toColumnByDtype(name, arr, src.dtype()));
        }
        return df.rebuild(newCols, df.index());
    }

    /** 按 dtype 把 Object[] 转回具体 Column(与 ffillColumn 同模式)。 */
    private static Column toColumnByDtype(String name, Object[] arr, DType dtype) {
        switch (dtype) {
            case DOUBLE: {
                double[] d = new double[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    if (arr[i] == null) d[i] = Double.NaN;
                    else if (arr[i] instanceof Number num) d[i] = num.doubleValue();
                    else d[i] = Double.parseDouble(arr[i].toString());
                }
                return new DoubleColumn(name, d);
            }
            case LONG: {
                long[] l = new long[arr.length];
                boolean[] mask = new boolean[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    if (arr[i] == null) mask[i] = true;
                    else l[i] = ((Number) arr[i]).longValue();
                }
                return new LongColumn(name, l, mask);
            }
            case INT: {
                int[] v = new int[arr.length];
                boolean[] mask = new boolean[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    if (arr[i] == null) mask[i] = true;
                    else v[i] = ((Number) arr[i]).intValue();
                }
                return new IntColumn(name, v, mask);
            }
            case STRING: {
                String[] s = new String[arr.length];
                for (int i = 0; i < arr.length; i++) s[i] = arr[i] == null ? null : String.valueOf(arr[i]);
                return new StringColumn(name, s);
            }
            case BOOL: {
                boolean[] b = new boolean[arr.length];
                boolean[] mask = new boolean[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    if (arr[i] == null) mask[i] = true;
                    else b[i] = (Boolean) arr[i];
                }
                return new BoolColumn(name, b, mask);
            }
            default:
                return new ObjectColumn(name, arr);
        }
    }

    // ======================== 阶段 F 缺失值扩展(2026-08-09)========================

    // ┌─ What : interpolate —— 线性插值(对齐 pandas Series.interpolate(method="linear"))
    // │  Why  : 与 fillna/ffill/bfill 同源(都是缺失值填充),按 §3.1.1.1 内聚到 DataFrameMissing
    // │  How  : 找缺失位置,取前后最近非缺失值,按线性比例插值;首尾缺失保持缺失
    /**
     * 线性插值填充缺失值(对齐 pandas Series.interpolate(method="linear"))。
     * <p>策略:对每个缺失位置,找前一个非缺失值 prev 和后一个非缺失值 next,按线性比例插值。
     * 首/尾连续缺失(无 prev 或无 next)保持缺失。
     * @param df DataFrame 目标表,非 null
     * @return DataFrame 同结构,数值列的缺失位置被线性插值填上
     */
    public static DataFrame interpolate(DataFrame df) {
        java.util.List<Column> out = new java.util.ArrayList<>();
        for (Column c : df.columnsInternal()) {
            if (c.dtype() == DType.DOUBLE) {
                out.add(interpolateDoubleColumn(c));
            } else if (c.dtype() == DType.LONG || c.dtype() == DType.INT) {
                DoubleColumn dc = interpolateDoubleColumn(toDoubleCol(c));
                out.add(dc);
            } else {
                out.add(c);
            }
        }
        return df.rebuild(out, df.index());
    }

    /** 对数值 Column 做线性插值。 */
    private static DoubleColumn interpolateDoubleColumn(Column c) {
        int n = c.size();
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = c.getDouble(i);
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(d[i])) continue;
            int prev = i - 1;
            while (prev >= 0 && Double.isNaN(d[prev])) prev--;
            int next = i + 1;
            while (next < n && Double.isNaN(d[next])) next++;
            if (prev < 0 || next >= n) continue;
            double v1 = d[prev], v2 = d[next];
            int span = next - prev;
            for (int k = prev + 1; k < next; k++) {
                double frac = (double) (k - prev) / span;
                d[k] = v1 + (v2 - v1) * frac;
            }
        }
        return new DoubleColumn(c.name(), d);
    }

    /** 把任意数值 Column 转 DoubleColumn(便于插值统一处理)。 */
    private static DoubleColumn toDoubleCol(Column c) {
        double[] d = new double[c.size()];
        for (int i = 0; i < c.size(); i++) {
            d[i] = c.isNull(i) ? Double.NaN : c.getDouble(i);
        }
        return new DoubleColumn(c.name(), d);
    }
}
