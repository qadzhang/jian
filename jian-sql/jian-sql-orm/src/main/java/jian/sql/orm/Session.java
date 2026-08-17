package jian.sql.orm;

import jian.sql.engine.Engine;
import jian.sql.expr.SqlBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

// ┌─ What : Session —— ORM 会话(对齐规范 05 §2.3 SQLAlchemy Session,提供 findById/list/insert/update/delete)
// │  Why  : 规范 05 §2.3 ORM 层;反射扫描实体类的 @Table/@Column/@Id 注解做对象 ↔ 行映射
// │  Who  : 用户经 engine.session(EntityClass) 创建
// │  When : CRUD 操作
// │  Where: jian-sql-orm/Session.java
// │  How  : 数据走向:
// │           查询:实体类 → 反射取 @Table/@Column → SELECT * FROM table → ResultSet → 反射填字段 → 实体列表;
// │           插入:实体对象 → 反射取字段 → INSERT ...(?,?...) → 绑定参数。
// │         关键变量变化:
// │           - entityClass:实体类的 Class<?>;
// │           - 注解扫描缓存:表名/列名/主键字段从注解读一次。
/**
 * ORM 会话,提供实体 CRUD。由 {@link Engine} 创建(规范 §2.3)。
 *
 * <p>用法:
 * <pre>{@code
 * Session&lt;User&gt; s = engine.session(User.class);
 * User u = s.findById(1L);
 * List&lt;User&gt; all = s.list();
 * s.insert(new User(2L, "alice", 30));
 * s.update(u);
 * s.delete(u);
 * }</pre>
 */
public final class Session<T> {

    private final Engine engine;
    private final Class<T> entityClass;
    private final String tableName;
    private final Field idField;
    private final List<FieldInfo> fields;  // 所有 @Column/@Id 字段

    /**
     * @param engine     Engine 数据库引擎,约束:不能为 null
     * @param entityClass Class<T> 实体类,约束:不能为 null;必须标注 @Table;字段用 @Column/@Id 标注
     */
    public Session(Engine engine, Class<T> entityClass) {
        this.engine = engine;
        this.entityClass = entityClass;
        Table t = entityClass.getAnnotation(Table.class);
        if (t == null) {
            throw new IllegalArgumentException("实体类 " + entityClass.getName() + " 缺少 @Table 注解");
        }
        // 因为 @Table/@Column 注解值会直接拼入 SELECT/INSERT/UPDATE/DELETE(标识符无参数化形式),
        // 所以按需以库引号符包裹 + 双写转义(与 jian-io-sql Sql.quoteIdentifier 同族防线:
        // 简单 ASCII 原样放行保留各库默认折叠,中文/特殊字符保真包裹,控制字符构造期即拒)
        validateIdentifier(t.value(), "表名");
        this.tableName = t.value();
        Field idF = null;
        List<FieldInfo> fs = new ArrayList<>();
        // 沿类层级收集字段(旧实现只扫本类 getDeclaredFields,
        // 父类(实体基类模式)的 @Id/@Column 字段被静默丢弃 —— insert 抛"无 @Id"或
        // update 丢列,数据漂移无提示)。父类字段在前(ORM 惯例);跳过 static/synthetic。
        java.util.List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> k = entityClass; k != null && k != Object.class; k = k.getSuperclass()) {
            hierarchy.add(k);
        }
        java.util.Collections.reverse(hierarchy);
        for (Class<?> k : hierarchy) {
        for (Field f : k.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
            Id idAnno = f.getAnnotation(Id.class);
            Column colAnno = f.getAnnotation(Column.class);
            if (idAnno != null) {
                idF = f;
                { String cn = colAnno != null ? colAnno.value() : f.getName(); validateIdentifier(cn, "列名");
                fs.add(new FieldInfo(f, cn, true)); }
            } else if (colAnno != null) {
                { String cn = colAnno.value(); validateIdentifier(cn, "列名"); fs.add(new FieldInfo(f, cn, false)); }
            }
        }
        }
        this.idField = idF;
        this.fields = fs;
    }

    // ┌─ What : 标识符构造期校验(空值/控制字符 fail-fast)
    // │  Why  : 引号包裹能化解注入元字符,但救不了空名与不可见控制字符;且应在构造期
    // │         早于任何 SQL 拼接报错(与 jian-io-sql Sql.quoteIdentifier 路径 B 同口径)。
    // │  How  : null/空串 → IAE;任一字符 <0x20 或 ==0x7F → IAE(带名字与"表名/列名"上下文)。
    private static void validateIdentifier(String name, String what) {
        if (name == null || name.isEmpty())
            throw new IllegalArgumentException("非法" + what + "(@Table/@Column 值不能为空)");
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < 0x20 || ch == 0x7F)
                throw new IllegalArgumentException("非法" + what + "(含控制字符): " + name);
        }
    }

    /** 库引号符缓存(懒取一次并复用;PG/H2/SQLite 为 ",MySQL 为 `;空白=驱动不提供)。 */
    private volatile String identQuote;

    /** 取库引号符(经 engine 短连取元数据后缓存,避免每条 SQL 都开连接)。 */
    private String quoteChar() throws SQLException {
        String q = identQuote;
        if (q == null) {
            try (Connection c = engine.connect()) {
                String meta = c.getMetaData().getIdentifierQuoteString();
                q = (meta == null) ? "" : meta;
            }
            identQuote = q;
        }
        return q;
    }

    // ┌─ What : 标识符按需以库引号符包裹(jian-io-sql Sql.quoteIdentifier 的同族实现)
    // │  Why  : 简单 ASCII([A-Za-z_][A-Za-z0-9_]*,表名含 schema.table 点号)不加引号,
    // │         保留各库默认大小写折叠(既有用法零破坏);中文/特殊字符以库引号符包裹 +
    // │         引号双写转义,严格按输入保真("AA_a啊" 原样建表建列)—— ORM 与 io-sql
    // │         行为一致(jOOQ/SQLAlchemy 等主流 ORM 同为按需引号化,官方文档明言引号
    // │         同时是防标识符注入的手段)。控制字符已在构造期 validateIdentifier 拒绝。
    // │  How  : 简单 ASCII 形态命中 → 原样返回;否则 q + name 内的 q 双写 + q;
    // │         驱动不提供引号符且名字非简单 ASCII → IAE(该库无法安全写入该标识符)。
    private String quoteIdentifier(String id) throws SQLException {
        if (id.matches("[A-Za-z_][A-Za-z0-9_]*")) return id;
        String q = quoteChar();
        if (q.isEmpty())
            throw new IllegalArgumentException("标识符含非简单字符但该数据库驱动不提供引号符,无法安全写入: " + id);
        return q + id.replace(q, q + q) + q;
    }

    /** 表名引号化(schema.table 点号形态逐段包裹后以点号连接)。 */
    private String quoteTable(String table) throws SQLException {
        if (table.matches("[A-Za-z_][A-Za-z0-9_.]*")) return table;
        int dot = table.indexOf('.');
        if (dot <= 0 || dot == table.length() - 1) return quoteIdentifier(table);
        return quoteIdentifier(table.substring(0, dot)) + "." + quoteTable(table.substring(dot + 1));
    }

    /**
     * 按主键查(对齐 sqlalchemy session.get)。
     *
     * @param id Object 主键值,约束:类型须与 @Id 字段匹配;不能为 null
     * @return T 查到的实体对象;未找到返回 null
     * @throws Exception 当实体无 @Id 字段、或 JDBC 操作失败时抛出
     */
    public T findById(Object id) throws Exception {
        if (idField == null) throw new IllegalStateException("实体无 @Id 字段");
        try (Connection conn = engine.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM " + quoteTable(tableName) + " WHERE " + idColumnName() + " = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapRow(rs);
            }
        }
    }

    /** 查全部(对齐 sqlalchemy session.query.all)。 */
    public List<T> list() throws Exception {
        try (Connection conn = engine.connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + quoteTable(tableName))) {
            List<T> out = new ArrayList<>();
            while (rs.next()) out.add(mapRow(rs));
            return out;
        }
    }

    /**
     * 插入(对齐 sqlalchemy session.add)。
     * <p>因为只读引擎的所有写入口都必须拦截,所以写路径先过 {@link Engine#checkReadOnly(String)}。
     * <p>因为自增主键表上实体 @Id 值为 null 时不应手工写主键列,
     * 所以此时跳过主键列并以 RETURN_GENERATED_KEYS 取回生成键反射回填实体
     * (仅当字段可写且为 Number);id 非 null 时行为不变。
     *
     * @param entity T 待插入实体对象,约束:不能为 null;字段值按 @Column 注解顺序绑定
     * @return int 受影响行数(通常为 1)
     * @throws SecurityException 当引擎为只读模式(readOnly=true)时抛出
     * @throws Exception         当 JDBC 操作失败时抛出
     */
    public int insert(T entity) throws Exception {
        // 伪代码:
        //   1. 读 @Id 字段值 idVal;idVal==null 且实体有 @Id → generatedKey 分支(跳过主键列 + 取生成键)
        //   2. 拼 INSERT 列清单(generatedKey 时排除 @Id 列)与等量 ?
        //   3. checkReadOnly(只读引擎到此抛 SecurityException,不碰数据库)
        //   4. prepareStatement(带不带 RETURN_GENERATED_KEYS 按 generatedKey)
        //   5. 绑定参数 → executeUpdate → generatedKey 时回填自增主键
        Object idVal = null;
        if (idField != null) {
            idField.setAccessible(true);
            idVal = idField.get(entity);
        }
        boolean generatedKey = (idField != null && idVal == null);
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(quoteTable(tableName)).append(" (");
        StringBuilder ph = new StringBuilder();
        boolean first = true;
        for (FieldInfo fi : fields) {
            if (generatedKey && fi.isId) continue;   // 主键交给库生成,不进列清单
            if (!first) { sql.append(','); ph.append(','); }
            sql.append(quoteIdentifier(fi.columnName));
            ph.append('?');
            first = false;
        }
        sql.append(") VALUES (").append(ph).append(')');
        engine.checkReadOnly(sql.toString());   // 写 SQL 过只读防线(与 sql()/dsl() 同一入口)
        try (Connection conn = engine.connect();
             PreparedStatement ps = conn.prepareStatement(sql.toString(),
                     generatedKey ? PreparedStatement.RETURN_GENERATED_KEYS
                                  : PreparedStatement.NO_GENERATED_KEYS)) {
            int p = 1;
            for (FieldInfo fi : fields) {
                if (generatedKey && fi.isId) continue;
                fi.field.setAccessible(true);
                ps.setObject(p++, fi.field.get(entity));
            }
            int rows = ps.executeUpdate();
            if (generatedKey) fillGeneratedId(ps, entity);
            return rows;
        }
    }

    // ┌─ What : 把数据库生成的自增主键反射回填进实体
    // │  Why  : 因为 insert 恒写 @Id 列时,自增主键表上实体 id=null 会 setObject(null) 主键违例,
    // │         所以 @Id 值为 null 的插入走 RETURN_GENERATED_KEYS 路径取回生成 id 回填实体
    // │  Who  : Session.insert(generatedKey 分支)
    // │  When : @Id 值为 null 的 INSERT 执行成功之后
    // │  Where: jian-sql-orm/Session.java
    // │  How  : 数据走向:PreparedStatement.getGeneratedKeys() → ResultSet 首列键值
    // │           → adaptValue 适配 @Id 字段类型 → 反射 field.set 回填实体。
    // │         关键变量变化:key(初始 null→驱动返回的生成键,通常 Long)→ adapted(适配字段类型)。
    // │         逻辑路线(四条):
    // │           路径 A(无 @Id / 字段 final 不可写 / 字段非 Number)→ 不回填直接返回(约定仅 Number 回填);
    // │           路径 B(生成键结果集空或首列为 null)→ 不回填;
    // │           路径 C(取到键)→ 适配并 set 回填;
    // │           路径 D(getGeneratedKeys 抛 SQLException)→ stderr 留痕不抛(插入已成功,回填是增值)。
    private void fillGeneratedId(PreparedStatement ps, T entity) {
        if (idField == null) return;
        if (Modifier.isFinal(idField.getModifiers())) return;
        Class<?> ft = idField.getType();
        // 去掉"仅 Number 回填"早退 —— String/enum 等 @Id 走
        // adaptValue 既有兜底(String.valueOf/Enum.valueOf);不可适配类型由 adaptValue
        // 抛教学型 IAE(fail-fast 优于静默丢回填)
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
                Object key = keys.getObject(1);
                if (key == null) return;
                idField.setAccessible(true);
                idField.set(entity, adaptValue(key, ft));
            }
        } catch (Exception e) {
            System.err.println("[jian-sql-orm] 回填自增主键失败(插入已成功,实体 id 未刷新):"
                    + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 更新(按主键更新所有字段)。
     * <p>因为只读引擎的所有写入口都必须拦截,所以写路径先过 {@link Engine#checkReadOnly(String)}(与 insert 同理)。
     *
     * @param entity T 待更新实体对象,约束:不能为 null;@Id 字段值用于 WHERE 条件
     * @return int 受影响行数(通常为 1)
     * @throws SecurityException 当引擎为只读模式(readOnly=true)时抛出
     * @throws Exception         当实体无 @Id 字段、或 JDBC 操作失败时抛出
     */
    public int update(T entity) throws Exception {
        if (idField == null) throw new IllegalStateException("实体无 @Id 字段,无法 update");
        StringBuilder sql = new StringBuilder("UPDATE ").append(quoteTable(tableName)).append(" SET ");
        // 因为主键同时进 SET 与 WHERE 语义错("SET id=?,name=? WHERE id=?",对齐 SQLAlchemy 只对非 PK 列 SET),
        // 所以 SET 子句跳过 @Id 主键
        boolean first = true;
        for (FieldInfo fi : fields) {
            if (fi.isId) continue;
            if (!first) sql.append(',');
            sql.append(quoteIdentifier(fi.columnName)).append("=?");
            first = false;
        }
        sql.append(" WHERE ").append(idColumnName()).append("=?");
        engine.checkReadOnly(sql.toString());   // 写 SQL 过只读防线
        try (Connection conn = engine.connect();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int p = 1;
            for (FieldInfo fi : fields) {
                if (fi.isId) continue;   // 绑定参数与 SET 同步跳过主键
                fi.field.setAccessible(true);
                ps.setObject(p++, fi.field.get(entity));
            }
            idField.setAccessible(true);
            ps.setObject(p, idField.get(entity));
            return ps.executeUpdate();
        }
    }

    /**
     * 删除(按主键)。
     * <p>因为只读引擎的所有写入口都必须拦截,所以写路径先过 {@link Engine#checkReadOnly(String)}(与 insert 同理)。
     *
     * @param entity T 待删除实体对象,约束:不能为 null;@Id 字段值用于 WHERE 条件
     * @return int 受影响行数(通常为 1)
     * @throws SecurityException 当引擎为只读模式(readOnly=true)时抛出
     * @throws Exception         当实体无 @Id 字段、或 JDBC 操作失败时抛出
     */
    public int delete(T entity) throws Exception {
        if (idField == null) throw new IllegalStateException("实体无 @Id 字段,无法 delete");
        String sql = "DELETE FROM " + quoteTable(tableName) + " WHERE " + idColumnName() + " = ?";
        engine.checkReadOnly(sql);   // 写 SQL 过只读防线
        try (Connection conn = engine.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            idField.setAccessible(true);
            ps.setObject(1, idField.get(entity));
            return ps.executeUpdate();
        }
    }

    /**
     * ResultSet → 实体对象(反射填字段)。三项健壮性约定:
     * ①缺列跳过:结果集无该列(SQLite "Column not found")→ 字段置 null 不炸整查询(对齐 SQLAlchemy);
     * ②类型规范化:JDBC 特殊类型(Timestamp/BigDecimal 等)先转 Java 标准类型,Number↔Number 容错
     *   (Integer↔Long 互转),String 字段遇非字符串按 String.valueOf(不再裸抛 IllegalArgumentException);
     * ③基本类型遇 NULL:fail-fast 抛 IllegalStateException 并教学提示改包装类型(不静默填 0/false)。
     */
    private T mapRow(ResultSet rs) throws Exception {
        T entity = entityClass.getDeclaredConstructor().newInstance();
        for (FieldInfo fi : fields) {
            Object v;
            try {
                v = rs.getObject(fi.columnName);
            } catch (java.sql.SQLException e) {
                // SELECT 未投影该列 → 跳过(字段保持 null),不中断整条查询
                continue;
            }
            v = adaptValue(v, fi.field.getType());
            fi.field.setAccessible(true);
            if (v == null && fi.field.getType().isPrimitive()) {
                // 基本类型无法承载 NULL —— fail-fast 教学(静默填 0/false 会掩盖数据缺失)
                throw new IllegalStateException("实体 " + entityClass.getSimpleName() + " 字段 '"
                        + fi.field.getName() + "' 是基本类型 " + fi.field.getType()
                        + ",而 DB 该列为 NULL 无法映射;请改用包装类型(Integer/Long/Double/Boolean)");
            }
            fi.field.set(entity, v);
        }
        return entity;
    }

    /**
     * DB 值 → 字段类型适配。
     * <p>适配要点:①目标 BigDecimal 字段经 {@code new BigDecimal(v.toString())} 构造保精度
     * (先 doubleValue() 会截断精度,反射 set 必抛裸 IllegalArgumentException);
     * ②SQLite 的 BOOLEAN 列经 JDBC 是 0/1 Number → Boolean 字段按 {@code !=0} 转;
     * ③enum 字段按 {@code String.valueOf → Enum.valueOf} 转换,失败抛教学型 IAE;
     * ④LocalDate 字段遇 LocalDateTime(MySQL DATETIME 读回)截取日期部分。
     *
     * @param v      Object DB 值(已过 normalize),可为 null
     * @param target Class 目标字段类型
     * @return Object 适配后的值;无法适配时按目标类型做字符串化兜底(String 字段)
     * @throws IllegalArgumentException 当 enum 字段的 DB 值不匹配任何枚举常量时抛出(教学型,含常量提示)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object adaptValue(Object v, Class<?> target) {
        if (v == null) return null;
        // 伪代码:
        //   1. 目标 BigDecimal → 值本就是 BigDecimal 原样返回(保精度);Number/字符串经 String 构造
        //   2. JDBC 特殊类型规范化(Timestamp/Date/Time → java.time)
        //   3. LocalDateTime → LocalDate 字段截日期
        //   4. Number ↔ Number 容错(Integer↔Long↔Double 互转);Number → Boolean 字段按 0/1
        //   5. enum 字段:String.valueOf → Enum.valueOf;无匹配常量抛教学型 IAE
        //   6. String 字段兜底 String.valueOf
        if (target == java.math.BigDecimal.class) {
            if (v instanceof java.math.BigDecimal bd) return bd;
            if (v instanceof Number || v instanceof CharSequence) {
                // 经 String 构造:doubleValue() 会把 123.456789012345678901 截成 17 位有效数字
                return new java.math.BigDecimal(v.toString());
            }
        }
        // JDBC 特殊类型规范化(与 SqlBridge.normalizeJdbcObject 同思路,本地轻量版)
        if (v instanceof java.sql.Timestamp ts) v = ts.toLocalDateTime();
        else if (v instanceof java.sql.Date d) v = d.toLocalDate();
        else if (v instanceof java.sql.Time t) v = t.toLocalTime();
        if (target == java.time.LocalDate.class && v instanceof java.time.LocalDateTime ldt) {
            return ldt.toLocalDate();   // DATETIME 列 → LocalDate 字段(丢时间部分)
        }
        // Number ↔ Number 容错(Integer↔Long↔Double 互转)
        if (v instanceof Number num) {
            if (target == Integer.class || target == int.class) return num.intValue();
            if (target == Long.class || target == long.class) return num.longValue();
            if (target == Double.class || target == double.class) return num.doubleValue();
            if (target == Float.class || target == float.class) return num.floatValue();
            if (target == Short.class || target == short.class) return num.shortValue();
            // SQLite BOOLEAN 列 = INTEGER 0/1 → Boolean 字段(0=false, 非0=true)
            if (target == Boolean.class || target == boolean.class) return num.intValue() != 0;
        }
        // enum 字段 —— DB 常以 VARCHAR 存枚举名;已是枚举实例则原样返回
        if (target.isEnum()) {
            if (target.isInstance(v)) return v;
            String name = String.valueOf(v);
            try {
                return Enum.valueOf((Class<? extends Enum>) target.asSubclass(Enum.class), name);
            } catch (IllegalArgumentException noSuchConstant) {
                throw new IllegalArgumentException("枚举字段 " + target.getName() + " 无常量 '" + name
                        + "';请检查 DB 存值与枚举常量一致(区分大小写)", noSuchConstant);
            }
        }
        // String 字段兜底:非字符串值按 String.valueOf(不再裸抛)
        if (target == String.class && !(v instanceof String)) return String.valueOf(v);
        return v;
    }

    private String idColumnName() throws SQLException {
        for (FieldInfo fi : fields) if (fi.isId) return quoteIdentifier(fi.columnName);
        return quoteIdentifier(idField.getName());
    }

    /** 字段元数据。 */
    private record FieldInfo(Field field, String columnName, boolean isId) {}
}
