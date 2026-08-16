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
 * (因为 hadoop-client-runtime 内含 shaded woodstox,缺它时 ORC 读写会抛 NoClassDefFoundError,
 * 所以显式引入,见 pom 注释)。
 *
 * <p>用法:
 * <pre>{@code
 * Orc.write(df, "data.orc").go();
 * DataFrame r = Orc.read("data.orc").go();
 * }</pre>
 */
public final class Orc {

    private Orc() {}

    /**
     * 按 String 路径读 ORC 的 builder。
     * @param path String ORC 文件路径,需为合法可读文件,不允许 null
     * @return OrcReader 配置器,调用 .go() 执行读取
     */
    public static OrcReader read(String path) { return new OrcReader(Path.of(path)); }

    /**
     * 按 Path 路径读 ORC 的 builder。
     * @param path Path ORC 文件路径对象,需为合法可读文件,不允许 null
     * @return OrcReader 配置器,调用 .go() 执行读取
     */
    public static OrcReader read(Path path) { return new OrcReader(path); }

    /**
     * 写 ORC 的 builder。
     * @param df DataFrame 要写出的数据帧,不允许 null;列类型按 jian DType 映射为 ORC 类型
     * @param path String 输出 ORC 文件路径,需为合法可写路径,不允许 null
     * @return OrcWriter 配置器,调用 .go() 执行写出
     */
    public static OrcWriter write(DataFrame df, String path) {
        return new OrcWriter(df, Path.of(path));
    }

    // ======================== 读 ========================

    public static final class OrcReader {
        private final Path path;
        OrcReader(Path p) { this.path = p; }

        public DataFrame go() throws Exception {
            // Reader/RecordReader 用 try-with-resources,异常路径也释放 native 资源
            Configuration conf = new Configuration();
            try (Reader reader = OrcFile.createReader(
                    new org.apache.hadoop.fs.Path(path.toAbsolutePath().toString()),
                    OrcFile.readerOptions(conf))) {
                TypeDescription schema = reader.getSchema();
                java.util.List<String> names = schema.getFieldNames();
                // 因为 LongColumnVector 同时承载 boolean/int/bigint(ORC 的向量化表示
                // 都是 long 数组),不回溯 schema 就无法区分(一律装箱 Long 会导致
                // BOOL→LONG(0/1)、INT→LONG),所以列的 ORC 类型元数据随行传递给 vectorValue。
                java.util.List<TypeDescription> children = schema.getChildren();
                Reader.Options options = reader.options().schema(schema);
                try (org.apache.orc.RecordReader rows = reader.rows(options)) {
                    java.util.List<Object[]> data = new java.util.ArrayList<>();
                    VectorizedRowBatch batch = schema.createRowBatch();
                    while (rows.nextBatch(batch)) {
                        ColumnVector[] cols = batch.cols;
                        for (int r = 0; r < batch.size; r++) {
                            Object[] row = new Object[names.size()];
                            for (int c = 0; c < names.size(); c++) row[c] = vectorValue(cols[c], children.get(c), r);
                            data.add(row);
                        }
                    }
                    // 因为 0 行时 Schema.infer 会全列退化 STRING,
                    // 所以用文件 schema 元数据(名字+类型)建列,与 Json/Xml/Pickle 口径一致。
                    if (data.isEmpty()) {
                        java.util.List<DType> dts = new java.util.ArrayList<>(names.size());
                        for (TypeDescription child : children) dts.add(orcTypeToDType(child));
                        return DataFrame.of(new Schema(names, dts), new Object[0][]);
                    }
                    Object[][] arr = data.toArray(new Object[0][]);
                    return DataFrame.of(Schema.infer(names, arr), arr);
                }
            }
        }
    }

    // ┌─ What : vectorValue —— 从 ColumnVector 取第 r 行的值(按 ORC schema 类型分流)
    // │  Why  : 因为 LongColumnVector 同时承载 boolean/int/bigint(ORC 向量化表示都是
    // │         long 数组),一律装箱 Long 会让 BOOL 列往返变 LONG(值 0/1)、INT 列变 LONG,
    // │         所以按 schema 类型分流取值
    // │  Who  : OrcReader.go()
    // │  When : 逐行取值时
    // │  Where: jian-io-orc/Orc.java
    // │  How  : 关键变量变化:
    // │           - raw(long 数组元素):boolean 列 0/1;int 列整值;bigint 列整值 —— 数值同型,
    // │             仅靠 type 参数区分语义;
    // │           - 返回值:BOOLEAN→Boolean(raw!=0);BYTE/SHORT/INT→Integer(窄化 (int) raw,
    // │             Schema.infer 据此推 INT);LONG/默认→Long(推 LONG);DOUBLE→Double;BYTES→String。
    // │         逻辑路线(四条路径):
    // │           路径 A(isNull)→ null;
    // │           路径 B(LongColumnVector)→ 按 category 分流 Boolean/Integer/Long;
    // │           路径 C(DoubleColumnVector)→ Double;
    // │           路径 D(BytesColumnVector)→ UTF-8 String。
    private static Object vectorValue(ColumnVector v, TypeDescription type, int r) {
        if (v.isNull[r]) return null;
        if (v instanceof org.apache.hadoop.hive.ql.exec.vector.LongColumnVector) {
            long raw = ((org.apache.hadoop.hive.ql.exec.vector.LongColumnVector) v).vector[r];
            TypeDescription.Category cat = type != null ? type.getCategory() : TypeDescription.Category.LONG;
            switch (cat) {
                case BOOLEAN: return raw != 0;                 // boolean 向量也是 long 0/1
                case BYTE: case SHORT: case INT: return (int) raw;   // int 系 → Integer(INT dtype)
                default: return raw;                            // bigint 等默认 → Long
            }
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

    /** ORC 类型 → jian DType(0 行重建 Schema 的元数据映射,与写侧 buildOrcSchema 对称)。 */
    private static DType orcTypeToDType(TypeDescription t) {
        if (t == null) return DType.STRING;
        switch (t.getCategory()) {
            case BOOLEAN: return DType.BOOL;
            case BYTE: case SHORT: case INT: return DType.INT;
            case LONG: return DType.LONG;
            case FLOAT: case DOUBLE: return DType.DOUBLE;
            default: return DType.STRING;   // string/varchar/char/其余 → STRING
        }
    }

    // ======================== 写 ========================

    public static final class OrcWriter {
        private final DataFrame df;
        private final Path path;

        OrcWriter(DataFrame df, Path p) { this.df = df; this.path = p; }

        public void go() throws Exception {
            // Writer 用 try-with-resources,addRowBatch 抛错时也 close(防 native 泄漏)
            TypeDescription schema = buildOrcSchema(df);
            Configuration conf = new Configuration();
            try (org.apache.orc.Writer writer = OrcFile.createWriter(
                    new org.apache.hadoop.fs.Path(path.toAbsolutePath().toString()),
                    OrcFile.writerOptions(conf).setSchema(schema))) {
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
            }
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
                // 说明:ORC 的 boolean 列在向量化表示里就是 LongColumnVector 的 0/1
                // (orc-core 无 boolean 专用向量),写侧本就正确;
                // 保真丢失的根因在读侧不回溯 schema(见 vectorValue),此处不改。
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
