package jian.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


public final class DateTimeColumn implements Column {

    private final String name;
    final LocalDateTime[] data;

    public DateTimeColumn(String name, LocalDateTime[] data) {
        this.name = name;
        this.data = data.clone();
    }

    private DateTimeColumn(String name, LocalDateTime[] data, boolean noCopy) {
        this.name = name;
        this.data = noCopy ? data : data.clone();
    }

    @Override public DType dtype() { return DType.DATETIME; }
    @Override public String name() { return name; }
    @Override public Column rename(String newName) { return new DateTimeColumn(newName, data, true); }
    @Override public int size() { return data.length; }
    @Override public Object get(int i) { return data[i]; }
    @Override public double getDouble(int i) {
        return data[i] == null ? Double.NaN : (double) data[i].toEpochSecond(java.time.ZoneOffset.UTC);
    }
    @Override public long getLong(int i) {
        if (data[i] == null) throw new IllegalStateException("DateTimeColumn 第 " + i + " 行为缺失,不能转 long");
        return data[i].toEpochSecond(java.time.ZoneOffset.UTC);
    }
    @Override public boolean isNull(int i) { return data[i] == null; }

    @Override public int nullCount() {
        int c = 0;
        for (LocalDateTime d : data) if (d == null) c++;
        return c;
    }

    public LocalDateTime[] data() { return data.clone(); }
    public LocalDateTime getDateTime(int i) { return data[i]; }

    @Override public Column slice(int start, int end) {
        return new DateTimeColumn(name, Arrays.copyOfRange(data, start, end), true);
    }

    @Override public Column filter(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) if (m) n++;
        LocalDateTime[] out = new LocalDateTime[n];
        int j = 0;
        for (int i = 0; i < data.length; i++) if (mask[i]) out[j++] = data[i];
        return new DateTimeColumn(name, out, true);
    }

    @Override public Column take(int[] indices) {
        LocalDateTime[] out = new LocalDateTime[indices.length];
        for (int k = 0; k < indices.length; k++) out[k] = data[indices[k]];
        return new DateTimeColumn(name, out, true);
    }

    @Override public Column copy() { return new DateTimeColumn(name, data); }
    @Override public Object[] toObjectArray() { return data.clone(); }

    @Override public String toString() {
        return "DateTimeColumn[" + name + ", len=" + data.length + "]";
    }
}
