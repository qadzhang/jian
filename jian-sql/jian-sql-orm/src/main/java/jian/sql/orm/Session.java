package jian.sql.orm;

import jian.sql.engine.Engine;
import jian.sql.expr.SqlBuilder;

import java.lang.reflect.Field;
import java.sql.Connection;
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

    public Session(Engine engine, Class<T> entityClass) {
        this.engine = engine;
        this.entityClass = entityClass;
        Table t = entityClass.getAnnotation(Table.class);
        if (t == null) {
            throw new IllegalArgumentException("实体类 " + entityClass.getName() + " 缺少 @Table 注解");
        }
        this.tableName = t.value();
        Field idF = null;
        List<FieldInfo> fs = new ArrayList<>();
        for (Field f : entityClass.getDeclaredFields()) {
            Id idAnno = f.getAnnotation(Id.class);
            Column colAnno = f.getAnnotation(Column.class);
            if (idAnno != null) {
                idF = f;
                fs.add(new FieldInfo(f, colAnno != null ? colAnno.value() : f.getName(), true));
            } else if (colAnno != null) {
                fs.add(new FieldInfo(f, colAnno.value(), false));
            }
        }
        this.idField = idF;
        this.fields = fs;
    }

    /** 按主键查(对齐 sqlalchemy session.get)。 */
    public T findById(Object id) throws Exception {
        if (idField == null) throw new IllegalStateException("实体无 @Id 字段");
        try (Connection conn = engine.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM " + tableName + " WHERE " + idColumnName() + " = ?")) {
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
             ResultSet rs = st.executeQuery("SELECT * FROM " + tableName)) {
            List<T> out = new ArrayList<>();
            while (rs.next()) out.add(mapRow(rs));
            return out;
        }
    }

    /** 插入(对齐 sqlalchemy session.add)。 */
    public int insert(T entity) throws Exception {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) { sql.append(','); ph.append(','); }
            sql.append(fields.get(i).columnName);
            ph.append('?');
        }
        sql.append(") VALUES (").append(ph).append(')');
        try (Connection conn = engine.connect();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i).field;
                f.setAccessible(true);
                ps.setObject(i + 1, f.get(entity));
            }
            return ps.executeUpdate();
        }
    }

    /** 更新(按主键更新所有字段)。 */
    public int update(T entity) throws Exception {
        if (idField == null) throw new IllegalStateException("实体无 @Id 字段,无法 update");
        StringBuilder sql = new StringBuilder("UPDATE ").append(tableName).append(" SET ");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append(fields.get(i).columnName).append("=?");
        }
        sql.append(" WHERE ").append(idColumnName()).append("=?");
        try (Connection conn = engine.connect();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int p = 1;
            for (FieldInfo fi : fields) {
                fi.field.setAccessible(true);
                ps.setObject(p++, fi.field.get(entity));
            }
            idField.setAccessible(true);
            ps.setObject(p, idField.get(entity));
            return ps.executeUpdate();
        }
    }

    /** 删除(按主键)。 */
    public int delete(T entity) throws Exception {
        if (idField == null) throw new IllegalStateException("实体无 @Id 字段,无法 delete");
        try (Connection conn = engine.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM " + tableName + " WHERE " + idColumnName() + " = ?")) {
            idField.setAccessible(true);
            ps.setObject(1, idField.get(entity));
            return ps.executeUpdate();
        }
    }

    /** ResultSet → 实体对象(反射填字段)。 */
    private T mapRow(ResultSet rs) throws Exception {
        T entity = entityClass.getDeclaredConstructor().newInstance();
        for (FieldInfo fi : fields) {
            Object v = rs.getObject(fi.columnName);
            fi.field.setAccessible(true);
            fi.field.set(entity, v);
        }
        return entity;
    }

    private String idColumnName() {
        for (FieldInfo fi : fields) if (fi.isId) return fi.columnName;
        return idField.getName();
    }

    /** 字段元数据。 */
    private record FieldInfo(Field field, String columnName, boolean isId) {}
}
