package jian.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


public final class DateColumn implements Column {

    private final String name;
    final LocalDate[] data;

    public DateColumn(String name, LocalDate[] data) {
        this.name = name;
        this.data = data.clone();
    }

    private DateColumn(String name, LocalDate[] data, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
    }

    @Override public DType dtype() { return DType.DATE; }
    @Override public String name() { return name; }
    @Override public Column rename(String newName) { return new DateColumn(newName, data, true); }
    @Override public int size() { return data.length; }
    @Override public Object get(int i) { return data[i]; }
    @Override public double getDouble(int i) {
        return data[i] == null ? Double.NaN : (double) data[i].toEpochDay();
    }
    @Override public long getLong(int i) {
        if (data[i] == null) throw new IllegalStateException("DateColumn 第 " + i + " 行为缺失,不能转 long");
        return data[i].toEpochDay();
    }
    @Override public boolean isNull(int i) { return data[i] == null; }

    @Override public int nullCount() {
        int c = 0;
        for (LocalDate d : data) if (d == null) c++;
        return c;
    }

    public LocalDate[] data() { return data.clone(); }

    @Override public Column slice(int start, int end) {
        return new DateColumn(name, Arrays.copyOfRange(data, start, end), true);
    }

    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        LocalDate[] out = new LocalDate[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) if (mask[i]) out[j++] = data[i];
        return new DateColumn(name, out, true);
    }

    @Override public Column take(int[] indices) {
        LocalDate[] out = new LocalDate[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = data[indices[k]];
        return new DateColumn(name, out, true);
    }

    @Override public Column copy() { return new DateColumn(name, data); }
    @Override public Object[] toObjectArray() { return data.clone(); }

    @Override public String toString() {
        return "DateColumn[" + name + ", len=" + data.length + "]";
    }
}
