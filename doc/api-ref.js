// 方法卡数据(改示例只改这个文件)
const API_REF = [
  {
    id:'ref-dataframe-of',module:'jian-core',since:'v1.0',status:'stable',
    sig:'DataFrame.of(Schema, Object[][])',
    summary:'构造 DataFrame。Schema 指定列名+类型(9种dtype),rows 传行数据(null=缺失)。构造后链式调用 query/sortBy/groupBy/merge/pivot 等。适用于:内存数据建表。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.core.*;\npublic class Demo {\n  public static void main(String[] args) {\n    // 定义列名和类型\n    Schema schema = Schema.of(\n        "id", DType.LONG,    // 整数(大ID不丢精度)\n        "name", DType.STRING, // 文本\n        "score", DType.DOUBLE);// 浮点(NaN表缺失)\n    Object[][] rows = {\n        {1L, "alice", 90.5},\n        {2L, "bob", 85.0},\n        {3L, "carol", 76.5}};\n    DataFrame df = DataFrame.of(schema, rows);\n    // 链式变换\n    DataFrame r = df.query("score > 80")\n        .sortBy("score", false).head(2);\n    System.out.println(r);\n  }\n}',throws:[]
  },
  {
    id:'ref-merge',module:'jian-core',since:'v1.0',status:'stable',
    sig:'df.merge(right, how, on) / df.merge(right, how, leftOn[], rightOn[])',
    summary:'关系join,对齐pandas.merge。4种模式:inner(内连接)/left(左全保留)/right(右全保留)/outer(全保留)。多列键join用leftOn[]/rightOn[]。重名列用suffixes(_x/_y)。适用于:多表关联。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.core.*;\npublic class Demo {\n  public static void main(String[] args) {\n    DataFrame users = DataFrame.of(\n        Schema.of("uid",LONG,"name",STRING,"did",STRING),\n        new Object[][]{{1L,"alice","RD"},{2L,"bob","PM"}});\n    DataFrame depts = DataFrame.of(\n        Schema.of("did",STRING,"dname",STRING),\n        new Object[][]{{"RD","研发"},{"PM","产品"}});\n    // inner join(键列名不同用 leftOn/rightOn)\n    DataFrame r = users.merge(depts, "inner",\n        new String[]{"did"}, new String[]{"did"}, null);\n    System.out.println(r);\n  }\n}',throws:[]
  },
  {
    id:'ref-pivot',module:'jian-core',since:'v1.0',status:'stable',
    sig:'df.pivotTable(index, columns, values, aggFn) / df.melt(idVars, valueVars) / df.T()',
    summary:'重塑,对齐pandas.pivot_table/melt/transpose。pivotTable:长转宽(index作行,columns散开成多列)。melt:宽转长。T:行列转置。适用于:交叉表、宽表转长表绘图。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.core.*;\npublic class Demo {\n  public static void main(String[] args) {\n    DataFrame df = DataFrame.of(\n        Schema.of("dept",STRING,"month",STRING,"sales",DOUBLE),\n        new Object[][]{{"RD","1月",100.0},{"RD","2月",200.0},\n                       {"PM","1月",150.0},{"PM","2月",250.0}});\n    // 长转宽\n    DataFrame wide = df.pivotTable("dept","month","sales","sum");\n    System.out.println(wide);\n    // 宽转长\n    DataFrame melted = wide.melt(new String[]{"dept"},\n        new String[]{"1月","2月"});\n    System.out.println("melt: " + melted.rowCount() + "行");\n  }\n}',throws:[]
  },
  {
    id:'ref-series',module:'jian-core',since:'v1.0',status:'stable',
    sig:'df.getSeries(col) / Series.rolling(n).mean() / Series.ewm(alpha).mean()',
    summary:'Series+窗口族,对齐pandas.Series。rolling(n)滚动窗口(mean/std/min/max)。expanding()累积。ewm(alpha)指数加权(EWMA)。str()字符串accessor。dt()时间accessor(year/month/day)。适用于:时间序列、滚动指标。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.core.*;\npublic class Demo {\n  public static void main(String[] args) {\n    DataFrame df = DataFrame.of(\n        Schema.of("price",DOUBLE),\n        new Object[][]{{10.0},{20.0},{30.0},{40.0},{50.0}});\n    Series s = df.getSeries("price");\n    // 统计\n    System.out.println("mean=" + s.mean());\n    // 滚动均值(2日均线)\n    double[] ma2 = s.rolling(2).mean();\n    System.out.println("MA2[1]=" + ma2[1]);  // 15.0\n    // 指数加权(EWMA)\n    double[] ewma = s.ewm(0.5).mean();\n    System.out.println("EWMA[0]=" + ewma[0]); // 10.0\n  }\n}',throws:[]
  },
  {
    id:'ref-groupBy',module:'jian-core',since:'v1.0',status:'stable',
    sig:'df.groupBy(cols).agg(Map) / .transform(col, fn)',
    summary:'分组聚合,对齐pandas.groupby。agg:每组算统计量(mean/sum/count/min/max/std/median)。transform:广播回原行序(每人与组均值的差)。适用于:按部门算平均薪资等。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.core.*;\nimport java.util.*;\npublic class Demo {\n  public static void main(String[] args) {\n    DataFrame df = DataFrame.of(\n        Schema.of("dept",STRING,"name",STRING,"salary",DOUBLE),\n        new Object[][]{{"RD","alice",10000.0},{"PM","bob",8000.0},\n                       {"RD","carol",12000.0},{"PM","dave",9000.0}});\n    Map<String,String> spec = new LinkedHashMap<>();\n    spec.put("salary","mean"); spec.put("name","count");\n    DataFrame agg = df.groupBy("dept").agg(spec);\n    System.out.println(agg);\n    // transform:每人薪资与部门均值的差\n    double[] avg = df.groupBy("dept").transform("salary","mean");\n    System.out.println("alice差值=" + (10000-avg[0]));\n  }\n}',throws:[]
  },
  {
    id:'ref-query',module:'jian-dsl',since:'v1.0',status:'stable',
    sig:'Dsl.query(df, expr) / Dsl.eval(df, expr) / Dsl.sql(sql, df...)',
    summary:'三档DSL引擎。L1 query:布尔过滤(in/not in/like 防注入/between/is null)。L2 eval:派生列(三元/nvl/coalesce/ifnull)。L3 sql:内存SQL(DISTINCT/LIMIT OFFSET/GROUP/HAVING/ORDER/JOIN4种/UNION ALL/子查询≤2层)。统一API:sql在前df在后,${名}作表名。df.query()/df.eval()/df.sql() 经 SPI 自动接管。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.dsl.Dsl;\nimport jian.core.*;\npublic class Demo {\n  public static void main(String[] args) {\n    DataFrame df = DataFrame.of(\n        Schema.of("name",STRING,"age",LONG,"city",STRING,"salary",DOUBLE),\n        new Object[][]{{"alice",30L,"SH",10000.0},{"bob",25L,"BJ",8000.0}});\n    // L1 query\n    System.out.println("L1:" + Dsl.query(df,"age > 28").rowCount() + "行");\n    // L2 eval(三元)\n    DataFrame g = Dsl.eval(df,"level = age >= 30 ? \'SENIOR\' : \'JUNIOR\'");\n    System.out.println("L2:" + g.getStringColumn("level").get(0));\n    // L3 SQL单表\n    DataFrame r = Dsl.sql("SELECT city, mean(salary) FROM ${t} GROUP BY city", df);\n    System.out.println("L3:" + r.getStringColumn("city").get(0));\n    // L3 多表JOIN\n    DataFrame df2 = DataFrame.of(Schema.of("city",STRING,"region",STRING),\n        new Object[][]{{"SH","East"},{"BJ","North"}});\n    DataFrame j = Dsl.sql("SELECT * FROM ${a} JOIN ${b} ON a.city=b.city", df, df2);\n    System.out.println("JOIN:" + j.rowCount() + "行");\n  }\n}',throws:[]
  },
  {
    id:'ref-jian',module:'jian-facade',since:'v1.0',status:'stable',
    sig:'Jian.readCsv/readJson/readExcel/readSql(path) / Jian.toCsv/toJson/toExcel/toSql(df, path)',
    summary:'顶层门面,引一个jar得全部能力。pandas风格命名:readCsv/readJson/readExcel/readHtml/readXml/readParquet/readSql读入,toCsv/toJson/toExcel/toXml/toParquet/toSql写出。read/write按扩展名自动分发。sql在DataFrame上跑内存SQL。适用于:快速读写分析。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.Jian;\nimport jian.core.DataFrame;\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    // pandas风格读\n    DataFrame df = Jian.readCsv("data.csv");\n    // 内存SQL\n    DataFrame r = Jian.sql("SELECT city, avg(score) FROM ${t} GROUP BY city", df);\n    // pandas风格写\n    Jian.toHtml(r, "report.html");\n    Jian.toExcel(r, "out.xlsx");\n  }\n}',throws:[]
  },
  {
    id:'ref-readCsv',module:'jian-io-csv',since:'v1.0',status:'stable',
    sig:'Csv.read(path) / Csv.read(path).delimiter/header/allString/schema().go() / Csv.write(df, path)',
    summary:'CSV/TSV/FWF读写。read(path) 无配置直接读(自动推断类型),需要配置时 read(path).delimiter/header/encoding/allString/schema().go()。FWF用readFwf.widths()。write默认开启公式注入防护(值以 = + - @ 开头时前缀 \' 防 Excel 当公式)。适用于:读CSV报表、TSV日志。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.io.csv.Csv;\nimport jian.core.*;\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    DataFrame df = DataFrame.of(\n        Schema.of("id",LONG,"name",STRING,"phone",LONG),\n        new Object[][]{{1L,"alice",13800000000L}});\n    // 写(直接执行)\n    Csv.write(df, "out.csv");\n    // 读(默认自动推断)\n    DataFrame r1 = Csv.read("out.csv");\n    // 读TSV(配置分隔符+go())\n    DataFrame r2 = Csv.read("data.tsv").delimiter(\'\\t\').go();\n    // 读(全部字符串,手机号不转数字)\n    DataFrame r3 = Csv.read("phones.csv").allString(true).go();\n    // 读(指定列类型)\n    Schema s = Schema.of("id",LONG,"name",STRING);\n    DataFrame r4 = Csv.read("out.csv").schema(s).go();\n  }\n}',throws:[]
  },
  {
    id:'ref-readExcel',module:'jian-io-excel',since:'v1.0',status:'stable',
    sig:'Excel.read(path) / Excel.read(path).sheet(name).go() / Excel.write(df, path)',
    summary:'Excel读写(POI 5.5.1)。逐列类型精确推断:整数->LONG(手机号完整),小数->DOUBLE,混合->STRING("42"不变"42.0"),日期->ISO。表头重名自动加_1_2。空行自动跳过。多sheet用ExcelMultiWriter。适用于:读Excel报表。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.io.excel.Excel;\nimport jian.core.*;\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    DataFrame df = DataFrame.of(\n        Schema.of("id",LONG,"name",STRING,"phone",LONG),\n        new Object[][]{{1L,"alice",13800000000L}});\n    // 写(直接执行)\n    Excel.write(df, "out.xlsx");\n    // 读(默认第一个sheet)\n    DataFrame r = Excel.read("out.xlsx");\n    // 读(指定sheet名+go())\n    DataFrame r2 = Excel.read("out.xlsx").sheet("Sheet1").go();\n    // 验证手机号完整\n    Object p = r.getColumn("phone").get(0);\n    System.out.println("phone=" + p + "(" + p.getClass().getSimpleName() + ")");\n    // 枚举sheet\n    System.out.println("sheets: " + Excel.sheetNames("out.xlsx"));\n  }\n}',throws:[]
  },
  {
    id:'ref-readJson',module:'jian-io-json',since:'v1.0',status:'stable',
    sig:'Json.read(path) / Json.read(path).orient(Orient).go() / Json.write(df, path) / Json.parse(jsonStr, orient)',
    summary:'JSON读写,5种orient:RECORDS(行列表,默认)/COLUMNS(列存)/VALUES(纯二维)/INDEX(带索引)/SPLIT(columns+index+data)。parse()从字符串解析(适合API响应)。适用于:读REST API返回、NoSQL导出。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.io.json.Json;\nimport jian.core.*;\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    DataFrame df = DataFrame.of(\n        Schema.of("id",LONG,"name",STRING),\n        new Object[][]{{1L,"alice"},{2L,"bob"}});\n    // 写(默认RECORDS)\n    Json.write(df, "out.json");\n    // 读(默认RECORDS)\n    DataFrame r1 = Json.read("out.json");\n    // 读COLUMNS orient\n    DataFrame r2 = Json.read("cols.json").orient(Json.Orient.COLUMNS).go();\n    // 从字符串解析(适合API响应)\n    String json = "[{\\"id\\":1,\\"name\\":\\"alice\\"}]";\n    DataFrame r3 = Json.parse(json, Json.Orient.RECORDS);\n    System.out.println(r3.getStringColumn("name").get(0));\n  }\n}',throws:[]
  },
  {
    id:'ref-readSql',module:'jian-io-sql',since:'v1.0',status:'stable',
    sig:'Sql.readSql(conn, sql, params...) / Sql.readSqlTable(conn, table) / Sql.toSql(df, conn, table, mode)',
    summary:'数据库读写,对齐pandas.read_sql/to_sql。readSql:执行SQL查询(支持?参数化防注入)。readSqlTable:读整张表。toSql:写DataFrame到数据库(自动建表+批量INSERT)。4种模式:OVERWRITE(删表重建)/APPEND(追加,表须存在)/CREATE_OR_REPLACE(默认,删旧建新)/FAIL_IF_EXISTS(存在报错)。列类型自动映射(LONG->BIGINT等)。支持7库。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.io.sql.Sql;\nimport jian.core.*;\nimport java.sql.*;\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    try (Connection conn = DriverManager.getConnection(\n        "jdbc:h2:mem:test", "sa", "")) {\n      // 建表\n      try (var st = conn.createStatement()) {\n        st.execute("CREATE TABLE users (id BIGINT, name VARCHAR(100), score DOUBLE)");\n        st.execute("INSERT INTO users VALUES (1,\'alice\',90.5),(2,\'bob\',85.0)");\n      }\n      // readSqlTable:读整张表\n      DataFrame all = Sql.readSqlTable(conn, "users");\n      System.out.println("全部:" + all.rowCount() + "行");\n      // readSql:参数化查询(?防注入,80.0对应第一个?)\n      DataFrame high = Sql.readSql(conn, "SELECT * FROM users WHERE score > ?", 80.0);\n      // toSql:写入(默认CREATE_OR_REPLACE=删旧建新插入)\n      DataFrame data = DataFrame.of(\n          Schema.of("id",LONG,"name",STRING,"score",DOUBLE),\n          new Object[][]{{3L,"carol",76.5}});\n      Sql.toSql(data, conn, "new_users");\n      // toSql+APPEND模式(追加到已有表,表须存在)\n      Sql.toSql(data, conn, "users", Sql.Mode.APPEND);\n      // 模式说明:\n      //   OVERWRITE: 删表重建(危险,丢旧数据)\n      //   APPEND: 追加(表须存在,不建表)\n      //   CREATE_OR_REPLACE(默认): DROP IF EXISTS + CREATE + INSERT\n      //   FAIL_IF_EXISTS: 表存在则报错\n      // 写分析结果到新表\n      Sql.toSql(all.describe(), conn, "stats");\n    }\n  }\n}',throws:[]
  },
  {
    id:'ref-plot',module:'jian-viz',since:'v1.0',status:'stable',
    sig:'Plot.line/scatter/bar/hist/box(df, x, y) / Plot.savePng/saveSvg(chart, path)',
    summary:'13种图表,对齐pandas.plot。line/scatter/bar/hist/box/kde/area/pie/hexbin/barh + scatterMatrix/autocorrelation/lagPlot。PNG/SVG双格式。适用于:数据探索可视化。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.viz.Plot;\nimport jian.core.*;\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    DataFrame df = DataFrame.of(\n        Schema.of("date",STRING,"price",DOUBLE),\n        new Object[][]{{"1日",100.0},{"2日",105.0},{"3日",102.0}});\n    // 折线图\n    Plot.savePng(Plot.line(df,"date","price"), "line.png");\n    // 柱状图\n    Plot.savePng(Plot.bar(df,"date","price"), "bar.png");\n    // 直方图(5个分箱)\n    Plot.savePng(Plot.hist(df,"price",5), "hist.png");\n    // SVG矢量输出(推荐用于报告)\n    Plot.saveSvg(Plot.line(df,"date","price"), "line.svg");\n  }\n}',throws:[]
  },
  {
    id:'ref-styler',module:'jian-export',since:'v1.0',status:'stable',
    sig:'Styler.of(df).format/highlight/gradient/toHtml()/toExcel()',
    summary:'Styler样式子系统,对齐pandas.df.style。format(数值格式化)/highlightMax(极值高亮)/backgroundGradient(渐变)/bar(条形)。输出HTML/Excel/LaTeX。适用于:数据分析报告、美化Excel。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.export.Styler;\nimport jian.core.*;\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    DataFrame df = DataFrame.of(\n        Schema.of("name",STRING,"salary",DOUBLE),\n        new Object[][]{{"alice",12000.0},{"bob",8000.0},{"carol",15000.0}});\n    String html = Styler.of(df)\n        .format("#,##0.00","salary")\n        .backgroundGradient("salary", Styler.ColorMap.GREEN_YELLOW_RED)\n        .highlightMax("salary","#ffff00")\n        .toHtml();\n    System.out.println(html);\n    Styler.of(df).toExcel("styled.xlsx");\n  }\n}',throws:[]
  },
  {
    id:'ref-engine',module:'jian-sql',since:'v1.0',status:'stable',
    sig:'Engine.create(DbType, cfg) / Session<T>(engine, Class)',
    summary:'数据库连接管理(HikariCP)+ORM CRUD,对标SQLAlchemy。Engine支持7库。begin()事务。Session的findById/list/insert/update/delete。@Table/@Column/@Id注解映射。只读模式拦截危险SQL。适用于:数据库后端开发。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.sql.engine.*;\nimport jian.sql.orm.*;\nimport java.sql.Connection;\npublic class Demo {\n  @Table("users") static class User {\n    @Id @Column("id") public Long id;\n    @Column("name") public String name;\n    @Column("age") public Integer age;\n    public User(){}\n    public User(Long id,String n,Integer a){id=id;name=n;age=a;}\n  }\n  public static void main(String[] args) throws Exception {\n    Engine engine = Engine.create(DbType.H2,\n        EngineConfig.builder().path("mem:test").user("sa").password("").build());\n    try (Connection c = engine.connect(); var st = c.createStatement()) {\n      st.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(100), age INT)");\n    }\n    Session<User> s = new Session<>(engine, User.class);\n    s.insert(new User(1L,"alice",30));\n    User u = s.findById(1L);\n    System.out.println("name="+u.name);\n    engine.close();\n  }\n}',throws:[]
  },
  {
    id:'ref-ndarray',module:'jian-num',since:'v1.0',status:'stable',
    sig:'Ndarray.of(long[]/double[]/Object[]) / ofStrings(...) / .str()',
    summary:'多dtype数组引擎(对标numpy)。INT64(整数不丢精度)/FLOAT64/BOOL/DATETIME64/OBJECT(兜底)。算术/逻辑/比较/切片/astype。str()返回StrOps(对齐pandas.str):upper/contains/replace/length等。适用于:底层存储引擎、独立数值计算。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.num.*;\npublic class Demo {\n  public static void main(String[] args) {\n    Ndarray ids = Ndarray.of(new long[]{13800000000L});\n    System.out.println("id="+ids.getInt(0));\n    Ndarray names = Ndarray.ofStrings("alice",null,"Bob");\n    Ndarray up = names.str().upper();\n    System.out.println("upper="+up.get(0));  // ALICE\n    Ndarray len = names.str().length();\n    System.out.println("len="+len.getInt(0)); // 5\n  }\n}',throws:[]
  },

  {
    id:'ref-df-eval-sql',module:'jian-dsl',since:'v1.0',status:'stable',
    sig:'df.eval("total = price * qty") / df.sql("SELECT ... FROM this", otherDf...)',
    summary:'DataFrame 上的 L2/L3(经 DslEngine SPI,jian-dsl 在 classpath 时自动可用;未引抛 ModuleNotLoadedException)。eval:派生新列(支持多语句/三元/空值函数)。sql:接收者 df 即 SQL 主表(this/DUAL),${名} 占位按出现顺序绑定其余 DataFrame。适用于:链式分析流水线。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.core.*;\npublic class Demo {\n  public static void main(String[] args) {\n    DataFrame df = DataFrame.of(\n        Schema.of("price",DOUBLE,"qty",LONG,"city",STRING),\n        new Object[][]{{10.0,2L,"SH"},{5.0,3L,"BJ"}});\n    // L2 eval:派生列\n    DataFrame e = df.eval("total = price * qty");\n    System.out.println("total=" + e.getDoubleColumn("total").get(0)); // 20.0\n    // L3 sql:df 即主表 this\n    DataFrame r = df.sql("SELECT city, mean(price) AS avg FROM this GROUP BY city");\n    System.out.println("行数=" + r.rowCount());\n    // 与另一表 JOIN(this 主表 + ${b} 绑定)\n    DataFrame b = DataFrame.of(Schema.of("city",STRING,"region",STRING),\n        new Object[][]{{"SH","East"}});\n    DataFrame j = df.sql("SELECT * FROM this JOIN ${b} ON this.city = b.city", b);\n    System.out.println("JOIN行数=" + j.rowCount());\n  }\n}',throws:[]
  },
  {
    id:'ref-engine-sql',module:'jian-sql',since:'v1.0',status:'stable',
    sig:'engine.sql(sql, params...).fetch() / engine.dsl().ctx().selectFrom(...)',
    summary:'Engine 的 SQL 入口(规范 05 §2.2)。sql():原生 SQL 参数化(? 占位防注入),返回 SqlBuilder 待 fetch()/execute()。dsl():jOOQ 类型安全 DSL(jOOQ OSS 运行时模式)。Engine 构造时反射探测驱动,缺失抛 ModuleNotLoadedException(带 maven 坐标提示);连接失败抛 JianSqlException(URL 密码脱敏)。适用于:数据库脚本与分析。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.sql.engine.*;\nimport java.sql.*;\npublic class Demo {\n  public static void main(String[] args) {\n    // 从配置建引擎(H2 内存库)\n    try (Engine engine = Engine.create(DbType.H2, EngineConfig.builder()\n            .path("mem:demo;DB_CLOSE_DELAY=-1").user("sa").password("").build())) {\n      try (Connection c = engine.connect(); var st = c.createStatement()) {\n        st.execute("CREATE TABLE u (id BIGINT, name VARCHAR(100))");\n        st.execute("INSERT INTO u VALUES (1, \'alice\'), (2, \'bob\')");\n      }\n      // 原生 SQL 参数化(防注入)\n      var r = engine.sql("SELECT name FROM u WHERE id > ?", 1).fetch();\n      System.out.println("id>1: " + r.size() + " 行");\n      // 类型安全 DSL\n      var r2 = engine.dsl().ctx().selectFrom("u").where("name = ?", "alice").fetch();\n      System.out.println("alice: " + r2.size() + " 行");\n    }\n  }\n}',throws:[]
  },
  {
    id:'ref-jsonNormalize',module:'jian-io-json',since:'v1.0',status:'stable',
    sig:'Json.normalize(jsonStr, recordPath) / Jian.jsonNormalize(jsonStr, recordPath)',
    summary:'拍平嵌套 JSON,对齐 pandas.json_normalize(规范 02 §2.1/§3.3)。recordPath 用点号路径定位对象数组(如 "results.items"),嵌套对象拍平为点号列(o.x),对象数组按下标展开(items.0.n)。recordPath 为 "$" 时输入本身就是数组。适用于:REST API 嵌套响应转表格。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.io.json.Json;\nimport jian.core.DataFrame;\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    String json = "{\\"results\\":{\\"items\\":[{\\"a\\":1,\\"o\\":{\\"x\\":2,\\"y\\":3}},{\\"a\\":4,\\"o\\":{\\"x\\":5}}]}}";\n    DataFrame df = Json.normalize(json, "results.items");\n    System.out.println(df.columnNames());  // [a, o.x, o.y]\n    System.out.println("o.x[0]=" + df.getColumn("o.x").get(0));  // 2\n    // 门面入口同样可用\n    DataFrame df2 = jian.Jian.jsonNormalize(json, "results.items");\n  }\n}',throws:[]
  },
  {
    id:'ref-facade-full',module:'jian-facade',since:'v1.0',status:'stable',
    sig:'Jian.readOrc/readPickle/readSqlQuery/readFwf + Jian.toOrc/toPickle/toClipboard/toLatex/toMarkdown/toHtml',
    summary:'门面 pandas 风格方法全量补齐。读:readCsv/readTable(TSV)/readFwf/readJson/readExcel/readHtml/readXml/readParquet/readOrc/readPickle/readSql/readSqlQuery/readSqlTable/readClipboard。写:toCsv/toTable/toJson/toExcel/toHtml/toXml/toParquet/toOrc/toPickle/toLatex/toMarkdown/toClipboard/toSql。read/write(path) 按扩展名分发,支持 .tsv/.orc。适用于:一个 jar 全格式读写。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.Jian;\nimport jian.core.*;\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    DataFrame df = DataFrame.of(\n        Schema.of("id",LONG,"name",STRING),\n        new Object[][]{{1L,"alice"},{2L,"bob"}});\n    // 全格式写出\n    Jian.toPickle(df, "data.jpk");\n    Jian.toOrc(df, "data.orc");\n    Jian.toMarkdown(df, "out.md");\n    Jian.write(df, "data.tsv");   // 按扩展名分发(含 .tsv)\n    // 全格式读回\n    DataFrame a = Jian.readPickle("data.jpk");\n    DataFrame b = Jian.readOrc("data.orc");\n    DataFrame c = Jian.read("data.tsv");\n    System.out.println(a.rowCount() + " " + b.rowCount() + " " + c.rowCount());\n  }\n}',throws:[]
  },
  {
    id:'ref-jian-sql-facade',module:'jian-facade',since:'v1.0',status:'stable',
    sig:'Jian.readSql(conn, sql, params...) / Jian.readSqlQuery / Jian.readSqlTable / Jian.toSql(df, conn, table[, mode])',
    summary:'门面的 pandas 风格数据库读写(对齐 pd.read_sql / read_sql_query / read_sql_table / to_sql)。readSql/readSqlQuery:执行 SQL(支持 ? 参数化,防 SQL 注入);readSqlTable:读整张表;toSql:写 DataFrame 到库表(自动建表 + 批量 INSERT,4 种模式)。适用于:分析结果入库、库表导入 DataFrame。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.Jian;\nimport jian.core.*;\nimport java.sql.Connection;\nimport java.sql.DriverManager;\n\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    try (Connection conn = DriverManager.getConnection(\n        "jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1", "sa", "")) {\n      DataFrame df = DataFrame.of(\n          Schema.of("id", DType.LONG, "name", DType.STRING),\n          new Object[][]{{1L, "alice"}, {2L, "bob"}});\n      // pandas 风格写库(自动建表 + PreparedStatement 批量 INSERT)\n      Jian.toSql(df, conn, "users");\n      // pandas 风格读:readSql / readSqlQuery(参数化 ? 防注入)/ readSqlTable\n      DataFrame r1 = Jian.readSql(conn, "SELECT * FROM users WHERE id > ?", 1);\n      DataFrame r2 = Jian.readSqlQuery(conn, "SELECT name FROM users");\n      DataFrame all = Jian.readSqlTable(conn, "users");\n      System.out.println("行数: " + r1.rowCount() + " / " + r2.rowCount() + " / " + all.rowCount());\n    }\n  }\n}',throws:[]
  },
  {
    id:'ref-df-transform',module:'jian-core',since:'v1.0',status:'stable',
    sig:'df.select(...)/drop(...)/sortBy(...)/head/tail/slice/astype/assign + DataFrame.concat(...)/df.describe()',
    summary:'DataFrame 基础变换与统计(全部返回新实例,可链式,不可变优先)。select 选列;drop 删列;sortBy 排序(多列+升降序);head/tail 首尾;slice(start,end) 区间;astype 改列类型;assign 派生列;concat 纵向拼接;describe 返回 count/mean/std/min/25%/50%/75%/max。适用于:日常清洗与预览。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.core.*;\n\npublic class Demo {\n  public static void main(String[] args) {\n    DataFrame df = DataFrame.of(\n        Schema.of("name", DType.STRING, "age", DType.LONG, "city", DType.STRING),\n        new Object[][]{{"alice", 30L, "SH"}, {"bob", 25L, "BJ"}, {"carol", 40L, "SZ"}});\n    // 链式变换:选列 → 排序 → 取前 2 → 派生列\n    DataFrame r = df.select("name", "age")\n        .sortBy("age", true)\n        .head(2)\n        .assign("level", i -> df.getLongColumn("age").get(i) >= 30 ? "senior" : "junior");\n    // 其它常用:tail(末尾)/ slice(区间)/ drop(删列)/ astype(改类型)\n    DataFrame tail2 = df.tail(2);\n    DataFrame mid = df.slice(1, 3);\n    DataFrame noCity = df.drop("city");\n    DataFrame asDouble = df.astype("age", DType.DOUBLE);\n    // 纵向拼接 + 描述统计\n    DataFrame more = DataFrame.of(\n        Schema.of("name", DType.STRING, "age", DType.LONG, "city", DType.STRING),\n        new Object[][]{{"dave", 35L, "BJ"}});\n    DataFrame all = DataFrame.concat(df, more);\n    System.out.println(r);\n    System.out.println(df.describe());\n  }\n}',throws:[]
  },
  {
    id:'ref-parquet-orc',module:'jian-io-parquet',since:'v1.0',status:'stable',
    sig:'Parquet.read(path).go() / Parquet.write(df, path).go() + Orc.read(path).go() / Orc.write(df, path).go()',
    summary:'列式存储读写(对齐 pd.read_parquet / read_orc)。Parquet 基于 parquet-avro + LocalInputFile(不依赖 Hadoop);ORC 基于 orc-core 1.9.5(hadoop-client-runtime 提供 shaded woodstox,避免 NoClassDefFoundError)。写读往返类型一致。适用于:大数据量列存、数仓文件。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.io.parquet.Parquet;\nimport jian.io.orc.Orc;\nimport jian.core.*;\n\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    DataFrame df = DataFrame.of(\n        Schema.of("id", DType.LONG, "name", DType.STRING, "score", DType.DOUBLE),\n        new Object[][]{{1L, "alice", 90.5}, {2L, "bob", 85.0}});\n    // Parquet 列存(默认 SNAPPY 压缩)\n    Parquet.write(df, "data.parquet").go();\n    DataFrame p = Parquet.read("data.parquet").go();\n    // ORC 列存(orc-core 1.9.5 + hadoop-client-runtime)\n    Orc.write(df, "data.orc").go();\n    DataFrame o = Orc.read("data.orc").go();\n    System.out.println("parquet=" + p.rowCount() + " orc=" + o.rowCount());\n  }\n}',throws:[]
  },
  {
    id:'ref-xml-html',module:'jian-io-xml',since:'v1.0',status:'stable',
    sig:'Xml.read(path).go() / Xml.write(df, path).rootName().rowName().go() + Html.readAll(path) / Html.read(path).match().go() / Html.readUrl(url).go()',
    summary:'XML 与 HTML 表格读写。XML 基于 Jackson XML:写端自动清洗非法列名(替换 _)并转义值,产物永远合法;HTML 基于 jsoup:提取全部 <table>,match 支持正则筛表(对齐 pandas.read_html 的 match),readUrl 支持在线抓取。适用于:配置交换、网页表格抓取。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.io.xml.Xml;\nimport jian.io.html.Html;\nimport jian.core.*;\nimport java.util.List;\n\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    DataFrame df = DataFrame.of(\n        Schema.of("name", DType.STRING, "age", DType.LONG),\n        new Object[][]{{"alice", 30L}, {"bob", 25L}});\n    // XML 读写(可配 root/row 名;列名非法字符自动清洗,值转义 & < >)\n    Xml.write(df, "data.xml").rootName("rows").rowName("item").go();\n    DataFrame x = Xml.read("data.xml").rowName("item").go();\n    // HTML 表格读:直接读全部表;或 match 正则筛表(统一终结符 go())\n    List<DataFrame> all = Html.readAll("page.html");\n    List<DataFrame> hit = Html.read("page.html").match(".*用户.*").go();\n    // 从 URL 读\n    List<DataFrame> fromUrl = Html.readUrl("https://example.com").go();\n    System.out.println(x.rowCount() + " " + all.size() + " " + hit.size() + " " + fromUrl.size());\n  }\n}',throws:[]
  },
  {
    id:'ref-pickle-clipboard',module:'jian-io-pickle',since:'v1.0',status:'stable',
    sig:'Pickle.write(df, path) / Pickle.read(path) + Clipboard.write(df) / Clipboard.read()',
    summary:'DataFrame 落盘与剪贴板。Pickle 是自定义 .jpk 格式(魔数 + JSON records + CRC32 校验),反序列化只读 JSON、不实例化任意类,无 RCE 风险(与 Python pickle 不互通,规范已说明)。Clipboard 跨平台:Linux xclip/xsel、macOS pbcopy/pbpaste、Windows clip,命令缺失降级内存不崩溃。适用于:缓存中间结果、复制表格到 Excel。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.io.pickle.Pickle;\nimport jian.io.clipboard.Clipboard;\nimport jian.core.*;\n\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    DataFrame df = DataFrame.of(\n        Schema.of("id", DType.LONG, "name", DType.STRING),\n        new Object[][]{{1L, "alice"}, {2L, "bob"}});\n    // .jpk 落盘再加载(魔数 + JSON + CRC32,不实例化任意类,无 RCE 风险)\n    Pickle.write(df, "data.jpk");\n    DataFrame back = Pickle.read("data.jpk");\n    // 剪贴板:TSV 格式,粘贴到 Excel/WPS 自动分列\n    // 命令不可用(xclip/pbcopy/clip 缺失)时降级内存,同 JVM 内可读回,不崩溃\n    Clipboard.write(df);\n    DataFrame clip = Clipboard.read();\n    System.out.println("pickle=" + back.rowCount() + " clip=" + clip.rowCount());\n  }\n}',throws:[]
  },
  {
    id:'ref-renderers',module:'jian-export',since:'v1.0',status:'stable',
    sig:'HtmlRenderer.of(df).render()/.renderTo(path) + MarkdownRenderer.of(df).render() + LatexRenderer.of(df).caption().render() + LatexIo.write(df, path).go()',
    summary:'DataFrame → HTML / Markdown / LaTeX 导出(对齐 pandas.to_html/to_markdown/to_latex)。HtmlRenderer 可配 border/index/caption/maxRows 等;MarkdownRenderer 纯表格;LaTeX 两条路径(io 模块 LatexIo 直写文件,export 的 LatexRenderer 取字符串)。顶层 Jian.toHtml/toMarkdown/toLatex 已包装。适用于:报表生成、文档嵌入。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.export.HtmlRenderer;\nimport jian.export.MarkdownRenderer;\nimport jian.export.LatexRenderer;\nimport jian.io.latex.LatexIo;\nimport jian.core.*;\n\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    DataFrame df = DataFrame.of(\n        Schema.of("name", DType.STRING, "age", DType.LONG),\n        new Object[][]{{"alice", 30L}, {"bob", 25L}});\n    // HTML 表格(可配 border/index/caption/maxRows)\n    HtmlRenderer.of(df).border(1).index(true).renderTo("report.html");\n    // Markdown(降级纯表格)\n    String md = MarkdownRenderer.of(df).render();\n    // LaTeX 两条路:io 模块直写文件 / export 渲染器取字符串\n    LatexIo.write(df, "table.tex").caption("用户表").label("tab:users").go();\n    String tex = LatexRenderer.of(df).caption("用户表").render();\n    System.out.println(md.split("\\n")[0] + " | tex=" + tex.length() + " chars");\n  }\n}',throws:[]
  },
  {
    id:'ref-excel-multi',module:'jian-io-excel',since:'v1.0',status:'stable',
    sig:'Excel.writer(path).write(df, "sheet1").write(df2, "sheet2")...(try-with-resources) + Excel.sheetNames(path) + Excel.read(path).sheet(name).go()',
    summary:'Excel 多 sheet 读写(POI 5.5.1 原生,非 uber)。Excel.writer 返回 ExcelMultiWriter(AutoCloseable,close 时落盘);sheetNames 枚举;read().sheet(name) 读指定页。读路径含两阶段逐列类型推断与全套 POI 陷阱处理(空行/重名表头/≥15位 Long/公式缓存值)。适用于:多页报表导出。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.io.excel.Excel;\nimport jian.core.*;\n\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    DataFrame users = DataFrame.of(\n        Schema.of("id", DType.LONG, "name", DType.STRING),\n        new Object[][]{{1L, "alice"}});\n    DataFrame depts = DataFrame.of(\n        Schema.of("did", DType.STRING, "dname", DType.STRING),\n        new Object[][]{{"RD", "研发"}});\n    // 多 sheet 写(对齐 pandas.ExcelWriter,try-with-resources 自动落盘)\n    try (Excel.ExcelMultiWriter w = Excel.writer("multi.xlsx")) {\n      w.write(users, "users").write(depts, "depts");\n    }\n    // 枚举 sheet 名\n    System.out.println(Excel.sheetNames("multi.xlsx"));\n    // 读指定 sheet\n    DataFrame r = Excel.read("multi.xlsx").sheet("depts").go();\n    System.out.println(r.getStringColumn("dname").get(0));\n  }\n}',throws:[]
  },
  {
    id:'ref-session',module:'jian-sql-orm',since:'v1.0',status:'stable',
    sig:'@Table/@Id/@Column 注解实体 + new Session<>(engine, cls).findById/list/insert/update/delete',
    summary:'jian-sql-orm 的轻量 ORM(对齐 sqlalchemy 的 Session)。实体用 @Table/@Id/@Column 标注(字段 public);Session 包装 Engine:findById 按主键查、list 全量、insert 插入(回填自增 id)、update 按主键更新、delete 删除。批量/条件查询走 SqlBuilder(复杂条件,见 ref-engine-sql)。适用于:脚本里快速 CRUD。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.sql.engine.*;\nimport jian.sql.orm.*;\nimport java.sql.*;\nimport java.util.List;\n\n@Table("users")\nclass User {\n  @Id @Column("id") public Long id;\n  @Column("name") public String name;\n  @Column("age") public Integer age;\n}\n\npublic class Demo {\n  public static void main(String[] args) throws Exception {\n    try (Engine engine = Engine.create(DbType.H2, EngineConfig.builder()\n        .path("mem:orm;DB_CLOSE_DELAY=-1").user("sa").password("").build())) {\n      try (Connection c = engine.connect(); Statement st = c.createStatement()) {\n        st.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(100), age INT)");\n      }\n      Session<User> s = new Session<>(engine, User.class);\n      User u = new User(); u.name = "alice"; u.age = 30;\n      s.insert(u);                       // 自动回填自增 id\n      User back = s.findById(1L);\n      u.age = 31;\n      s.update(u);\n      List<User> all = s.list();\n      s.delete(back);\n      System.out.println("list=" + all.size() + " find=" + back.name);\n    }\n  }\n}',throws:[]
  },
  {
    id:'ref-matrix-num',module:'jian-num',since:'v1.0',status:'stable',
    sig:'Matrix.of/identity/T/matmul/row/solve/inverse/determinant/leastSquares + JianNum.mean/percentile/pearson/linearFit',
    summary:'jian-num 矩阵与统计(基于 Commons Math 3.6.1)。Matrix:乘法/转置/行/解线性方程/求逆/行列式/最小二乘(奇异矩阵抛带提示异常);JianNum:mean/sum/std/var/median/percentile(0-100)/quantile/skewness/kurtosis/cov/pearson/spearman/linearFit(返回 slope/intercept/rSquared)。适用于:数值分析与简单线代。',
    params:[],returns:{type:'',desc:''},
    example:'import jian.num.*;\n\npublic class Demo {\n  public static void main(String[] args) {\n    // 矩阵:乘法 / 转置 / 取行 / 解方程 / 求逆\n    Matrix a = Matrix.of(new double[][]{{1, 2}, {3, 4}});\n    Matrix c = a.matmul(Matrix.identity(2));     // == a.mul(b)\n    Matrix t = a.T();                            // 转置\n    double[] row0 = a.row(0);                    // [1, 2]\n    double[] x = a.solve(new double[]{5, 11});   // 解 Ax=b → [1, 2]\n    Matrix inv = a.inverse();\n    // 统计:均值 / 分位(0-100)/ 相关 / 线性拟合\n    double[] xs = {1, 2, 3, 4}, ys = {2, 4, 6, 8};\n    double m = JianNum.mean(xs);\n    double p75 = JianNum.percentile(xs, 75);\n    double r = JianNum.pearson(xs, ys);          // 1.0\n    LinearFit fit = JianNum.linearFit(xs, ys);   // slope=2, rSquared=1\n    System.out.println("x[1]=" + x[1] + " r=" + r + " slope=" + fit.slope());\n  }\n}',throws:[]
  },
  {
    id:'ref-columnar-hashmap',module:'jian-core',since:'v1.1',status:'stable',
    sig:'ColumnarHashMap.buildFromLong/buildFromInt/buildFromDouble + findLong/findInt/findDouble + nextInBucket',
    summary:'列式 open-addressing hash 表(单列数值 key 专用,JOIN/GroupBy hot path)。容量始终为 2^k,装载因子 ≤ 0.5。同 key 多行通过桶内链表(nextInBucket)解决。500万行 hash build ~120ms。适用于:自定义 JOIN/group-by 算子直接复用,避免 HashMap<List<Object>> 装箱开销。',
    params:[],returns:{type:'',desc:''},
    example:'long[] rightKeys = {5L, 1L, 5L, 3L};\nColumnarHashMap map = ColumnarHashMap.buildFromLong(rightKeys);\nint first = map.findLong(5L);\nfor (int r = first; r >= 0; r = map.nextInBucket(r)) { /* 行下标 r */ }',throws:[]
  },
  {
    id:'ref-column-data',module:'jian-core',since:'v1.1',status:'stable',
    sig:'LongColumn.data() → long[] / DoubleColumn.data() → double[] / *.wrapNoCopy(name, arr, mask)',
    summary:'Column 子类的 primitive 数组零拷贝访问(高性能 hot path)。data() 返回内部数组直接引用(不 clone);wrapNoCopy 是对应的零拷贝构造。警告:返回数组不得修改。适用于:自定义向量化算子、批量统计、与外部库对接。',
    params:[],returns:{type:'',desc:''},
    example:'long[] ids = ((LongColumn) df.getColumn("id")).data();\ndouble[] vs = ((DoubleColumn) df.getColumn("v")).data();\nlong[] newArr = {9L, 8L};\nLongColumn c = LongColumn.wrapNoCopy("x", newArr, null);',throws:[]
  },
  {
    id:'ref-df-ofcolumnarrays',module:'jian-core',since:'v1.1',status:'stable',
    sig:'DataFrame.ofColumnArrays(List<String> columnNames, Object[] columnArrays)',
    summary:'直接用 primitive 数组构造 DataFrame(零拷贝)。columnArrays 元素按 Java 类型映射 DType:long[]→LONG, double[]→DOUBLE, int[]→INT(升位 LONG), boolean[]→BOOL, String[]→STRING, 其它 Object[]→OBJECT。零拷贝直接引用(调用方此后不应再修改)。适用于:JOIN/GroupBy/统计 hot path 输出,避免逐行 new Object[] + 装箱。',
    params:[],returns:{type:'',desc:''},
    example:'long[] ids = {1L, 2L, 3L};\ndouble[] vals = {10.0, 20.0, 30.0};\nDataFrame df = DataFrame.ofColumnArrays(\n    List.of("id", "val"),\n    new Object[]{ ids, vals });',throws:[]
  },
  // ===== 扩展方法卡(各分册 §3.16 等)=====
  { id:'ref-resample', module:'jian-core', since:'1.0.1', status:'alpha',
    sig:'df.resample("ts", "1D").sum() / .mean() / .count() / .ohlc("price")',
    summary:'时间序列重采样(对齐 pandas DataFrame.resample)。返回 Resampler 对象,链式调聚合(sum/mean/count/min/max/median/std/var/ohlc/agg/first/last 共 17 方法)。',
    params:[{name:'tsCol',type:'String',desc:'时间列名(LocalDateTime 元素)'},{name:'rule',type:'String',desc:'频率字符串("1D"/"2H"/"1W")'}],
    returns:{type:'Resampler',desc:'重采样器对象(链式调聚合)'},
    example:'DataFrame daily = df.resample("ts", "1D").sum();',throws:[]
  },
  { id:'ref-corr-matrix', module:'jian-core', since:'1.0.1', status:'alpha',
    sig:'df.corrMatrix() / df.covMatrix()',
    summary:'全数值列相关/协方差矩阵(对齐 pandas df.corr/cov)。经 StatsProvider SPI。',
    params:[],returns:{type:'DataFrame',desc:'方阵(行/列=数值列名)'},
    example:'DataFrame m = df.corrMatrix();',throws:[]
  },
  { id:'ref-idxmax', module:'jian-core', since:'1.0.1', status:'alpha',
    sig:'df.idxmax("v") / df.idxmin("v")',
    summary:'极值位置(对齐 pandas df.idxmax/idxmin)。空表/全缺失返回 -1。',
    params:[{name:'col',type:'String',desc:'数值列名'}],
    returns:{type:'int',desc:'首行下标;-1=无有效值'},
    example:'int i = df.idxmax("salary");',throws:[]
  },
  { id:'ref-stack-unstack', module:'jian-core', since:'1.0.1', status:'alpha',
    sig:'df.stack(idCols, valueCols) / df.unstack(idCol, keyCol, valCol)',
    summary:'长宽转换(对齐 pandas df.stack/unstack)。',
    params:[],returns:{type:'DataFrame',desc:'长/宽转换后的新表'},
    example:'DataFrame stacked = df.stack(new String[]{"id"}, new String[]{"q1","q2"});',throws:[]
  },
  { id:'ref-interpolate', module:'jian-core', since:'1.0.1', status:'alpha',
    sig:'df.interpolate()',
    summary:'线性插值填充缺失(对齐 pandas df.interpolate)。',
    params:[],returns:{type:'DataFrame',desc:'同结构,数值列缺失被线性插值填充'},
    example:'DataFrame r = df.interpolate();',throws:[]
  },
  { id:'ref-sql-engine', module:'jian-dsl', since:'1.0.1', status:'alpha',
    sig:'SqlEngines.useRegex() / useJsqlParser() / useCustom(impl)',
    summary:'L3 SQL 引擎可插拔切换(库无关接口)。',
    params:[],returns:{type:'void',desc:'切换 ThreadLocal 引擎'},
    example:'SqlEngines.useCustom(new MyEngine());',throws:[]
  },
];
