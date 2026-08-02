package jian.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


public final class ObjectColumn implements Column {

    private final String name;
    final Object[] data;

    public ObjectColumn(String name, Object[] data) {
        this.name = name;
        this.data = data.clone();
    }

    private ObjectColumn(String name, Object[] data, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
    }

    @Override public DType dtype() { return DType.OBJECT; }
    @Override public String name() { return name; }
    @Override public Column rename(String newName) { return new ObjectColumn(newName, data, true); }
    @Override public int size() { return data.length; }
    @Override public Object get(int i) { return data[i]; }
    @Override public double getDouble(int i) {
        Object o = data[i];
        if (o == null) return Double.NaN;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("Object 列第 " + i + " 行不能转 double:" + o);
        }
    }
    @Override public long getLong(int i) {
        Object o = data[i];
        if (o == null) throw new IllegalStateException("ObjectColumn 第 " + i + " 行为缺失,不能转 long");
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("Object 列第 " + i + " 行不能转 long:" + o);
        }
    }
    @Override public boolean isNull(int i) { return data[i] == null; }

    @Override public int nullCount() {
        int c = 0;
        for (Object o : data) if (o == null) c++;
        return c;
    }

    public Object[] data() { return data.clone(); }

    @Override public Column slice(int start, int end) {
        return new ObjectColumn(name, Arrays.copyOfRange(data, start, end), true);
    }

    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        Object[] out = new Object[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) if (mask[i]) out[j++] = data[i];
        return new ObjectColumn(name, out, true);
    }

    @Override public Column take(int[] indices) {
        Object[] out = new Object[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = data[indices[k]];
        return new ObjectColumn(name, out, true);
    }

    @Override public Column copy() { return new ObjectColumn(name, data); }
    @Override public Object[] toObjectArray() { return data.clone(); }

    @Override public String toString() {
        return "ObjectColumn[" + name + ", len=" + data.length + "]";
    }
}
