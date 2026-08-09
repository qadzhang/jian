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
    { sig:'df.groupBy("dept").agg(spec)', mod:"jian-core", status:"alpha" },
    { sig:'df.idxmax("v") / df.sample(10, false, 42L) / df.isin(1,2,3)', mod:"jian-core", status:"alpha" },
    { sig:'df.colCorr("x","y") / df.colSkew("v") / df.colKurt("v") / df.corrMatrix()', mod:"jian-core", status:"alpha" },
    { sig:'df.colCumsum("v","cs") / df.colDiff("v",1,"d") / df.colRank("v","average","rk")', mod:"jian-core", status:"alpha" },
    { sig:'df.pivot("date","city","temp") / df.explode("tags") / df.stack(idCols,valueCols)', mod:"jian-core", status:"alpha" },
    { sig:'df.mergeAsof(right, "ts") / df.join(right, "id", "left")', mod:"jian-core", status:"alpha" },
    { sig:'df.resample("ts","1D").sum() / .mean() / .ohlc("price")', mod:"jian-core", status:"alpha" },
    { sig:'df.interpolate() / df.where(mask, 0) / df.mask(mask, 0)', mod:"jian-core", status:"alpha" },
    { sig:'df.astype("v", DType.BOOL/DATETIME/DATE) (7种)', mod:"jian-core", status:"alpha" },
    { sig:'df.tzLocalize("ts","UTC") / df.tzConvert("ts","Asia/Shanghai")', mod:"jian-core", status:"alpha" },
    { sig:'series.argmax() / .argmin() / .tolist() / .between(1,10) / .hasnans()', mod:"jian-core", status:"alpha" }
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
