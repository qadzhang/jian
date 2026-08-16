package jian.core;

import java.util.ArrayList;
import java.util.List;

// ┌─ What : DataFrameTypes —— 列类型推断(inferObjects/inferColumnDtype/inferColumnFromArray,从 DataFrame.java 拆出)
// │  Why  : 落实 §3.1 ≤600 行红线;推断簇 ~90 行自包含(BigInteger 溢出保护语义集中在此)。
// │  Who  : 由 DataFrame.inferObjects 委托;inferColumnFromArray 包级供 insert/resetIndexImpl/applyRowImpl 调
// │  When : OBJECT 列类型升级(infer_objects)/ 新列类型推断(insert/applyRow/resetIndex)
// │  Where: jian-core/DataFrameTypes.java
// │  How  : 数据走向:Column → toObjectArray → 扫描 flag(allLong/allDouble/allString/allBool + hasBigOverflow)
// │           → DataFrameConvert.convertColumn 装目标 dtype;超出 long 范围的 BigInteger/BigDecimal 保留 OBJECT(防截断)。
final class DataFrameTypes {
    private DataFrameTypes() {}

    /**
     * 数值列 → long[] 视图(LONG 直返底层数组;INT 宽化拷贝;其它类型抛 IAE)。
     * 由 GroupBy / DataFrameMerge 两份同体实现收敛至此(单一事实来源)。
     * @param col Column 数值列,非 null
     * @return long[] LONG 列为零拷贝视图(INT 为新数组),调用方不得修改
     * @throws IllegalArgumentException 列非 LONG/INT
     */
    static long[] columnToLongArray(Column col) {
        if (col instanceof LongColumn lc) return lc.dataInPlace();
        if (col instanceof IntColumn ic) {
            int[] src = ic.dataInPlace();
            long[] out = new long[src.length];
            for (int i = 0; i < src.length; i++) out[i] = src[i];
            return out;
        }
        throw new IllegalArgumentException("join/group 键要求 LONG/INT 列,实际:" + col.dtype() + "(" + col.name() + ")");
    }

    /** 类型推断升级(对齐 pandas df.infer_objects):OBJECT 列 → LONG/DOUBLE/STRING/BOOL。 */
    static DataFrame inferObjects(DataFrame df) {
        List<Column> newCols = new ArrayList<>();
        for (String c : df.columnNames()) {
            Column col = df.getColumn(c);
            newCols.add(col.dtype() == DType.OBJECT ? inferColumnDtype(col) : col);
        }
        return df.rebuild(newCols, df.index());
    }

    /**
     * 推断单列最窄 dtype(BigInteger/BigDecimal 纳入整数判定,超 long 范围保留 OBJECT,
     * 防止 num.longValue() 静默截断丢数据)。
     */
    private static Column inferColumnDtype(Column src) {
        Object[] vals = src.toObjectArray();
        boolean allLong = true, allDouble = true, allString = true, allBool = true;
        boolean hasBigOverflow = false;
        for (Object v : vals) {
            if (v == null) continue;
            if (!isIntegral(v)) allLong = false;
            if (isBigOverflow(v)) hasBigOverflow = true;
            if (!(v instanceof Number)) allDouble = false;
            if (!(v instanceof String)) allString = false;
            if (!(v instanceof Boolean)) allBool = false;
        }
        if (allBool) return DataFrameConvert.convertColumn(src, DType.BOOL);
        if (allLong) return DataFrameConvert.convertColumn(src, DType.LONG);
        if (allDouble && !hasBigOverflow) return DataFrameConvert.convertColumn(src, DType.DOUBLE);
        if (allString) return DataFrameConvert.convertColumn(src, DType.STRING);
        return src;
    }

    /** 是否可无损转 long(Integer/Long/范围内的 BigInteger/BigDecimal)。 */
    private static boolean isIntegral(Object v) {
        if (v instanceof Integer || v instanceof Long) return true;
        if (v instanceof java.math.BigInteger bi) {
            try { bi.longValueExact(); return true; } catch (ArithmeticException e) { return false; }
        }
        if (v instanceof java.math.BigDecimal bd) {
            try { bd.longValueExact(); return true; } catch (ArithmeticException e) { return false; }
        }
        return false;
    }

    /** 是否超出 long 范围(转 LONG/DOUBLE 都会失真)。 */
    private static boolean isBigOverflow(Object v) {
        if (v instanceof java.math.BigInteger bi) {
            try { bi.longValueExact(); return false; } catch (ArithmeticException e) { return true; }
        }
        if (v instanceof java.math.BigDecimal bd) {
            try { bd.longValueExact(); return false; } catch (ArithmeticException e) { return true; }
        }
        return false;
    }

    /**
     * 从 Object[] 推断 Column(全 Long/Integer → LONG;全 Number → DOUBLE;全 Boolean → BOOL;
     * 全 LocalDate → DATE;全 LocalDateTime → DATETIME;全 String → STRING;否则 OBJECT)。
     * 包级供 insert/resetIndex/applyRow 用。
     * <p>含 BOOL/DATE/DATETIME 三类推断(与 {@link Schema#infer} 口径对齐;
     * 只推 LONG/DOUBLE/STRING 会让 df.insert(0,"flag",new Object[]{true,false}) 得 OBJECT 列)。
     */
    static Column inferColumnFromArray(String name, Object[] arr) {
        boolean allLong = true, allDouble = true, allString = true;
        boolean allBool = true, allDate = true, allDateTime = true;
        for (Object v : arr) {
            if (v == null) continue;
            if (!(v instanceof Integer) && !(v instanceof Long)) allLong = false;
            if (!(v instanceof Number)) allDouble = false;
            if (!(v instanceof String)) allString = false;
            if (!(v instanceof Boolean)) allBool = false;
            if (!(v instanceof java.time.LocalDate)) allDate = false;
            if (!(v instanceof java.time.LocalDateTime)) allDateTime = false;
        }
        if (allLong) {
            long[] l = new long[arr.length];
            boolean[] mask = new boolean[arr.length];
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == null) mask[i] = true;
                else l[i] = ((Number) arr[i]).longValue();
            }
            return new LongColumn(name, l, mask);
        }
        if (allDouble) {
            double[] d = new double[arr.length];
            for (int i = 0; i < arr.length; i++) d[i] = arr[i] == null ? Double.NaN : ((Number) arr[i]).doubleValue();
            return new DoubleColumn(name, d);
        }
        // BOOL(Boolean[],null 进 nullMask)
        if (allBool) {
            boolean[] b = new boolean[arr.length];
            boolean[] mask = new boolean[arr.length];
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == null) mask[i] = true;
                else b[i] = (Boolean) arr[i];
            }
            return new BoolColumn(name, b, mask);
        }
        // DATETIME(LocalDateTime[];先于 DATE 判 —— LocalDate 非 LocalDateTime 子类,互不干扰)
        if (allDateTime) {
            java.time.LocalDateTime[] d = new java.time.LocalDateTime[arr.length];
            for (int i = 0; i < arr.length; i++) d[i] = (java.time.LocalDateTime) arr[i];
            return new DateTimeColumn(name, d);
        }
        // DATE(LocalDate[])
        if (allDate) {
            java.time.LocalDate[] d = new java.time.LocalDate[arr.length];
            for (int i = 0; i < arr.length; i++) d[i] = (java.time.LocalDate) arr[i];
            return new DateColumn(name, d);
        }
        if (allString) {
            String[] s = new String[arr.length];
            for (int i = 0; i < arr.length; i++) s[i] = arr[i] == null ? null : String.valueOf(arr[i]);
            return new StringColumn(name, s);
        }
        return new ObjectColumn(name, arr);
    }
}
