package jian.core;

// ┌─ What : DataFrameConvert —— 列 dtype 转换(从 DataFrame.java 拆出,落实 §3.1 ≤600 行红线)
// │  Why  : convertColumn 是 116 行巨型 switch(8 个 dtype 分支),自包含,被 astype/typed accessor/
// │         isetitem/inferColumnDtype 共 8 处调用,适合独立成伴生类。
// │  Who  : 由 DataFrame.astype/getDoubleColumn/getLongColumn/getIntColumn/isetitem/inferColumnDtype 调用
// │  When : 任何 dtype 转换场景(astype / 类型推断 / typed accessor)
// │  Where: jian-core/DataFrameConvert.java
// │  How  : 数据走向:Column → toObjectArray() → 按 target dtype 装 primitive 数组(缺失行 NaN/0/null)
// │           → 新 Column 子类。
// │         关键变量:vals(Object[] 中转)、mask(INT/LONG/BOOL 缺失标记)、target(switch 分发)。
// │         逻辑路线:DOUBLE→double[];LONG/INT→long[]/int[]+mask(BigInteger/BigDecimal longValueExact 防溢出);
// │           BOOL→接受 Boolean/"true"/0/1;DATETIME/DATE→LocalDateTime/LocalDate/ISO String 解析。
final class DataFrameConvert {
    private DataFrameConvert() {}

    /**
     * 把整列转目标 dtype(基于 Object[] 中转,实现简单;性能不是 M1 关注点)。
     * 包级可见:被 DataFrame 多处(astype/typed accessor/isetitem/inferColumnDtype)共用。
     *
     * @param src    Column 源列;非 null
     * @param target DType 目标类型;非 null
     * @return Column 转换后的新列(不修改 src)
     * @throws IllegalArgumentException BigInteger/BigDecimal 超 long 范围、LONG/INT/DOUBLE 字符串
     *         解析失败(教学型,带列名/行号/值)、DATETIME/DATE 解析失败、不支持的目标
     */
    static Column convertColumn(Column src, DType target) {
        String name = src.name();
        Object[] vals = src.toObjectArray();
        int n = vals.length;
        switch (target) {
            case DOUBLE: {
                double[] d = new double[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) d[i] = Double.NaN;
                    else if (vals[i] instanceof Number num) d[i] = num.doubleValue();
                    else {
                        // 字符串解析失败包教学型 IAE(带列/行/值,对齐 pandas
                        // ValueError "could not convert";裸 NumberFormatException 无上下文)
                        try {
                            d[i] = Double.parseDouble(String.valueOf(vals[i]));
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("astype DOUBLE 失败:列 '" + name + "' 第 " + i
                                    + " 行值 '" + vals[i] + "' 不是合法数值(pandas 同场景抛 ValueError)");
                        }
                    }
                }
                return new DoubleColumn(name, d);
            }
            case LONG: {
                long[] d = new long[n];
                boolean[] mask = new boolean[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) { mask[i] = true; d[i] = 0; }
                    else if (vals[i] instanceof Number num) {
                        // BigInteger/BigDecimal 超 long 范围时 longValueExact 抛错,
                        // 包装为带行号的 IAE(longValue() 会静默截断丢数据)
                        if (num instanceof java.math.BigInteger bi) {
                            try { d[i] = bi.longValueExact(); }
                            catch (ArithmeticException e) {
                                throw new IllegalArgumentException("astype LONG 第 " + i + " 行值超出 long 范围:" + vals[i]);
                            }
                        } else if (num instanceof java.math.BigDecimal bd) {
                            try { d[i] = bd.longValueExact(); }
                            catch (ArithmeticException e) {
                                throw new IllegalArgumentException("astype LONG 第 " + i + " 行值超出 long 范围:" + vals[i]);
                            }
                        } else d[i] = num.longValue();
                    }
                    else {
                        // 字符串转 LONG 失败包教学 IAE(带列/行/值,对齐 pandas
                        // ValueError "invalid literal";裸 NumberFormatException 无上下文)
                        try { d[i] = Long.parseLong(String.valueOf(vals[i])); }
                        catch (NumberFormatException e) {
                            throw new IllegalArgumentException("astype LONG 失败:列 '" + name + "' 第 " + i
                                    + " 行值 '" + vals[i] + "' 不是合法整数(pandas 同场景抛 ValueError)");
                        }
                    }
                }
                return new LongColumn(name, d, mask);
            }
            case STRING: {
                String[] d = new String[n];
                for (int i = 0; i < n; i++) d[i] = vals[i] == null ? null : String.valueOf(vals[i]);
                return new StringColumn(name, d);
            }
            case INT: {
                int[] d = new int[n];
                boolean[] mask = new boolean[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) { mask[i] = true; d[i] = 0; }
                    else if (vals[i] instanceof Number num) d[i] = num.intValue();
                    else {
                        try { d[i] = Integer.parseInt(String.valueOf(vals[i])); }
                        catch (NumberFormatException e) {
                            throw new IllegalArgumentException("astype INT 失败:列 '" + name + "' 第 " + i
                                    + " 行值 '" + vals[i] + "' 不是合法整数");
                        }
                    }
                }
                return new IntColumn(name, d, mask);
            }
            case BOOL: {
                // 接受:Boolean / "true"/"false" / 0/1 数值。
                // 显式声明【有意设计差异】:pandas astype(bool) 对非空字符串
                // 恒 True("yes"/"false"/"" 均为 True,仅空串/None 为 False);jian 仅 "true"/"1"
                // (不区分大小写)为 true,其余非空串(含 "yes"、"false")一律 false。
                // 该差异已在 doc/00-overview.md §10.16 登记,行为由回归测试锁定(不修改)。
                boolean[] d = new boolean[n];
                boolean[] mask = new boolean[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) { mask[i] = true; continue; }
                    if (vals[i] instanceof Boolean b) d[i] = b;
                    else if (vals[i] instanceof Number num) d[i] = num.doubleValue() != 0;
                    else {
                        String s = String.valueOf(vals[i]).toLowerCase();
                        d[i] = "true".equals(s) || "1".equals(s);
                    }
                }
                return new BoolColumn(name, d, mask);
            }
            case DATETIME: {
                // 接受:LocalDateTime / LocalDate(转 atStartOfDay)/ String(ISO 格式解析)
                java.time.LocalDateTime[] d = new java.time.LocalDateTime[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) continue;
                    if (vals[i] instanceof java.time.LocalDateTime lt) d[i] = lt;
                    else if (vals[i] instanceof java.time.LocalDate ld) d[i] = ld.atStartOfDay();
                    else {
                        try {
                            // 默认格式 YYYY-MM-DD HH:MM:SS(空格分隔);
                            // ISO T 分隔兼容。trim 防前后空格,replace 空格→T 后两种格式都能 parse。
                            String s = vals[i].toString().trim().replace(' ', 'T');
                            d[i] = java.time.LocalDateTime.parse(s);
                        } catch (Exception e) {
                            throw new IllegalArgumentException("astype DATETIME 无法解析第 " + i
                                + " 行值:" + vals[i] + "(期望 YYYY-MM-DD HH:MM:SS,如 2026-01-01 12:00:00;"
                                + "或 ISO 格式 2026-01-01T12:00:00)");
                        }
                    }
                }
                return new DateTimeColumn(name, d);
            }
            case DATE: {
                java.time.LocalDate[] d = new java.time.LocalDate[n];
                for (int i = 0; i < n; i++) {
                    if (vals[i] == null) continue;
                    if (vals[i] instanceof java.time.LocalDate ld) d[i] = ld;
                    else if (vals[i] instanceof java.time.LocalDateTime lt) d[i] = lt.toLocalDate();
                    else {
                        try {
                            d[i] = java.time.LocalDate.parse(String.valueOf(vals[i]));
                        } catch (Exception e) {
                            throw new IllegalArgumentException("astype DATE 无法解析第 " + i
                                + " 行值:" + vals[i] + "(期望 ISO 格式 2026-01-01)");
                        }
                    }
                }
                return new DateColumn(name, d);
            }
            case OBJECT: {
                return new ObjectColumn(name, vals);
            }
            default:
                // CATEGORY 列暂不支持(jian v1 未实现完整 CATEGORY dtype 语义)
                throw new IllegalArgumentException("astype 暂不支持转换到 " + target
                    + "(支持:DOUBLE/LONG/INT/STRING/BOOL/DATETIME/DATE/OBJECT)");
        }
    }

    /** 取 DOUBLE 列(数值列 INT/LONG 自动转 DOUBLE;主类 getDoubleColumn 委托,P3 拆分)。 */
    static DoubleColumn getDoubleColumn(DataFrame df, String name) {
        Column c = df.getColumn(name);
        if (c instanceof DoubleColumn) return (DoubleColumn) c;
        if (c.dtype().isNumeric()) return (DoubleColumn) convertColumn(c, DType.DOUBLE);
        throw new IllegalStateException("列 \"" + name + "\" 不是数值(DOUBLE/INT/LONG),实际 " + c.dtype());
    }

    /** 取 LONG 列(INT 自动升位;主类 getLongColumn 委托,P3 拆分)。 */
    static LongColumn getLongColumn(DataFrame df, String name) {
        Column c = df.getColumn(name);
        if (c instanceof LongColumn) return (LongColumn) c;
        if (c.dtype() == DType.INT) return (LongColumn) convertColumn(c, DType.LONG);
        if (c.dtype() == DType.LONG) return (LongColumn) c;
        throw new IllegalStateException("列 \"" + name + "\" 不是整数(INT/LONG),实际 " + c.dtype());
    }

    /** 取 INT 列(LONG 降位转 INT;主类 getIntColumn 委托,P3 拆分)。 */
    static IntColumn getIntColumn(DataFrame df, String name) {
        Column c = df.getColumn(name);
        if (c.dtype() == DType.INT) return (IntColumn) c;
        if (c.dtype() == DType.LONG) return (IntColumn) convertColumn(c, DType.INT);
        throw new IllegalStateException("列 \"" + name + "\" 不是整数(INT/LONG),实际 " + c.dtype());
    }
}
