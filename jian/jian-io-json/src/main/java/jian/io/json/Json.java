package jian.io.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jian.core.Column;
import jian.core.DataFrame;
import jian.core.Schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

// ┌─ What : Json —— JSON 读写(对齐 pandas read_json / to_json,基于 jackson)
// │  Why  : 规范 02 §3.3;JSON 5 种 orient(records/columns/values/index/split)是 pandas 数据交换核心
// │  Who  : 用户经 Jian.readJson 入口,或直接 Json.read/write
// │  When : API 响应、配置文件、跨语言数据交换
// │  Where: jian-io-json/Json.java
// │  How  : 数据走向:
// │           读:Path/字符串 → jackson ObjectMapper → JsonNode → 按 orient 解析 → DataFrame;
// │           写:DataFrame → 按 orient 构造 ObjectNode/ArrayNode → JSON 字符串/文件。
// │         关键变量变化:
// │           - orient:决定 JSON 结构(records=[{col:val}]/columns={col:[vals]}/...);
// │           - 日期:默认 ISO-8601 字符串(M3 不做复杂日期解析)。
// │         逻辑路线:
// │           路径 A(读 records)→ array of object,每个 object 一行;
// │           路径 B(读 columns)→ object of array,每个 key 是列;
// │           路径 C(读 values)→ array of array,纯值(取首行作列名 or _0/_1);
// │           路径 D(读 index/split)→ split 含 columns+index+data 三段。
/**
 * JSON 读写,对齐 pandas.read_json / to_json。
 *
 * <p>支持 pandas 全部 5 种 orient:
 * <ul>
 *   <li>{@code RECORDS}(默认):{@code [{"a":1,"b":2},...]} —— 最常用,适合行列表;</li>
 *   <li>{@code COLUMNS}:{@code {"a":[1,...],"b":[...]}} —— 列存;</li>
 *   <li>{@code VALUES}:{@code [[1,2],[3,4]]} —— 纯二维数组,首行可作列名;</li>
 *   <li>{@code INDEX}:{@code {"a":{"0":1,"1":3},"b":{...}}} —— 带行索引的列存;</li>
 *   <li>{@code SPLIT}:{@code {"columns":["a","b"],"index":[...],"data":[[1,2]]}} —— 完整 schema + 数据。</li>
 * </ul>
 */
public final class Json {

    private Json() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Orient { RECORDS, COLUMNS, VALUES, INDEX, SPLIT }

    // ======================== 读 ========================

    /**
     * 读 JSON 的 builder(默认 RECORDS orient)。
     * @param path String JSON 文件路径,需为合法可读文件,不允许 null
     * @return JsonReader 配置器,链式调用 .orient 后 .go() 执行
     */
    public static JsonReader read(String path) { return new JsonReader(Path.of(path)); }

    public static final class JsonReader {
        private final Path path;
        private Orient orient = Orient.RECORDS;

        JsonReader(Path p) { this.path = p; }

        /**
         * 设置 JSON 结构 orient。
         * @param o Orient JSON 结构枚举,取值 RECORDS/COLUMNS/VALUES/INDEX/SPLIT,默认 RECORDS
         * @return JsonReader 当前配置器,便于链式调用
         */
        public JsonReader orient(Orient o) { this.orient = o; return this; }

        public DataFrame go() throws IOException {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            // 因为 Jackson 不吃 BOM(带头 U+FEFF 的文件直接 JsonParseException),
            // 而 Csv 读路径已剥,所以统一口径先剥 UTF-8 BOM。
            if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                content = content.substring(1);
            }
            // 默认 RECORDS 但顶层是 object 且值全为数组 → 自动按 COLUMNS 解析
            // (配合写侧 0 行切 COLUMNS;也友好兼容用户手工传入的 columns 形态文件)
            Orient eff = orient;
            if (orient == Orient.RECORDS && !content.isEmpty() && content.charAt(0) == '{') {
                try {
                    com.fasterxml.jackson.databind.JsonNode n = MAPPER.readTree(content);
                    boolean allArr = n.isObject() && !n.isEmpty();
                    for (com.fasterxml.jackson.databind.JsonNode v : n) {
                        if (!v.isArray()) { allArr = false; break; }
                    }
                    if (allArr) eff = Orient.COLUMNS;
                } catch (IOException ignore) { /* 走默认路径报原有错误 */ }
            }
            return parse(content, eff);
        }
    }

    /**
     * 从字符串解析(供 Jian.jsonNormalize 等复用)。
     * @param json String JSON 文本内容,需符合所选 orient 的结构,不允许 null
     * @param orient Orient JSON 结构枚举,取值 RECORDS/COLUMNS/VALUES/INDEX/SPLIT
     * @return DataFrame 解析出的数据帧(列名与类型按 orient 语义和值推断)
     * @throws IOException JSON 格式错误或与 orient 不匹配时抛出
     */
    public static DataFrame parse(String json, Orient orient) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        switch (orient) {
            case RECORDS: return parseRecords(root);
            case COLUMNS: return parseColumns(root);
            case VALUES: return parseValues(root);
            case INDEX: return parseIndex(root);
            case SPLIT: return parseSplit(root);
            default: throw new IllegalArgumentException("未知 orient: " + orient);
        }
    }

    /** records:[{col:val},...]。 */
    private static DataFrame parseRecords(JsonNode root) {
        if (!root.isArray()) throw new IllegalArgumentException("RECORDS orient 要求顶层数组");
        ArrayNode arr = (ArrayNode) root;
        if (arr.isEmpty()) return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
        // 因为数组元素非 object 时(如 "[1,2,3]")fieldNames() 返回空迭代器,
        // 列集收集会静默跳过、rows 全 null,返回"空列名 DataFrame"且数据全部丢弃无报错
        // (pandas read_json 对此抛清晰异常),所以这里 fail-fast 给出明确错误。
        for (int r = 0; r < arr.size(); r++) {
            JsonNode el = arr.get(r);
            if (!el.isObject()) {
                throw new IllegalArgumentException("仅支持对象元素的 records 数组:第 " + r
                        + " 个元素是 " + el.getNodeType() + "(" + el + "),records orient 要求 [{列:值},...]");
            }
        }
        // 因为只取首对象 keys 会把后续对象的额外键静默丢弃,
        // 所以列集取全部对象的键并集(保序,对齐 pandas json_normalize)。
        LinkedHashSet<String> nameSet = new LinkedHashSet<>();
        for (JsonNode obj : arr) {
            Iterator<String> it = obj.fieldNames();
            while (it.hasNext()) nameSet.add(it.next());
        }
        List<String> names = new ArrayList<>(nameSet);
        Object[][] rows = new Object[arr.size()][names.size()];
        for (int r = 0; r < arr.size(); r++) {
            JsonNode obj = arr.get(r);
            for (int c = 0; c < names.size(); c++) rows[r][c] = nodeToValue(obj.get(names.get(c)));
        }
        return DataFrame.of(Schema.infer(names, rows), rows);
    }

    /** columns:{col:[vals],...}。 */
    private static DataFrame parseColumns(JsonNode root) {
        if (!root.isObject()) throw new IllegalArgumentException("COLUMNS orient 要求顶层对象");
        Map<String, Object[]> cols = new LinkedHashMap<>();
        int n = -1;
        Iterator<String> it = root.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            JsonNode arr = root.get(name);
            if (!arr.isArray()) throw new IllegalArgumentException("COLUMNS orient 每个值须数组");
            Object[] vals = new Object[arr.size()];
            for (int i = 0; i < arr.size(); i++) vals[i] = nodeToValue(arr.get(i));
            // 因为若只取首列长度、后续列长度不等时静默通过,下游 get(r,c) 会抛裸
            // AIOOBE 且根因不可见,所以这里做列间长度校验,对齐 pandas
            // "All arrays must be of the same length" 的清晰报错
            if (n < 0) n = vals.length;
            else if (vals.length != n) {
                throw new IllegalArgumentException("COLUMNS orient 列 '" + name + "' 长度 " + vals.length
                        + " ≠ 首列长度 " + n + "(所有列必须等长)");
            }
            cols.put(name, vals);
        }
        return DataFrame.ofColumns(cols);
    }

    /** values:[[1,2],[3,4]]。 */
    private static DataFrame parseValues(JsonNode root) {
        if (!root.isArray()) throw new IllegalArgumentException("VALUES orient 要求顶层数组");
        ArrayNode arr = (ArrayNode) root;
        if (arr.isEmpty()) return DataFrame.of(new Schema(List.of(), List.of()), new Object[0][]);
        int cols = arr.get(0).size();
        List<String> names = new ArrayList<>();
        for (int c = 0; c < cols; c++) names.add("_" + c);
        Object[][] rows = new Object[arr.size()][cols];
        for (int r = 0; r < arr.size(); r++) {
            JsonNode row = arr.get(r);
            // 因为行宽与首行不一致时静默截断会丢数据,所以抛 IAE 明确报错
            if (row.size() != cols) {
                throw new IllegalArgumentException("VALUES 第 " + r + " 行宽 " + row.size()
                    + " ≠ 首行宽 " + cols + "(orient=values 要求等宽行)");
            }
            for (int c = 0; c < cols; c++) rows[r][c] = nodeToValue(row.get(c));
        }
        return DataFrame.of(Schema.infer(names, rows), rows);
    }

    /** index:{"col":{"0":val,"1":val},...} —— 带 index 的列存,index 忽略(用位置)。 */
    private static DataFrame parseIndex(JsonNode root) {
        if (!root.isObject()) throw new IllegalArgumentException("INDEX orient 要求顶层对象");
        Map<String, Object[]> cols = new LinkedHashMap<>();
        Iterator<String> it = root.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            JsonNode obj = root.get(name);
            if (!obj.isObject()) throw new IllegalArgumentException("INDEX orient 每列须是 {idx:val}");
            // 收集 values(按 key 排序作为行序;key 是行索引字符串)
            List<String> idxKeys = new ArrayList<>();
            Iterator<String> kit = obj.fieldNames();
            while (kit.hasNext()) idxKeys.add(kit.next());
            // 因为键全为数字串时若按字典序排序会得 "0","1","10","2" 的错位行序,
            // 所以数字键按【数值】排序;文本键保持字典序(pandas 对文本键也字典序,实测一致)。
            idxKeys.sort(indexKeyComparator(idxKeys));
            Object[] vals = new Object[idxKeys.size()];
            for (int i = 0; i < idxKeys.size(); i++) vals[i] = nodeToValue(obj.get(idxKeys.get(i)));
            cols.put(name, vals);
        }
        return DataFrame.ofColumns(cols);
    }

    /**
     * INDEX 键排序器:全数字串键按数值排序("0","1","2","10"),
     * 否则(含文本键)保持字典序。数据走向:keys 尝试全转 long → 成功返数值比较器,失败返字典序。
     */
    private static java.util.Comparator<String> indexKeyComparator(List<String> keys) {
        boolean allNumeric = !keys.isEmpty();
        for (String k : keys) {
            if (!k.matches("-?\\d+")) { allNumeric = false; break; }
        }
        if (!allNumeric) return String::compareTo;
        // 因为全数字串但超出 long 范围(如 9223372036854775808)时 parseLong 会抛
        // 裸 NFE 中断整个 parse,所以先预检可解析性,超范围降级字典序(pandas 回退语义)
        for (String k : keys) {
            try { Long.parseLong(k); }
            catch (NumberFormatException overflow) { return String::compareTo; }
        }
        return java.util.Comparator.comparingLong(Long::parseLong);
    }

    /** split:{"columns":[...],"index":[...],"data":[[...],...]}。 */
    private static DataFrame parseSplit(JsonNode root) {
        if (!root.isObject()) throw new IllegalArgumentException("SPLIT orient 要求顶层对象");
        JsonNode colsNode = root.get("columns");
        JsonNode dataNode = root.get("data");
        if (colsNode == null || dataNode == null) {
            throw new IllegalArgumentException("SPLIT orient 须含 columns 和 data");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode n : colsNode) names.add(n.asText());
        Object[][] rows = new Object[dataNode.size()][names.size()];
        for (int r = 0; r < dataNode.size(); r++) {
            JsonNode row = dataNode.get(r);
            // 因为行宽 > 列数时静默截断会丢数据(pandas 对齐抛 ValueError),
            // 所以多余数据抛错;短行缺键填 null 保持(pandas 同款)。
            if (row.size() > names.size()) {
                throw new IllegalArgumentException("SPLIT 第 " + r + " 行宽 " + row.size()
                    + " > 列数 " + names.size() + "(pandas 对齐:多余数据抛错不静默截断)");
            }
            for (int c = 0; c < names.size(); c++) rows[r][c] = nodeToValue(row.get(c));
        }
        return DataFrame.of(Schema.infer(names, rows), rows);
    }

    /** JsonNode → Java 值。 */
    private static Object nodeToValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isInt()) return node.intValue();
        if (node.isLong()) return node.longValue();
        // 因为超 long 范围的整数(BIGINT token)isInt/isLong 均为 false,
        // 所以显式处理:能转 long 转 long;超范围归字符串(经 Schema.infer 归 STRING 列,
        // 对齐 pandas read_csv 超 int64 → object)。
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? node.longValue() : node.toString();
        }
        if (node.isDouble()) return node.doubleValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isTextual()) return node.textValue();
        // 复杂类型(对象/数组)保留为 toString(M3 简化;v2 用 OBJECT 列存 JsonNode)
        return node.toString();
    }

    // ======================== json_normalize(拍平嵌套 JSON,对齐 pandas.json_normalize)========================

    /**
     * 拍平嵌套 JSON(对齐 pandas.json_normalize,规范 02 §2.1/§3.3)。
     *
     * <p>按点号路径(如 {@code "results.items"})定位到对象数组,把每个元素的嵌套对象
     * 拍平为"点号分隔"的列;嵌套对象数组按下标展开({@code items.0.name})。
     * <pre>{@code
     * DataFrame df = Json.normalize("{\"results\":{\"items\":[{\"a\":1,\"o\":{\"x\":2}}]}}", "results.items");
     * // 列:a, o.x
     * }</pre>
     *
     * @param json JSON 字符串
     * @param recordPath 点号路径(逐层取对象字段,最后必须落到数组)
     */
    public static DataFrame normalize(String json, String recordPath) throws IOException {
        JsonNode rootP = MAPPER.readTree(json);
        return normalize(rootP, recordPath);
    }

    /**
     * normalize 变参重载:逐段路径,
     * 段本身可含 "."(pandas json_normalize(record_path=["a","b.c"]) 同款能力,
     * 点号字符串入口无法表达含 "." 的 key)。
     * <pre>{@code
     * Json.normalize(json, "meta", "b.c")   // 逐层取 meta → "b.c"
     * }</pre>
     * @param json JSON 字符串,非 null
     * @param pathSegments String... 路径段,逐层取对象字段;空数组 = 根(输入须为数组)
     * @return DataFrame 拍平结果
     */
    public static DataFrame normalize(String json, String... pathSegments) throws IOException {
        JsonNode arr = MAPPER.readTree(json);
        // 变参:逐段 get,段本身不拆点(与字符串路径的关键差异 —— 段可含 ".")
        for (String part : pathSegments) {
            if (arr == null) break;
            arr = arr.get(part);
        }
        return normalizeArray(arr, String.join(".", pathSegments));
    }

    private static DataFrame normalize(JsonNode root, String recordPath) {
        // 数据走向:root → 按点号路径逐层 get → 数组 arr → 逐元素拍平 → Map 列表 → Object[][] → DataFrame
        // recordPath 为 "$"、空或 null 时,输入本身就是数组(pandas normalize 默认语义)。
        // 因为 recordPath.isBlank() 对 null 会直接 NPE,所以显式判 null 视为根。
        JsonNode arr = root;
        if (recordPath != null && !recordPath.isBlank() && !recordPath.equals("$")) {
            for (String part : recordPath.split("\\.")) {
                if (arr == null) break;
                arr = arr.get(part);
            }
        }
        return normalizeArray(arr, recordPath);
    }

    /** 已定位到数组节点后的拍平主流程(字符串路径与变参路径共用,displayPath 仅用于报错展示)。 */
    private static DataFrame normalizeArray(JsonNode arr, String displayPath) {
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("jsonNormalize:路径 '" + displayPath + "' 未找到数组");
        }
        // 第一遍:拍平所有元素,收集全量键集合(union,保证列一致)
        List<Map<String, Object>> flat = new ArrayList<>();
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (JsonNode el : arr) {
            if (!el.isObject()) {
                throw new IllegalArgumentException("jsonNormalize:数组元素须为对象,实际 " + el.getNodeType());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            flattenNode(el, "", m, keys);
            flat.add(m);
        }
        // 第二遍:按 keys 顺序填表(缺失的键补 null)
        List<String> names = new ArrayList<>(keys);
        Object[][] data = new Object[flat.size()][names.size()];
        for (int r = 0; r < flat.size(); r++) {
            Map<String, Object> m = flat.get(r);
            for (int c = 0; c < names.size(); c++) data[r][c] = m.get(names.get(c));
        }
        return DataFrame.of(Schema.infer(names, data), data);
    }

    /** 递归拍平:对象 → 点号前缀递归;标量数组 → 原样存 List;对象数组 → 下标展开。 */
    private static void flattenNode(JsonNode node, String prefix, Map<String, Object> out,
                                    java.util.Set<String> keys) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    flattenNode(e.getValue(), prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(),
                            out, keys));
        } else if (node.isArray()) {
            boolean allObj = !node.isEmpty() && node.get(0).isObject();
            if (allObj) {
                for (int i = 0; i < node.size(); i++) {
                    flattenNode(node.get(i), prefix + "." + i, out, keys);
                }
            } else {
                // 标量数组:整列存 List(Object dtype)
                List<Object> vals = new ArrayList<>();
                node.forEach(n -> vals.add(nodeToValue(n)));
                putFlat(prefix, vals, out, keys);
            }
        } else {
            putFlat(prefix, nodeToValue(node), out, keys);
        }
    }

    private static void putFlat(String key, Object val, Map<String, Object> out, java.util.Set<String> keys) {
        out.put(key, val);
        keys.add(key);
    }

    // ======================== 写 ========================

    /**
     * 写 JSON 的 builder(默认 RECORDS orient)。
     * @param df DataFrame 要写出的数据帧,不允许 null
     * @param path String 输出 JSON 文件路径,需为合法可写路径,不允许 null
     * @return JsonWriter 配置器,链式调用 .orient 后 .go() 执行
     */
    public static JsonWriter write(DataFrame df, String path) { return new JsonWriter(df, Path.of(path)); }

    public static final class JsonWriter {
        private final DataFrame df;
        private final Path path;
        private Orient orient = Orient.RECORDS;

        JsonWriter(DataFrame df, Path p) { this.df = df; this.path = p; }

        /**
         * 设置 JSON 结构 orient。
         * @param o Orient JSON 结构枚举,取值 RECORDS/COLUMNS/VALUES/INDEX/SPLIT,默认 RECORDS
         * @return JsonWriter 当前配置器,便于链式调用
         */
        public JsonWriter orient(Orient o) { this.orient = o; return this; }

        public void go() throws IOException {
            // 因为 0 行 df 用 RECORDS 会写出 "[]" 丢失全部列(pandas records orient 同样丢,
            // 但 pandas 默认 orient=columns 保留),所以 0 行时自动切 COLUMNS 形态
            // {"a":[],"b":[]}(读侧自动检测),列元数据不丢;非 0 行不受影响。
            Orient effective = (df.rowCount() == 0 && orient == Orient.RECORDS) ? Orient.COLUMNS : orient;
            String json = toJsonString(df, effective);
            Files.writeString(path, json, StandardCharsets.UTF_8);
        }
    }

    /**
     * DataFrame → JSON 字符串(指定 orient)。
     * @param df DataFrame 要序列化的数据帧,不允许 null
     * @param orient Orient JSON 结构枚举,取值 RECORDS/COLUMNS/VALUES/INDEX/SPLIT
     * @return String 指定 orient 的 JSON 文本(UTF-8 语义);NaN/Infinity 按 null 输出以兼容标准 JSON
     * @throws IOException 序列化过程发生 IO 错误时抛出
     */
    public static String toJsonString(DataFrame df, Orient orient) throws IOException {
        switch (orient) {
            case RECORDS: return writeRecords(df);
            case COLUMNS: return writeColumns(df);
            case VALUES: return writeValues(df);
            case SPLIT: return writeSplit(df);
            case INDEX: return writeIndex(df);
            default: throw new IllegalArgumentException("未知 orient: " + orient);
        }
    }

    private static String writeRecords(DataFrame df) throws IOException {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Object[] row : df.iterRows()) {
            ObjectNode obj = arr.addObject();
            List<String> names = df.columnNames();
            for (int c = 0; c < names.size(); c++) putValue(obj, names.get(c), row[c]);
        }
        return MAPPER.writeValueAsString(arr);
    }

    private static String writeColumns(DataFrame df) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        List<String> names = df.columnNames();
        for (String name : names) {
            ArrayNode arr = root.putArray(name);
            Column col = df.getColumn(name);
            for (int r = 0; r < df.rowCount(); r++) addValue(arr, col.get(r));
        }
        return MAPPER.writeValueAsString(root);
    }

    private static String writeValues(DataFrame df) throws IOException {
        ArrayNode arr = MAPPER.createArrayNode();
        List<String> names = df.columnNames();
        for (Object[] row : df.iterRows()) {
            ArrayNode inner = arr.addArray();
            for (int c = 0; c < names.size(); c++) addValue(inner, row[c]);
        }
        return MAPPER.writeValueAsString(arr);
    }

    private static String writeSplit(DataFrame df) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode cols = root.putArray("columns");
        for (String n : df.columnNames()) cols.add(n);
        ArrayNode idx = root.putArray("index");
        for (int r = 0; r < df.rowCount(); r++) idx.add(r);
        ArrayNode data = root.putArray("data");
        List<String> names = df.columnNames();
        for (Object[] row : df.iterRows()) {
            ArrayNode inner = data.addArray();
            for (int c = 0; c < names.size(); c++) addValue(inner, row[c]);
        }
        return MAPPER.writeValueAsString(root);
    }

    private static String writeIndex(DataFrame df) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        List<String> names = df.columnNames();
        for (String name : names) {
            ObjectNode col = root.putObject(name);
            Column c = df.getColumn(name);
            for (int r = 0; r < df.rowCount(); r++) putValue(col, String.valueOf(r), c.get(r));
        }
        return MAPPER.writeValueAsString(root);
    }

    private static void putValue(ObjectNode obj, String key, Object v) {
        if (v == null) obj.putNull(key);
        else if (v instanceof Integer) obj.put(key, (Integer) v);
        else if (v instanceof Long) obj.put(key, (Long) v);
        else if (v instanceof Double d) {
            // 因为 NaN/Infinity 在标准 JSON 不允许,Jackson 默认会抛异常或写成非数字 token
            // 导致读回类型损坏(如 Infinity 变字符串),所以统一按"缺失"语义输出 null
            // (与 jian-core 的 DataFrame 缺失处理一致)。
            if (Double.isNaN(d) || Double.isInfinite(d)) obj.putNull(key);
            else obj.put(key, d);
        }
        else if (v instanceof Float f) {
            if (Float.isNaN(f) || Float.isInfinite(f)) obj.putNull(key);
            else obj.put(key, f);
        }
        else if (v instanceof java.math.BigInteger bi) {
            obj.put(key, bi);   // BigInteger 精确写出,不降 double 丢精度
        }
        else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) obj.putNull(key);
            else obj.put(key, d);
        }
        else if (v instanceof Boolean) obj.put(key, (Boolean) v);
        else obj.put(key, String.valueOf(v));
    }

    private static void addValue(ArrayNode arr, Object v) {
        if (v == null) arr.addNull();
        else if (v instanceof Integer) arr.add((Integer) v);
        else if (v instanceof Long) arr.add((Long) v);
        else if (v instanceof Double d) {
            // 同 putValue:NaN/Infinity 转 null(标准 JSON 兼容)
            if (Double.isNaN(d) || Double.isInfinite(d)) arr.addNull();
            else arr.add(d);
        }
        else if (v instanceof Float f) {
            if (Float.isNaN(f) || Float.isInfinite(f)) arr.addNull();
            else arr.add(f);
        }
        else if (v instanceof java.math.BigInteger bi) {
            arr.add(bi);   // BigInteger 精确写出,不降 double 丢精度
        }
        else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) arr.addNull();
            else arr.add(d);
        }
        else if (v instanceof Boolean) arr.add((Boolean) v);
        else arr.add(String.valueOf(v));
    }
}
