package jian.io.json;

import jian.core.DataFrame;
import jian.core.DType;
import jian.core.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ┌─ What : JsonRegressionTest —— JSON 读写回归测试集
// │  Why  : 固化 JSON 读取/写出行为(因为 BOM、超大整数、列长校验、索引排序、
// │         records 元素校验、0 行保列等边界行为一旦回归会静默丢数据或崩解析,
// │         所以全部固化为本测试集;期望锚定 pandas 1.5.3)。
// │  Who  : CI(./mvnw test -pl jian-io-json)
// │  When : 改 Json 读/写行为后必须跑
// │  Where: jian-io-json/src/test/java/jian/io/json/JsonRegressionTest.java
// │  How  : 数据走向:JSON 文本/临时文件 → Json.parse/read/write → 断言
// │         dtype/列名/值/异常消息;非法输入断言 IAE fail-fast。
class JsonRegressionTest {

    @TempDir Path tmp;

    // ======================== 读取:BOM / 超大整数 / 索引排序 ========================

    @Test
    void UTF8_BOM文件可读() throws Exception {
        Path p = tmp.resolve("bom.json");
        Files.write(p, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        Files.writeString(p, "[{\"a\":1}]", java.nio.file.StandardOpenOption.APPEND);
        DataFrame df = Json.read(p.toString()).go();
        // 因为读入口统一剥 BOM(Jackson 不吃 BOM,否则 JsonParseException code 65279)
        assertThat(df.get(0, 0)).isEqualTo(1);
    }

    @Test
    void 超大整数读入不崩溃且归STRING() throws Exception {
        DataFrame df = Json.parse("[{\"x\":123456789012345678901234567890}]", Json.Orient.RECORDS);
        // 超 long 范围整数归 STRING(对齐 pandas read_csv 超 int64 → object),不裸抛 NFE
        assertThat(df.dtypes().get(0)).isEqualTo(jian.core.DType.STRING);
        assertThat(df.get(0, 0)).isEqualTo("123456789012345678901234567890");
    }

    @Test
    void 数字串键索引按数值排序() throws Exception {
        // 因为数字键若按字典序排序会得 "0","1","10","2" 的错位行序,所以按数值排序
        DataFrame df = Json.parse(
                "{\"v\":{\"0\":10,\"1\":11,\"10\":13,\"2\":12}}", Json.Orient.INDEX);
        jian.core.DoubleColumn v = df.getDoubleColumn("v");
        assertThat(v.data()).containsExactly(10.0, 11.0, 12.0, 13.0);
        // 文本键保持字典序(pandas 对文本键也字典序)
        DataFrame t = Json.parse("{\"v\":{\"b\":2,\"a\":1}}", Json.Orient.INDEX);
        assertThat(t.getDoubleColumn("v").data()).containsExactly(1.0, 2.0);
    }

    @Test
    void index超长整型键回退字典序不崩() throws Exception {
        // 因为超 long 范围键 parseLong 会抛裸 NFE 中断整个 parse,
        // 所以回退字典序(pandas 回退语义)
        DataFrame df = Json.parse("{\"a\":{\"9223372036854775808\":1,\"0\":2}}", Json.Orient.INDEX);
        assertThat(df.rowCount()).isEqualTo(2);
    }

    @Test
    void columns列长不等抛清晰异常() throws Exception {
        // 对齐 pandas "All arrays must be of the same length"(静默通过会让下游 get 抛裸 AIOOBE)
        assertThatThrownBy(() -> Json.parse("{\"a\":[1,2],\"b\":[]}", Json.Orient.COLUMNS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度")
                .hasMessageContaining("b");
    }

    @Test
    void split长行对齐pandas抛错() throws java.io.IOException {
        // 短行缺键填 null(pandas 一致)
        DataFrame shortDf = Json.parse(
                "{\"columns\":[\"a\",\"b\"],\"data\":[[1],[3,4]]}", Json.Orient.SPLIT);
        assertThat(shortDf.get(0, 1)).isNull();
        // 长行(3 值 > 2 列)对齐 pandas ValueError 抛 IAE(不静默截断丢数据)
        assertThatThrownBy(() -> Json.parse(
                "{\"columns\":[\"a\",\"b\"],\"data\":[[1,2,3]]}", Json.Orient.SPLIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("行宽");
    }

    // ======================== normalize ========================

    @Test
    void normalizeNull路径不再NPE() throws Exception {
        // recordPath=null 视为根(不调 isBlank 以免 NPE)
        DataFrame df = Json.normalize("[{\"a\":1}]", (String) null);
        assertThat(df.get(0, 0)).isEqualTo(1);
    }

    @Test
    void recordPath变参支持含点号key() throws Exception {
        String json = "{\"meta\":{\"b.c\":[{\"x\":1}]}}";
        // 点号字符串入口无法表达 key "b.c";变参逐段可表达(pandas record_path=["meta","b.c"] 同款)
        DataFrame df = Json.normalize(json, "meta", "b.c");
        assertThat(df.columnNames()).containsExactly("x");
    }

    // ======================== 写出:BigInteger / 0 行保列 ========================

    @Test
    void BigInteger写出保精度() throws Exception {
        java.math.BigInteger huge = new java.math.BigInteger("123456789012345678901234567890");
        DataFrame df = DataFrame.ofColumns(new java.util.LinkedHashMap<>(java.util.Map.of("big", new Object[]{huge})));
        Path p = tmp.resolve("big.json");
        Json.write(df, p.toString()).go();
        String json = Files.readString(p);
        // BigInteger 精确写出(若降 double 会变 1.2345678901234568E29,精度全丢)
        assertThat(json).contains("123456789012345678901234567890");
    }

    @Test
    void bigInteger推断为STRING值保真() {
        // infer 遇超 long 整数 → STRING(对齐 pandas object),值逐字保真
        java.math.BigInteger big = new java.math.BigInteger("99999999999999999999");
        DataFrame df = DataFrame.of(Schema.infer(java.util.List.of("x"), new Object[][]{{big}}),
                new Object[][]{{big}});
        assertThat(df.getColumn("x").dtype()).isEqualTo(DType.STRING);
        assertThat(df.getColumn("x").get(0)).isEqualTo("99999999999999999999");
    }

    @Test
    void 显式LONG装超范围值抛IAE不静默截断() {
        // 因为 BigInteger 截断为 long 会静默损坏数据(值变 7766279631452241919 之类),
        // 所以显式 LONG schema 装超范围值必须抛 IAE
        java.math.BigInteger big = new java.math.BigInteger("99999999999999999999");
        assertThatThrownBy(() -> DataFrame.of(Schema.of("x", DType.LONG), new Object[][]{{big}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出 long 范围");
    }

    @Test
    void 零行DataFrame写出读回保留列名() throws Exception {
        DataFrame empty = DataFrame.of(Schema.of("a", DType.LONG, "b", DType.STRING), new Object[0][]);
        Path p = tmp.resolve("e.json");
        Json.write(empty, p.toString()).go();
        // 0 行自动切 COLUMNS 形态(不再是 "[]")
        assertThat(Files.readString(p)).isEqualTo("{\"a\":[],\"b\":[]}");
        DataFrame back = Json.read(p.toString()).go();
        assertThat(back.columnNames()).containsExactly("a", "b");
        assertThat(back.rowCount()).isZero();
        // 非空表不回归(RECORDS 数组形态)
        DataFrame df = DataFrame.of(Schema.of("a", DType.LONG), new Object[][]{{1L}});
        Path p2 = tmp.resolve("n.json");
        Json.write(df, p2.toString()).go();
        assertThat(Files.readString(p2)).isEqualTo("[{\"a\":1}]");
    }

    // ======================== records 元素校验(fail-fast) ========================

    @Test
    void 标量数组records抛异常含元素与位置() {
        // 因为标量元素的 fieldNames() 返回空迭代器,静默跳过会返回空 DataFrame、
        // 数据全部丢弃无报错,所以 fail-fast(对齐 pandas read_json)
        assertThatThrownBy(() -> Json.parse("[1,2,3]", Json.Orient.RECORDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持对象元素的 records 数组")
                .hasMessageContaining("第 0 个元素")
                .hasMessageContaining("1");
    }

    @Test
    void 混合对象与标量数组抛异常() {
        // 首元素合法、后续混入标量 → 同样 fail-fast(位置指向标量元素)
        assertThatThrownBy(() -> Json.parse("[{\"a\":1}, 5]", Json.Orient.RECORDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 1 个元素")
                .hasMessageContaining("5");
    }

    @Test
    void 数组元素records同样抛异常() {
        assertThatThrownBy(() -> Json.parse("[[1,2],[3,4]]", Json.Orient.RECORDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持对象元素的 records 数组");
    }

    @Test
    void 文件读取路径同样抛异常() throws Exception {
        Path p = tmp.resolve("scalar.json");
        Files.writeString(p, "[1,2,3]");
        assertThatThrownBy(() -> Json.read(p.toString()).go())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持对象元素的 records 数组");
    }

    @Test
    void 合法records行为不回归() throws Exception {
        DataFrame df = Json.parse("[{\"a\":1,\"b\":\"x\"},{\"a\":2,\"b\":\"y\"}]", Json.Orient.RECORDS);
        assertThat(df.rowCount()).isEqualTo(2);
        assertThat(df.columnNames()).containsExactly("a", "b");
        assertThat(df.getColumn("a").get(1)).isEqualTo(2);
    }

    @Test
    void 空数组与VALUES语义不受影响() throws Exception {
        // 空 records 数组仍返回空 DataFrame(无元素可校验,不抛)
        DataFrame empty = Json.parse("[]", Json.Orient.RECORDS);
        assertThat(empty.rowCount()).isEqualTo(0);
        // VALUES orient 对 [[1,2],[3,4]] 是合法输入,不走 records 校验
        DataFrame vals = Json.parse("[[1,2],[3,4]]", Json.Orient.VALUES);
        assertThat(vals.rowCount()).isEqualTo(2);
        assertThat(vals.columnNames()).containsExactly("_0", "_1");
    }
}
