package jian.io.orc;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.orc.OrcFile;
import org.apache.orc.Reader;
import org.apache.orc.TypeDescription;

import java.nio.file.Path;

// ┌─ What : Orc —— ORC 列式二进制读写(对齐 pandas.read_orc / to_orc,基于 orc-core 1.9.5)
// │  Why  : 规范 02 §3.8;ORC 是 Hive 列存格式
// │  Who  : 用户经 Jian.readOrc / Orc.read 调用
// │  When : ORC 文件读写
// │  Where: jian-io-orc/Orc.java
// │  How  : 数据走向:
// │           写:DataFrame → TypeDescription schema → VectorizedRowBatch → OrcFile.createWriter → 写;
// │           读:OrcFile.createReader → Reader.rows() → VectorizedRowBatch → Object[][] → DataFrame。
// │         关键变量变化:
// │           - batch:ORC 的 VectorizedRowBatch,列式批量;
// │           - jian DType → ORC 类型(INT→int,LONG→bigint,DOUBLE→double,STRING→string)。
// │         逻辑路线:
// │           路径 A(读)→ OrcFile.createReader + Reader.rows() 流式遍历 batch → DataFrame;
// │           路径 B(写)→ TypeDescription schema → VectorizedRowBatch 填值 → Writer.addRowBatch;
// │           路径 C(异常)→ IOException 包装带中文提示。
/**
 * ORC 列式二进制读写,对齐 pandas.read_orc / to_orc。
 *
 * <p>基于 orc-core 1.9.5 + hive-storage-api 2.8.1 + hadoop-client-runtime 3.3.6
 * (hadoop-client-runtime 内含 shaded woodstox,修复 ORC 读写的 NoClassDefFoundError,见 pom 注释)。
 *
 * <p>用法:
 * <pre>{@code
 * Orc.write(df, "data.orc").go();
 * DataFrame r = Orc.read("data.orc").go();
 * }</pre>
 */
public final class Orc {

    private Orc() {}

    public static OrcReader read(String path) { return new OrcReader(Path.of(path)); }
    public static OrcReader read(Path path) { return new OrcReader(path); }

    public static OrcWriter write(DataFrame df, String path) {
        return new OrcWriter(df, Path.of(path));
    }

    // ======================== 读 ========================

    public static final class OrcReader {
        private final Path path;
        OrcReader(Path p) { this.path = p; }

        public DataFrame go() throws Exception {
            Configuration conf = new Configuration();
            Reader reader = OrcFile.createReader(
                    new org.apache.hadoop.fs.Path(path.toAbsolutePath().toString()),
                    OrcFile.readerOptions(conf));
            TypeDescription schema = reader.getSchema();
            java.util.List<String> names = schema.getFieldNames();
            Reader.Options options = reader.options().schema(schema);
            org.apache.orc.RecordReader rows = reader.rows(options);
            java.util.List<Object[]> data = new java.util.ArrayList<>();
            VectorizedRowBatch batch = schema.createRowBatch();
            while (rows.nextBatch(batch)) {
                ColumnVector[] cols = batch.cols;
                for (int r = 0; r < batch.size; r++) {
                    Object[] row = new Object[names.size()];
                    for (int c = 0; c < names.size(); c++) row[c] = vectorValue(cols[c], r);
                    data.add(row);
                }
            }
            rows.close();
            Object[][] arr = data.toArray(new Object[0][]);
            return DataFrame.of(Schema.infer(names, arr), arr);
        }
    }

    /** 从 ColumnVector 取第 r 行的值。 */
    private static Object vectorValue(ColumnVector v, int r) {
        if (v.isNull[r]) return null;
        if (v instanceof org.apache.hadoop.hive.ql.exec.vector.LongColumnVector) {
            return ((org.apache.hadoop.hive.ql.exec.vector.LongColumnVector) v).vector[r];
        }
        if (v instanceof org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector) {
            return ((org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector) v).vector[r];
        }
        if (v instanceof org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector) {
            org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector b =
                    (org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector) v;
            return new String(b.vector[r], b.start[r], b.length[r], java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    // ======================== 写 ========================

    public static final class OrcWriter {
        private final DataFrame df;
        private final Path path;

        OrcWriter(DataFrame df, Path p) { this.df = df; this.path = p; }

        public void go() throws Exception {
            TypeDescription schema = buildOrcSchema(df);
            Configuration conf = new Configuration();
            org.apache.orc.Writer writer = OrcFile.createWriter(
                    new org.apache.hadoop.fs.Path(path.toAbsolutePath().toString()),
                    OrcFile.writerOptions(conf).setSchema(schema));
            VectorizedRowBatch batch = schema.createRowBatch();
            java.util.List<String> cols = df.columnNames();
            java.util.List<DType> dtypes = df.dtypes();
            for (Object[] row : df.iterRows()) {
                int rowIdx = batch.size++;
                for (int c = 0; c < cols.size(); c++) {
                    setVector(batch.cols[c], rowIdx, row[c], dtypes.get(c));
                }
                if (batch.size == batch.getMaxSize()) {
                    writer.addRowBatch(batch);
                    batch.reset();
                }
            }
            if (batch.size != 0) writer.addRowBatch(batch);
            writer.close();
        }
    }

    /** 按 jian DType 设 ColumnVector 值。 */
    private static void setVector(ColumnVector v, int r, Object val, DType dt) {
        if (val == null) {
            v.isNull[r] = true;
            v.noNulls = false;
            return;
        }
        switch (dt) {
            case INT: case LONG: {
                ((org.apache.hadoop.hive.ql.exec.vector.LongColumnVector) v).vector[r] = ((Number) val).longValue();
                break;
            }
            case DOUBLE: {
                ((org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector) v).vector[r] = ((Number) val).doubleValue();
                break;
            }
            case BOOL: {
                ((org.apache.hadoop.hive.ql.exec.vector.LongColumnVector) v).vector[r] = (Boolean) val ? 1 : 0;
                break;
            }
            case STRING:
            default: {
                byte[] b = String.valueOf(val).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ((org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector) v).setVal(r, b, 0, b.length);
                break;
            }
        }
    }

    /** jian DType → ORC schema。 */
    private static TypeDescription buildOrcSchema(DataFrame df) {
        StringBuilder sb = new StringBuilder("struct<");
        java.util.List<String> names = df.columnNames();
        java.util.List<DType> dtypes = df.dtypes();
        for (int c = 0; c < names.size(); c++) {
            if (c > 0) sb.append(',');
            sb.append(names.get(c)).append(':');
            DType dt = dtypes.get(c);
            switch (dt) {
                case INT: sb.append("int"); break;
                case LONG: sb.append("bigint"); break;
                case DOUBLE: sb.append("double"); break;
                case BOOL: sb.append("boolean"); break;
                case STRING:
                default: sb.append("string"); break;
            }
        }
        sb.append('>');
        return TypeDescription.fromString(sb.toString());
    }
}
