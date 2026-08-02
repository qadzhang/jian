// 模块卡数据(改这里不影响渲染逻辑)
const MODULES = [
  {id:'m-num',name:'jian-num',color:'purple',py:'numpy',desc:'Ndarray(多dtype+sum/mean) + Stats + Matrix(T/matmul/row) + StrOps + Random + pearsonCorr/spearmanCorr 别名',deps:'Commons Math 3.6.1',lines:'~3000',methods:'统计/线代',phase:'done'},
  {id:'m-core',name:'jian-core',color:'blue',py:'pandas DataFrame',desc:'9 dtype + query(in/not in,like 防注入)/eval/sql(经SPI) + groupby/merge/pivot/sort/Series(rolling/ewm/str/dt)/MultiIndex + StatsProvider 接线 + ModuleNotLoadedException',deps:'纯 JDK 17',lines:'~11000',methods:'200+',phase:'done'},
  {id:'m-io',name:'jian-io',color:'green',py:'pandas.read_*',desc:'12 格式 + 7 数据库全实现(CSV 公式注入防护默认开 / XML 名称清洗 / json_normalize / .tsv 分支修复)',deps:'commons-csv/POI/Jackson/jsoup/parquet/orc/JDBC',lines:'~7000',methods:'12格式+7库',phase:'done'},
  {id:'m-viz',name:'jian-viz',color:'purple',py:'pandas.plot',desc:'13 种图 PNG/SVG(4 种高维图 v2 规划)',deps:'XChart 4.0.3',lines:'~2500',methods:'13图',phase:'done'},
  {id:'m-export',name:'jian-export',color:'amber',py:'pandas.Styler',desc:'HTML/Markdown/LaTeX/控制台 + Styler(format/highlight/gradient/bar/toExcel)',deps:'POI(可选)',lines:'~3500',methods:'Styler+5渲染',phase:'done'},
  {id:'m-dsl',name:'jian-dsl',color:'blue',py:'pandas.query+SQL',desc:'L1 query + L2 eval + L3 SQL(DISTINCT/LIMIT OFFSET/JOIN/UNION ALL/子查询2层/nvl/coalesce/ifnull). df.eval()/df.sql() 经 SPI',deps:'纯 JDK',lines:'~5000',methods:'3档DSL',phase:'done'},
  {id:'m-sql',name:'jian-sql',color:'green',py:'SQLAlchemy',desc:'Engine(HikariCP,dsl()/sql() 入口 + 只读拦截防注释绕过 + JianSqlException/ModuleNotLoadedException) + SqlBuilder(jOOQ) + ORM(Session) + Bridge',deps:'jOOQ 3.21.6 + HikariCP',lines:'~2500',methods:'engine/expr/orm',phase:'done'},
  {id:'m-facade',name:'jian-facade',color:'blue',py:'pd.read_*',desc:'顶层 Jian 门面: read/write 自动分发(csv/tsv/xlsx/json/html/xml/parquet/orc/jpk), pandas 风格 read*/to* 全套 + jsonNormalize + sql 内存SQL',deps:'全部子模块',lines:'~300',methods:'read/write/sql',phase:'done'},
];
