package jian.core;

import java.util.Arrays;


public final class IntColumn implements Column {

    private final String name;
    final int[] data;
    final boolean[] nullMask;

    public IntColumn(String name, int[] data) {
        this.name = name;
        this.data = data.clone();
        this.nullMask = null;
    }

    public IntColumn(String name, int[] data, boolean[] nullMask) {
        this.name = name;
        this.data = data.clone();
        this.nullMask = nullMask == null ? null : nullMask.clone();
    }

    private IntColumn(String name, int[] data, boolean[] nullMask, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
        this.nullMask = nullMask;
    }

    @Override public DType dtype() { return DType.INT; }
    @Override public String name() { return name; }
    @Override public Column rename(String newName) { return new IntColumn(newName, data, nullMask, true); }
    @Override public int size() { return data.length; }

    @Override public Object get(int i) { return isNull(i) ? null : data[i]; }
    @Override public double getDouble(int i) { return data[i]; }
    @Override public long getLong(int i) { return data[i]; }
    @Override public boolean isNull(int i) { return nullMask != null && nullMask[i]; }

    @Override public int nullCount() {
        if (nullMask == null) return 0;
        int c = 0;
        for (boolean m : nullMask) if (m) c++;
        return c;
    }

    public int[] data() { return data.clone(); }

    @Override public Column slice(int start, int end) {
        int[] d = Arrays.copyOfRange(data, start, end);
        boolean[] m = nullMask == null ? null : Arrays.copyOfRange(nullMask, start, end);
        return new IntColumn(name, d, m, true);
    }

    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        int[] d = new int[n];
        boolean[] mOut = nullMask == null ? null : new boolean[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) {
            if (mask[i]) {
                d[j] = data[i];
                if (mOut != null) mOut[j] = nullMask[i];
                j++;
            }
        }
        return new IntColumn(name, d, mOut, true);
    }

    @Override public Column take(int[] indices) {
        int[] d = new int[indices.length];
        boolean[] m = nullMask == null ? null : new boolean[indices.length];
        for (int k = 0; k < indices.length; k++) {
            d[k] = data[indices[k]];
            if (m != null) m[k] = nullMask[indices[k]];
        }
        return new IntColumn(name, d, m, true);
    }

    @Override public Column copy() { return new IntColumn(name, data, nullMask); }

    @Override public Object[] toObjectArray() {
        Object[] o = new Object[data.length];
        for (int i = 0; i < data.length; i++) o[i] = isNull(i) ? null : data[i];
        return o;
    }

    @Override public String toString() {
        return "IntColumn[" + name + ", len=" + data.length + "]";
    }
}
