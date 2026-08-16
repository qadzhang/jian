package jian.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// ┌─ What : RecordBridge —— DataFrame ↔ Java record 双向映射(强类型出口/入口)
// │  Why  : 借鉴 Kotlin DataFrame 的 convertTo<T>()/@DataSchema:DataFrame 泛型存 Object,
// │         与 Spring/JPA/业务代码之间缺一座"编译期可见"的强类型桥梁。Java 17 record
// │         的组件名天然对应列名,反射即可完成零依赖映射(规范 §1.1 纯 Java,不引 Kotlin)
// │  Who  : 由 DataFrame.toRecords/fromRecords 委托;用户经实例/静态方法调用
// │  When : DataFrame.toRecords/fromRecords 委托调用时
// │  Where: jian-core/RecordBridge.java
// │  How  : 数据走向:
// │           toRecords:df → 每列取值 → coerce(列值 → 组件类型,同族转换/缺失检查)
// │             → record 规范构造器逐行实例化 → List<T>
// │           fromRecords:record 组件声明类型 → DType 映射(精确,非推断)→ 取值
// │             → DataFrame.of(schema, rows)
// │         关键变量变化:
// │           - comps:record 组件数组(名字/声明类型),colIdx[c] = 组件在 df 的列下标;
// │           - args:某行的组件实参,经 coerce 后与构造器形参类型严格对齐;
// │           - rows:fromRecords 的 Object[][],元素为 getter 反射取出的原生类型值。
// │         逻辑路线(toRecords,四条路径任一失败即抛,不静默):
// │           路径 A(非 record)→ IAE「仅支持 record」;
// │           路径 B(组件无对应列)→ IAE 列出缺失组件与 df 现有列(防拼写错悄悄丢数据;
// │             df 多余列被忽略 = 投影语义,方向不对称是有意的);
// │           路径 C(某行值 coerce 失败:类型不匹配/越界/null 进原始类型)→ IAE 带行号+列名;
// │           路径 D(全部通过)→ List<T> 返回。
// │         逻辑路线(fromRecords):
// │           路径 A(空列表)→ IAE(无 schema 可推);
// │           路径 B(元素非 record/类型不齐)→ IAE;
// │           路径 C(正常)→ 按组件声明类型精确定 DType(不猜),getter 取值建表。
final class RecordBridge {

    private RecordBridge() {}

    /**
     * DataFrame → List&lt;record&gt;(每行一个 record 实例;df 多余列忽略)。
     * @param df DataFrame 数据源,非 null
     * @param type Class&lt;T&gt; 目标 record 类型,非 null 且必须为 record
     * @param <T> record 类型
     * @return List&lt;T&gt; 行数 == df.rowCount()
     * @throws IllegalArgumentException type 非 record / 组件无对应列 / 某行类型不匹配或缺失值进原始类型组件
     */
    static <T> List<T> toRecords(DataFrame df, Class<T> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(
                "toRecords 仅支持 Java record,实际:" + type.getName()
                    + "(record 组件名自动对应列名;普通类请改用 record)");
        }
        RecordComponent[] comps = type.getRecordComponents();
        int[] colIdx = new int[comps.length];
        for (int c = 0; c < comps.length; c++) {
            colIdx[c] = df.columnIndex(comps[c].getName());
            if (colIdx[c] < 0) {
                throw new IllegalArgumentException(
                    "record 组件「" + comps[c].getName() + "」在 DataFrame 中无对应列;"
                        + "现有列:" + df.columnNames()
                        + "(DataFrame 多余列会被忽略;组件缺列则是错误)");
            }
        }
        try {
            Constructor<T> ctor = type.getDeclaredConstructor(
                java.util.Arrays.stream(comps).map(RecordComponent::getType).toArray(Class[]::new));
            ctor.setAccessible(true);
            List<T> out = new ArrayList<>(df.rowCount());
            for (int r = 0; r < df.rowCount(); r++) {
                Object[] args = new Object[comps.length];
                for (int c = 0; c < comps.length; c++) {
                    String colName = df.columnNames().get(colIdx[c]);
                    Object v = df.get(r, colIdx[c]);
                    args[c] = coerce(v, comps[c].getType(),
                        comps[c].getName(), r, df.getColumn(colName).dtype());
                }
                out.add(ctor.newInstance(args));
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;  // coerce 的 IAE 直通,别被下面的反射包装吃掉
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                "record " + type.getSimpleName() + " 实例化失败:" + e.getMessage(), e);
        }
    }

    /**
     * List&lt;record&gt; → DataFrame(按组件声明类型精确定 DType,不做推断)。
     * @param records List&lt;?&gt; record 实例列表,非 null 且非空;元素须为同一 record 类型
     * @return DataFrame 列 = 组件,行数 = records.size()
     * @throws IllegalArgumentException 空列表 / 元素非 record / 元素类型不齐
     */
    static DataFrame fromRecords(List<?> records) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("fromRecords 需要至少一条 record(空列表无法确定 schema)");
        }
        Class<?> first = records.get(0).getClass();
        if (!first.isRecord()) {
            throw new IllegalArgumentException(
                "fromRecords 仅支持 Java record 列表,首元素类型:" + first.getName());
        }
        for (Object o : records) {
            if (o == null || o.getClass() != first) {
                throw new IllegalArgumentException(
                    "fromRecords 列表元素类型不齐:期望 " + first.getSimpleName()
                        + ",实际 " + (o == null ? "null" : o.getClass().getSimpleName()));
            }
        }
        RecordComponent[] comps = first.getRecordComponents();
        // accessor 预先 setAccessible:嵌套 record(如方法内/类内定义)类本身可能包私有,
        // jian.core 跨包直接反射调 public accessor 会 IllegalAccessException
        for (RecordComponent c : comps) {
            try {
                c.getAccessor().setAccessible(true);
            } catch (SecurityException e) {
                throw new IllegalArgumentException(
                    "record " + first.getSimpleName() + " 组件「" + c.getName() + "」 accessor 不可访问:" + e.getMessage(), e);
            }
        }
        Object[] schParts = new Object[comps.length * 2];
        for (int c = 0; c < comps.length; c++) {
            schParts[c * 2] = comps[c].getName();
            schParts[c * 2 + 1] = componentDType(comps[c].getType());
        }
        Object[][] rows = new Object[records.size()][comps.length];
        try {
            for (int r = 0; r < records.size(); r++) {
                for (int c = 0; c < comps.length; c++) {
                    rows[r][c] = comps[c].getAccessor().invoke(records.get(r));
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("record 取值失败:" + e.getMessage(), e);
        }
        return DataFrame.of(Schema.of(schParts), rows);
    }

    /**
     * 列值 → record 组件类型(严格转换:同族数值宽/窄化做范围检查,跨族/字符串→日期等隐式转换一律拒绝)。
     *
     * @param v Object 列值(可能为 null 表示缺失)
     * @param target Class&lt;?&gt; 组件声明类型(可能是原始类型)
     * @param name String 组件/列名(报错用)
     * @param row int 行号(报错用)
     * @param dtype DType 列 dtype(报错用)
     * @return Object 与 target 对齐的值
     * @throws IllegalArgumentException 缺失值进原始类型 / 类型跨族不匹配 / 窄化越界
     */
    private static Object coerce(Object v, Class<?> target, String name, int row, DType dtype) {
        if (v == null) {
            if (target.isPrimitive()) {
                throw new IllegalArgumentException("第 " + row + " 行列「" + name
                    + "」缺失(null),但组件是原始类型 " + target.getSimpleName()
                    + ";请改用包装类型(Integer/Long/Double/Boolean...)以承载缺失值");
            }
            return null;
        }
        // Class.isInstance 对原始类型恒 false(double.class.isInstance(Double装箱)=false),
        // 先把原始 target 装箱再对照:double 组件 + Double 值直通
        Class<?> boxed = box(target);
        if (boxed.isInstance(v)) return v;
        // 整型族内互转(Integer/Long/Short/Byte 之间):窄化做范围检查
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            long l = ((Number) v).longValue();
            if (target == long.class || target == Long.class) return l;
            if (target == int.class || target == Integer.class) {
                if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("第 " + row + " 行列「" + name
                        + "」值 " + l + " 超出 int 范围");
                }
                return (int) l;
            }
            if (target == short.class || target == Short.class) {
                if (l < Short.MIN_VALUE || l > Short.MAX_VALUE) {
                    throw new IllegalArgumentException("第 " + row + " 行列「" + name
                        + "」值 " + l + " 超出 short 范围");
                }
                return (short) l;
            }
        }
        // 浮点族内窄化:double → float(用户明确声明 float 视为接受精度损失)
        if (v instanceof Double d && (target == float.class || target == Float.class)) {
            return d.floatValue();
        }
        throw new IllegalArgumentException("第 " + row + " 行列「" + name + "」类型不匹配:"
            + "列 dtype=" + dtype + ",值类型=" + v.getClass().getSimpleName()
            + ",record 组件类型=" + target.getSimpleName()
            + "(跨族不做隐式转换,请先 df.astype(...))");
    }

    /** 原始类型 → 包装类(非原始原样返回);Class.isInstance 不能直接判原始类型。 */
    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == double.class) return Double.class;
        if (t == boolean.class) return Boolean.class;
        if (t == float.class) return Float.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        return t;   // char/void 不在 record 数值映射范围
    }

    /**
     * record 组件声明类型 → DType(精确定义,不做运行时推断)。
     * @param t Class&lt;?&gt; 组件声明类型
     * @return DType 对应 dtype;无对应者(OBJECT 列承载)
     */
    private static DType componentDType(Class<?> t) {
        if (t == String.class) return DType.STRING;
        if (t == boolean.class || t == Boolean.class) return DType.BOOL;
        if (t == int.class || t == Integer.class || t == short.class || t == Short.class
                || t == byte.class || t == Byte.class) return DType.INT;
        if (t == long.class || t == Long.class) return DType.LONG;
        if (t == double.class || t == Double.class || t == float.class || t == Float.class) return DType.DOUBLE;
        if (t == LocalDate.class) return DType.DATE;
        if (t == LocalDateTime.class) return DType.DATETIME;
        return DType.OBJECT;
    }
}
