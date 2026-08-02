package jian.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


public final class BoolColumn implements Column {

    private final String name;
    final boolean[] data;
    final boolean[] nullMask;

    public BoolColumn(String name, boolean[] data, boolean[] nullMask) {
        this.name = name;
        this.data = data.clone();
        this.nullMask = nullMask == null ? null : nullMask.clone();
    }

    private BoolColumn(String name, boolean[] data, boolean[] nullMask, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
        this.nullMask = nullMask;
    }

    @Override public DType dtype() { return DType.BOOL; }
    @Override public String name() { return name; }
    @Override public Column rename(String newName) { return new BoolColumn(newName, data, nullMask, true); }
    @Override public int size() { return data.length; }

    @Override public Object get(int i) { return isNull(i) ? null : data[i]; }
    @Override public double getDouble(int i) { return isNull(i) ? Double.NaN : (data[i] ? 1.0 : 0.0); }
    @Override public long getLong(int i) { if (isNull(i)) throw new IllegalStateException("BoolColumn 第 " + i + " 行为缺失,不能转 long"); return data[i] ? 1L : 0L; }
    @Override public boolean isNull(int i) { return nullMask != null && nullMask[i]; }

    /** 直接取布尔值(无缺失场景)。 */
    public boolean getBool(int i) { return data[i]; }
    public boolean[] data() { return data.clone(); }

    @Override public int nullCount() {
        if (nullMask == null) return 0;
        int c = 0;
        for (boolean m : nullMask) if (m) c++;
        return c;
    }

    @Override public Column slice(int start, int end) {
        boolean[] d = Arrays.copyOfRange(data, start, end);
        boolean[] m = nullMask == null ? null : Arrays.copyOfRange(nullMask, start, end);
        return new BoolColumn(name, d, m, true);
    }

    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        boolean[] d = new boolean[n];
        boolean[] mOut = nullMask == null ? null : new boolean[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) {
            if (mask[i]) {
                d[j] = data[i];
                if (mOut != null) mOut[j] = nullMask[i];
                j++;
            }
        }
        return new BoolColumn(name, d, mOut, true);
    }

    @Override public Column take(int[] indices) {
        boolean[] d = new boolean[indices.length];
        boolean[] m = nullMask == null ? null : new boolean[indices.length];
        for (int k = 0; k < indices.length; k++) {
            d[k] = data[indices[k]];
            if (m != null) m[k] = nullMask[indices[k]];
        }
        return new BoolColumn(name, d, m, true);
    }

    @Override public Column copy() { return new BoolColumn(name, data, nullMask); }

    @Override public Object[] toObjectArray() {
        Object[] o = new Object[data.length];
        for (int i = 0; i < data.length; i++) o[i] = isNull(i) ? null : data[i];
        return o;
    }

    @Override public String toString() {
        return "BoolColumn[" + name + ", len=" + data.length + "]";
    }
}
