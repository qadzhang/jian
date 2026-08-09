package jian.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// ┌─ What : CategoryColumn —— 分类列实现(Column 子类,DType.CATEGORY)
// │  Why  : 规范 01 §2.1;对齐 pandas category;有限离散值用 int 码 + 值表压缩存储,省内存+加速等值比较
// │  Who  : DataFrame.columns 持有;IO 读取;fromStrings 工厂从 String[] 建立
// │  When : 列取值有限(性别、省份、状态码、枚举);分类统计/分组
// │  Where: jian-core/CategoryColumn.java
// │  How  : 数据走向:外部 String[] → fromStrings 建码表 → int[] codes + String[] categories 存储。
// │         关键变量变化:
// │           - codes:int[] 每行的码(0..n-1);**-1 表示缺失**;
// │           - categories:String[] 码→值映射表,顺序按首次出现。
// │         逻辑路线:
// │           构造 A(public)→ clone codes + clone categories;
// │           构造 B(private + noCopy)→ 直接引用;
// │           fromStrings 工厂→ 扫描 values,首次见到入 categories,后续查码;null 元素 → code -1。
// │           get(i)= codes[i]==-1 ? null : categories[codes[i]];
// │           变换 slice/filter/take **共享 categories 数组**(码表不变,只动 codes)。
/**
 * 分类列,存 int[](码) + String[](值表);缺失用码 -1(规范 01 §2.1,对齐 pandas category)。
 * <p><b>不可变</b>:变换返回新 CategoryColumn(共享 categories 引用,因码表只读)。
 */
public final class CategoryColumn implements Column {

    private final String name;
    final int[] codes;
    final String[] categories;  // 码 → 值

    /**
     * 公开构造(默认拷贝)。
     * @param name       String 列名,非 null
     * @param codes      int[] 每行码值,非 null;会被 clone;**-1 表示缺失**,有效码 ∈ [0, categories.length)
     * @param categories String[] 码→值表,非 null;会被 clone;categories[c] 是码 c 对应的字符串
     */
    public CategoryColumn(String name, int[] codes, String[] categories) {
        this.name = name;
        this.codes = codes.clone();
        this.categories = categories.clone();
    }

    /**
     * 内部构造(可控拷贝)。
     * @param noCopy boolean true=直接引用;false=clone
     */
    private CategoryColumn(String name, int[] codes, String[] categories, boolean noCopy) {
        this.name = name;
        this.codes = noCopy ? codes : codes.clone();
        this.categories = noCopy ? categories : categories.clone();
    }

    /**
     * 零拷贝构造(高性能内部 API)。
     * @param name       String 列名
     * @param codes      int[] 码值,**调用方此后不得修改**;非 null
     * @param categories String[] 值表,**调用方此后不得修改**;非 null
     * @return CategoryColumn 直接引用入参数组的新实例
     */
    public static CategoryColumn wrapNoCopy(String name, int[] codes, String[] categories) {
        return new CategoryColumn(name, codes, categories, true);
    }

    /**
     * 从字符串数组构造分类列(自动建立 categories 码表,首次出现顺序入表)。
     * @param name   String 列名,非 null
     * @param values String[] 输入字符串,非 null;元素允许 null(对应码 -1,缺失)
     * @return CategoryColumn noCopy 构造的新实例;码表按首次出现顺序排列
     */
    public static CategoryColumn fromStrings(String name, String[] values) {
        Map<String, Integer> val2code = new HashMap<>();
        List<String> cats = new ArrayList<>();
        int[] codes = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) { codes[i] = -1; continue; }
            Integer code = val2code.get(values[i]);
            if (code == null) {
                code = cats.size();
                val2code.put(values[i], code);
                cats.add(values[i]);
            }
            codes[i] = code;
        }
        return new CategoryColumn(name, codes, cats.toArray(new String[0]), true);
    }

    /** @return DType.CATEGORY(恒定) */
    @Override public DType dtype() { return DType.CATEGORY; }
    /** @return String 列名 */
    @Override public String name() { return name; }
    /** @return Column 改名后的新实例(noCopy,共享 categories) */
    @Override public Column rename(String newName) { return new CategoryColumn(newName, codes, categories, true); }
    /** @return int 行数 == codes.length */
    @Override public int size() { return codes.length; }

    /**
     * @param i int 行下标 ∈ [0, size())
     * @return Object String(codes[i]==-1 返回 null;否则返回 categories[codes[i]])
     */
    @Override public Object get(int i) {
        return codes[i] == -1 ? null : categories[codes[i]];
    }
    /**
     * @param i int 行下标
     * @return double (double) codes[i](**-1 即缺失**,返回 -1.0)
     */
    @Override public double getDouble(int i) {
        if (codes[i] == -1) return Double.NaN;
        return codes[i];
    }
    /**
     * @param i int 行下标
     * @return long (long) codes[i]。**缺失行(code -1)返回 Long.MIN_VALUE**(缺失标记,不抛异常)。
     */
    @Override public long getLong(int i) {
        if (codes[i] == -1) return Long.MIN_VALUE;
        return codes[i];
    }
    /**
     * @param i int 行下标
     * @return boolean true=码为 -1(缺失);false=有值
     */
    @Override public boolean isNull(int i) { return codes[i] == -1; }

    /**
     * @return int 码为 -1 的行数 ∈ [0, size()]
     */
    @Override public int nullCount() {
        int c = 0;
        for (int code : codes) if (code == -1) c++;
        return c;
    }

    /**
     * @return int[] 内部 codes 直接引用(**不克隆**,高性能访问);调用方不得修改
     */
    public int[] codes() { return codes; }
    /**
     * @return String[] 内部 categories 直接引用(**不克隆**);调用方不得修改
     */
    public String[] categories() { return categories; }

    /**
     * @param start int 起始(含) ∈ [0, size()]
     * @param end   int 结束(不含) ∈ [start, size()]
     * @return Column 新 CategoryColumn,codes 切片;**categories 共享引用**(码表不变)
     */
    @Override public Column slice(int start, int end) {
        return new CategoryColumn(name, Arrays.copyOfRange(codes, start, end), categories, true);
    }

    /**
     * @param mask boolean[] 掩码,长度必须 == size()
     * @return Column 仅含 mask==true 行的新 CategoryColumn;categories 共享
     */
    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        int[] out = new int[n];
        int j = 0;
        for (int i = 0; i < codes.length; i++) if (mask[i]) out[j++] = codes[i];
        return new CategoryColumn(name, out, categories, true);
    }

    /**
     * @param indices int[] 行下标,每个 ∈ [0, size());允许重复/乱序
     * @return Column 长度 == indices.length 的新 CategoryColumn;categories 共享
     */
    @Override public Column take(int[] indices) {
        int[] out = new int[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = codes[indices[k]];
        return new CategoryColumn(name, out, categories, true);
    }

    /** @return Column 深拷贝(clone codes + categories) */
    @Override public Column copy() { return new CategoryColumn(name, codes, categories); }
    /**
     * @return Object[] 长度 == size();缺失为 null,非缺失为 String(经 categories 解码)
     */
    @Override public Object[] toObjectArray() {
        Object[] o = new Object[codes.length];
        for (int i = 0; i < codes.length; i++) o[i] = get(i);
        return o;
    }

    /** @return String "CategoryColumn[name, len=N, cats=M]" */
    @Override public String toString() {
        return "CategoryColumn[" + name + ", len=" + codes.length + ", cats=" + categories.length + "]";
    }
}
