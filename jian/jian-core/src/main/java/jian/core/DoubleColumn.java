package jian.core;

import java.util.Arrays;


public final class DoubleColumn implements Column {

    private final String name;
    final double[] data;

    public DoubleColumn(String name, double[] data) {
        this.name = name;
        this.data = data.clone();
    }

    private DoubleColumn(String name, double[] data, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
    }

    @Override public DType dtype() { return DType.DOUBLE; }
    @Override public String name() { return name; }
    @Override public Column rename(String newName) { return new DoubleColumn(newName, data, true); }
    @Override public int size() { return data.length; }

    @Override public Object get(int i) {
        double v = data[i];
        return Double.isNaN(v) ? null : v;
    }
    @Override public double getDouble(int i) { return data[i]; }
    @Override public long getLong(int i) { return (long) data[i]; }
    @Override public boolean isNull(int i) { return Double.isNaN(data[i]); }

    @Override public int nullCount() {
        int c = 0;
        for (double v : data) if (Double.isNaN(v)) c++;
        return c;
    }

    /** 直接访问内部数组(同包用,只读承诺)。 */
    public double[] data() { return data.clone(); }

    @Override public Column slice(int start, int end) {
        return new DoubleColumn(name, Arrays.copyOfRange(data, start, end), true);
    }

    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        double[] out = new double[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) if (mask[i]) out[j++] = data[i];
        return new DoubleColumn(name, out, true);
    }

    @Override public Column take(int[] indices) {
        double[] out = new double[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = data[indices[k]];
        return new DoubleColumn(name, out, true);
    }

    @Override public Column copy() { return new DoubleColumn(name, data); }

    @Override public Object[] toObjectArray() {
        Object[] o = new Object[data.length];
        for (int i = 0; i < data.length; i++) o[i] = Double.isNaN(data[i]) ? null : data[i];
        return o;
    }

    @Override public String toString() {
        return "DoubleColumn[" + name + ", len=" + data.length + "]";
    }
}
