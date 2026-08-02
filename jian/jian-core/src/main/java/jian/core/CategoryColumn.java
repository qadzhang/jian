package jian.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


public final class CategoryColumn implements Column {

    private final String name;
    final int[] codes;
    final String[] categories;  // 码 → 值

    public CategoryColumn(String name, int[] codes, String[] categories) {
        this.name = name;
        this.codes = codes.clone();
        this.categories = categories.clone();
    }

    private CategoryColumn(String name, int[] codes, String[] categories, boolean noCopy) {
        this.name = name;
        this.codes = noCopy ? codes : codes.clone();
        this.categories = noCopy ? categories : categories.clone();
    }

    /**
     * 从字符串数组构造分类列(自动建立 categories)。
     */
    public static CategoryColumn fromStrings(String name, String[] values) {
        Map<String, Integer> val2code = new HashMap<>();
        java.util.List<String> cats = new java.util.ArrayList<>();
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

    @Override public DType dtype() { return DType.CATEGORY; }
    @Override public String name() { return name; }
    @Override public Column rename(String newName) { return new CategoryColumn(newName, codes, categories, true); }
    @Override public int size() { return codes.length; }

    @Override public Object get(int i) {
        return codes[i] == -1 ? null : categories[codes[i]];
    }
    @Override public double getDouble(int i) { return codes[i]; }
    @Override public long getLong(int i) { return codes[i]; }
    @Override public boolean isNull(int i) { return codes[i] == -1; }

    @Override public int nullCount() {
        int c = 0;
        for (int code : codes) if (code == -1) c++;
        return c;
    }

    public int[] codes() { return codes; }
    public String[] categories() { return categories; }

    @Override public Column slice(int start, int end) {
        return new CategoryColumn(name, Arrays.copyOfRange(codes, start, end), categories, true);
    }

    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        int[] out = new int[n];
        int j = 0;
        for (int i = 0; i < codes.length; i++) if (mask[i]) out[j++] = codes[i];
        return new CategoryColumn(name, out, categories, true);
    }

    @Override public Column take(int[] indices) {
        int[] out = new int[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = codes[indices[k]];
        return new CategoryColumn(name, out, categories, true);
    }

    @Override public Column copy() { return new CategoryColumn(name, codes, categories); }
    @Override public Object[] toObjectArray() {
        Object[] o = new Object[codes.length];
        for (int i = 0; i < codes.length; i++) o[i] = get(i);
        return o;
    }

    @Override public String toString() {
        return "CategoryColumn[" + name + ", len=" + codes.length + ", cats=" + categories.length + "]";
    }
}
