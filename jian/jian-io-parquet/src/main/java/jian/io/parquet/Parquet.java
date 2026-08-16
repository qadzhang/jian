package jian.io.parquet;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// ┌─ What : Parquet —— 列式二进制读写(对齐 pandas.read_parquet / to_parquet,基于 parquet-avro 1.14.4)
// │  Why  : 规范 02 §3.7;Parquet 是大数据列存事实标准,去 Hadoop 化用 LocalInputFile/OutputFile(纯 JDK)
// │  Who  : 用户经 Jian.readParquet / Parquet.read 调用
// │  When : 大表持久化、跨系统交换
// │  Where: jian-io-parquet/Parquet.java
// │  How  : 数据走向:
// │           写:DataFrame → Avro Schema(按 dtype 映射)→ GenericRecord 列表 → AvroParquetWriter → .parquet;
// │           读:.parquet → AvroParquetReader → GenericRecord 列表 → Object[][] → DataFrame。
// │         关键变量变化:
// │           - jian DType → Avro Schema 类型(LONG→long,DOUBLE→double,STRING→string,BOOL→boolean);
// │           - DATETIME/DATE 转 string(millis)(简化);OBJECT 转 string。
// │         逻辑路线:
// │           路径 A(写)→ 建 Avro Schema + 逐行 GenericData.Record → 写;
// │           路径 B(读)→ Avro Schema 取字段名 → 逐 record 取值 → DataFrame。
/**
 * Parquet 列式二进制读写,对齐 pandas.read_parquet / to_parquet。
 *
 * <p>基于 parquet-avro 1.14.4 + LocalInputFile/OutputFile(纯 JDK,**去 Hadoop 化**,规范 §3.7)。
 *
 * <p>用法:
 * <pre>{@code
 * Parquet.write(df, "data.parquet").go();
 * DataFrame r = Parquet.read("data.parquet").go();
 * }</pre>
 */
public final class Parquet {

    private Parquet() {}

    /**
     * 按 String 路径读 Parquet 的 builder。
     * @param path String Parquet 文件路径,需为合法可读文件,不允许 null
     * @return ParquetReader 配置器,调用 .go() 执行读取
     */
    public static ParquetReader read(String path) { return new ParquetReader(Path.of(path)); }

    /**
     * 写 Parquet 的 builder。
     * @param df DataFrame 要写出的数据帧,不允许 null;列类型按 jian DType 映射为 Avro Schema
     * @param path String 输出 Parquet 文件路径,需为合法可写路径,不允许 null
     * @return ParquetWriter 配置器,调用 .go() 执行写出
     */
    public static ParquetWriter write(DataFrame df, String path) { return new ParquetWriter(df, Path.of(path)); }

    // ======================== 读 ========================

    public static final class ParquetReader {
        private final Path path;
        ParquetReader(Path p) { this.path = p; }

        public DataFrame go() throws IOException {
            LocalInputFile input = new LocalInputFile(path);
            try (org.apache.parquet.hadoop.ParquetReader<GenericRecord> reader =
                         AvroParquetReader.<GenericRecord>builder(input).build()) {
                List<GenericRecord> records = new ArrayList<>();
                GenericRecord rec;
                while ((rec = reader.read()) != null) records.add(rec);
                if (records.isEmpty()) {
                    // 因为 0 行文件若直接返回零列 DataFrame 会丢列(写 [a,b] 两列 0 行
                    // parquet 读回 []),且空数据经 Schema.infer 会全列退化 STRING,
                    // 所以与 Json/Xml/Pickle 的 "0 行保列元数据"口径对齐:
                    // 从文件 footer 的 Parquet schema 取列名+物理类型重建 Schema。
                    return emptyFromFooter(input);
                }
                // 从 schema 取列名
                org.apache.avro.Schema avroSchema = records.get(0).getSchema();
                List<String> names = new ArrayList<>();
                for (org.apache.avro.Schema.Field f : avroSchema.getFields()) names.add(f.name());
                Object[][] rows = new Object[records.size()][names.size()];
                for (int r = 0; r < records.size(); r++) {
                    GenericRecord record = records.get(r);
                    for (int c = 0; c < names.size(); c++) rows[r][c] = record.get(c);
                }
                return DataFrame.of(Schema.infer(names, rows), rows);
            }
        }
    }

    // ┌─ What : emptyFromFooter —— 0 行 parquet 文件按 footer 元数据重建列
    // │  Why  : 空文件读不到任何 record,列名/类型只能从文件 footer 的 Parquet schema 拿;
    // │         写侧 buildAvroSchema 的 [null, X] union 落到 parquet 是 OPTIONAL 的物理类型 X
    // │  Who  : ParquetReader.go()(records 为空分支)
    // │  When : 读 0 行 parquet 文件时
    // │  Where: jian-io-parquet/Parquet.java
    // │  How  : 伪代码:
    // │           1. ParquetFileReader.open(input) 读 footer → getFileMetaData().getSchema()
    // │           2. 逐字段取名字 + PrimitiveTypeName → DType(INT32→INT/INT64→LONG/
    //              DOUBLE→DOUBLE/BOOLEAN→BOOL/其余 BINARY 等→STRING)
    // │           3. DataFrame.of(Schema, 0 行) —— 列数 = schema 字段数
    // │         关键变量变化:names/dtypes 从空 → 按 footer 字段逐一填充;
    // │           ParquetFileReader 自身 try-with-resources(footer 读毕即关,不占句柄)。
    // │         逻辑路线(两条路径):
    // │           路径 A(footer 可读)→ 返回 N 列 0 行 DataFrame(N = 字段数);
    // │           路径 B(footer 读取失败)→ 不吞异常,原样上抛 IOException(文件损坏必须暴露)。
    // │         数据走向:parquet footer → MessageType → (列名, DType) 列表 → Schema → DataFrame。
    private static DataFrame emptyFromFooter(LocalInputFile input) throws IOException {
        try (org.apache.parquet.hadoop.ParquetFileReader fr = org.apache.parquet.hadoop.ParquetFileReader.open(input)) {
            org.apache.parquet.schema.MessageType mt = fr.getFileMetaData().getSchema();
            List<String> names = new ArrayList<>();
            List<DType> dtypes = new ArrayList<>();
            for (org.apache.parquet.schema.Type field : mt.getFields()) {
                names.add(field.getName());
                dtypes.add(parquetTypeToDType(field));
            }
            return DataFrame.of(new Schema(names, dtypes), new Object[0][]);
        }
    }

    /** Parquet 物理类型 → jian DType(与写侧 buildAvroSchema 的映射对称;复合/未知类型兜底 STRING)。 */
    private static DType parquetTypeToDType(org.apache.parquet.schema.Type field) {
        if (!field.isPrimitive()) return DType.STRING;
        org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName ptn =
                ((org.apache.parquet.schema.PrimitiveType) field).getPrimitiveTypeName();
        switch (ptn) {
            case INT32: return DType.INT;
            case INT64: return DType.LONG;
            case DOUBLE: case FLOAT: return DType.DOUBLE;
            case BOOLEAN: return DType.BOOL;
            default: return DType.STRING;   // BINARY(avro string)/FIXED_LEN_BYTE_ARRAY/INT96 → STRING
        }
    }

    // ======================== 写 ========================

    public static final class ParquetWriter {
        private final DataFrame df;
        private final Path path;

        ParquetWriter(DataFrame df, Path p) { this.df = df; this.path = p; }

        public void go() throws IOException {
            org.apache.avro.Schema avroSchema = buildAvroSchema(df);
            LocalOutputFile output = new LocalOutputFile(path);
            // AvroParquetWriter.builder 返回 ParquetWriter(基类),用基类变量类型
            try (org.apache.parquet.hadoop.ParquetWriter<GenericRecord> writer =
                         AvroParquetWriter.<GenericRecord>builder(output)
                                 .withSchema(avroSchema)
                                 .build()) {
                for (Object[] row : df.iterRows()) {
                    GenericData.Record rec = new GenericData.Record(avroSchema);
                    List<String> cols = df.columnNames();
                    for (int c = 0; c < cols.size(); c++) {
                        Object v = row[c];
                        rec.put(c, toAvroValue(v));
                    }
                    writer.write(rec);
                }
            }
        }
    }

    /** jian DType → Avro Schema 类型。 */
    private static org.apache.avro.Schema buildAvroSchema(DataFrame df) {
        SchemaBuilder.FieldAssembler<org.apache.avro.Schema> fa =
                SchemaBuilder.record("jian").fields();
        List<String> names = df.columnNames();
        List<DType> dtypes = df.dtypes();
        for (int c = 0; c < names.size(); c++) {
            String name = names.get(c);
            DType dt = dtypes.get(c);
            switch (dt) {
                case INT: fa = fa.name(name).type().unionOf().nullType().and().intType().endUnion().nullDefault(); break;
                case LONG: fa = fa.name(name).type().unionOf().nullType().and().longType().endUnion().nullDefault(); break;
                case DOUBLE: fa = fa.name(name).type().unionOf().nullType().and().doubleType().endUnion().nullDefault(); break;
                case BOOL: fa = fa.name(name).type().unionOf().nullType().and().booleanType().endUnion().nullDefault(); break;
                case STRING:
                default:
                    fa = fa.name(name).type().unionOf().nullType().and().stringType().endUnion().nullDefault();
                    break;
            }
        }
        return fa.endRecord();
    }

    /** Java 值 → Avro 兼容值(日期/对象转 string)。 */
    private static Object toAvroValue(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return v;
        if (v instanceof Boolean) return v;
        if (v instanceof String) return v;
        if (v instanceof java.time.temporal.Temporal) return v.toString();
        return String.valueOf(v);
    }
}
