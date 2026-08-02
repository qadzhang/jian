// API 速查数据
const API_QUICK = [
  { group:"读入", items:[
    { sig:'Jian.read("data.csv") / .tsv / .xlsx / .json', mod:"jian-facade", status:"beta" },
    { sig:'Jian.readCsv/readExcel/readOrc/readPickle/readSql(...)', mod:"jian-facade", status:"beta" },
    { sig:'Csv.read(path).delimiter(\'\t\').go()', mod:"jian-io-csv", status:"beta" },
    { sig:'Json.normalize(json, "results.items")', mod:"jian-io-json", status:"beta" }
  ]},
  { group:"变换", items:[
    { sig:'df.query("age > 18 && city in (\'SH\',\'BJ\')")', mod:"jian-core", status:"beta" },
    { sig:'df.eval("total = price * qty")', mod:"jian-dsl", status:"beta" },
    { sig:'df.sql("SELECT ... FROM this")', mod:"jian-dsl", status:"beta" },
    { sig:'df.groupBy("dept").agg(spec)', mod:"jian-core", status:"alpha" }
  ]},
  { group:"写出", items:[
    { sig:'Jian.write(df, "out.csv/.tsv/.orc/.jpk")', mod:"jian-facade", status:"beta" },
    { sig:'Jian.toMarkdown/toLatex/toClipboard/toSql(...)', mod:"jian-facade", status:"beta" },
    { sig:'engine.sql("SELECT ... WHERE id > ?", 18).fetch()', mod:"jian-sql", status:"beta" }
  ]},
  { group:"数据库", items:[
    { sig:'Jian.readSql(conn, "SELECT ... WHERE id > ?", 1)', mod:"jian-facade", status:"beta" },
    { sig:'Jian.readSqlTable(conn, "users") / readSqlQuery(conn, sql, ...)', mod:"jian-facade", status:"beta" },
    { sig:'Jian.toSql(df, conn, "users"[, Sql.Mode.APPEND])', mod:"jian-facade", status:"beta" },
    { sig:'new Session<>(engine, User.class).findById/list/insert/update/delete', mod:"jian-sql-orm", status:"beta" }
  ]}
];
