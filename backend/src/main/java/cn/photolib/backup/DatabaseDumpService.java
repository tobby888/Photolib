package cn.photolib.backup;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 逻辑备份引擎：按 JDBC 元数据把整库业务数据导出成 JSON Lines，并能原样写回。
 *
 * <p>刻意不依赖 {@code mysqldump}：应用与数据库不一定在同一台机器，服务器上也不一定装了
 * 客户端工具；纯 JDBC 实现还能在 H2 上完整测试。代价是只备份数据、不备份表结构——
 * 表结构由 Flyway 负责，因此回滚前必须校验 schema 版本一致（见 {@link #restore}）。
 *
 * <p>格式（每行一个 JSON 值）：
 * <pre>
 * {"format":"photolib-backup","version":1,...,"tables":[...]}   清单
 * {"table":"app_user","columns":[...],"types":[...]}            表头
 * [1,"admin",null]                                              数据行
 * {"endTable":"app_user","rows":1}                              表尾
 * </pre>
 * 值按表头里的 {@link java.sql.Types} 解码；二进制值编码成 {@code {"b64":"..."}}，
 * 这样不必为每种数据库的 JSON/BLOB 列各写一套转义规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseDumpService {
    /** 备份文件格式版本。改变取值编码方式时必须同时提升它。 */
    public static final int FORMAT_VERSION = 1;
    public static final String FORMAT_NAME = "photolib-backup";

    /**
     * 不导出也不回滚的表。flyway_schema_history 由 Flyway 维护，回滚它会让库结构与迁移
     * 记录对不上；备份目录本身若被回滚，这次回滚刚生成的兜底备份记录就会一起消失。
     */
    static final Set<String> EXCLUDED_TABLES =
            Set.of("flyway_schema_history", "database_backup", "database_restore");

    private static final int FETCH_SIZE = 500;
    private static final int INSERT_BATCH = 200;

    private final DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();

    public record DumpResult(int tableCount, long rowCount, String schemaVersion, int migrationCount) {}

    public record RestoreResult(int tableCount, long rowCount) {}

    public record ValidationResult(int tableCount, long rowCount, String schemaVersion, int migrationCount) {}

    public record SchemaState(String version, int migrationCount) {}

    /**
     * 取值的兼容族。上传的备份未必来自同一种数据库，逐个比对 {@link java.sql.Types}
     * 会把 MySQL 的 BOOLEAN(BIT/TINYINT) 之类的等价表示误判成不合法，所以按族比对。
     * {@link #OTHER} 是通配族：JSON 列在不同驱动上分别报成 OTHER/JAVA_OBJECT/LONGVARCHAR。
     */
    private enum TypeFamily {
        INTEGRAL, REAL, STRING, BINARY, DATE, TIME, TIMESTAMP, OTHER
    }

    /** 导出当前库的全部业务数据。调用方负责关闭 {@code output}。 */
    public DumpResult dump(OutputStream output) throws SQLException, IOException {
        try (Connection connection = dataSource.getConnection()) {
            List<String> tables = listTables(connection);
            SchemaState schema = readSchemaState(connection);
            Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);

            ObjectNode manifest = json.createObjectNode();
            manifest.put("format", FORMAT_NAME);
            manifest.put("version", FORMAT_VERSION);
            manifest.put("generatedAt", LocalDateTime.now().toString());
            manifest.put("product", connection.getMetaData().getDatabaseProductName());
            manifest.put("schemaVersion", schema.version());
            manifest.put("migrationCount", schema.migrationCount());
            ArrayNode tableNames = manifest.putArray("tables");
            tables.forEach(tableNames::add);
            writeLine(writer, manifest);

            long total = 0;
            for (String table : tables) {
                total += dumpTable(connection, writer, table);
            }
            writer.flush();
            return new DumpResult(tables.size(), total, schema.version(), schema.migrationCount());
        }
    }

    /**
     * 用备份内容整体替换当前库的业务数据。全过程在一个事务里完成：先清空清单中的每张表，
     * 再逐表写回，任何一步失败都会回滚，不会留下"删了一半"的库。
     */
    public RestoreResult restore(InputStream input) throws SQLException, IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        JsonNode manifest = readManifest(reader);
        List<String> tables = manifestTables(manifest);

        try (Connection connection = dataSource.getConnection()) {
            verifySchemaMatches(connection, manifest);
            Map<String, Map<String, Integer>> live = liveColumns(connection);
            verifyTablesExist(tables, live);

            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            setReferentialIntegrity(connection, false);
            try {
                for (String table : tables) {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("DELETE FROM " + quote(connection, table));
                    }
                }
                long rows = applyRows(connection, reader, live);
                connection.commit();
                return new RestoreResult(tables.size(), rows);
            } catch (RuntimeException | SQLException | IOException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    log.error("数据库回滚事务撤销失败", rollbackFailure);
                }
                throw failure;
            } finally {
                // 外键开关是会话级的，连接归还连接池前必须恢复，否则后续业务写入会失去约束保护。
                try {
                    setReferentialIntegrity(connection, true);
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            }
        }
    }

    /** 读取当前库已应用的 Flyway 版本，用于备份清单与回滚前的一致性校验。 */
    public SchemaState currentSchemaState() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return readSchemaState(connection);
        }
    }

    private long dumpTable(Connection connection, Writer writer, String table)
            throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(FETCH_SIZE);
            try (ResultSet rows = statement.executeQuery("SELECT * FROM " + quote(connection, table))) {
                ResultSetMetaData meta = rows.getMetaData();
                int columnCount = meta.getColumnCount();
                int[] types = new int[columnCount];
                ObjectNode header = json.createObjectNode();
                header.put("table", table);
                ArrayNode columns = header.putArray("columns");
                ArrayNode typeNodes = header.putArray("types");
                for (int index = 1; index <= columnCount; index++) {
                    columns.add(meta.getColumnName(index));
                    types[index - 1] = meta.getColumnType(index);
                    typeNodes.add(types[index - 1]);
                }
                writeLine(writer, header);

                long count = 0;
                while (rows.next()) {
                    ArrayNode row = json.createArrayNode();
                    for (int index = 1; index <= columnCount; index++) {
                        row.add(readValue(rows, index, types[index - 1]));
                    }
                    writeLine(writer, row);
                    count++;
                }
                ObjectNode footer = json.createObjectNode();
                footer.put("endTable", table);
                footer.put("rows", count);
                writeLine(writer, footer);
                return count;
            }
        }
    }

    /**
     * 干跑一遍备份文件：确认它确实是本系统产出的格式、结构版本与当前库一致，
     * 并且每张表、每个字段、每行的形态都能对上当前的表结构。只读元数据，不写任何数据。
     *
     * <p>管理员上传的文件必须先过这一关再入库，否则一个残缺或伪造的文件会在回滚
     * 事务跑到一半时才报错——那时整库已经被清空，只能靠兜底备份救回来。
     */
    public ValidationResult validate(InputStream input) throws SQLException, IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        JsonNode manifest = readManifest(reader);
        List<String> tables = manifestTables(manifest);
        try (Connection connection = dataSource.getConnection()) {
            SchemaState schema = readSchemaState(connection);
            verifySchemaMatches(connection, manifest);
            Map<String, Map<String, Integer>> live = liveColumns(connection);
            verifyTablesExist(tables, live);

            Set<String> seen = new LinkedHashSet<>();
            long total = walk(reader, live, new BlockVisitor() {
                private String current;

                @Override
                public void table(String table, List<String> columns, int[] types) {
                    if (!containsIgnoreCase(tables, table)) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                                "备份文件包含清单之外的数据表：" + table);
                    }
                    if (!seen.add(table.toLowerCase(Locale.ROOT))) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                                "备份文件中的数据表重复出现：" + table);
                    }
                    current = table;
                }

                @Override
                public void row(JsonNode row) {
                    for (int index = 0; index < row.size(); index++) {
                        JsonNode value = row.get(index);
                        boolean scalar = value.isNull() || value.isTextual() || value.isNumber()
                                || value.isBoolean()
                                || (value.isObject() && value.size() == 1 && value.path("b64").isTextual());
                        if (!scalar) {
                            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                                    "备份文件中 " + current + " 表存在无法识别的字段取值");
                        }
                    }
                }

                @Override
                public void endTable(String table, long declared, long actual) {
                    if (declared >= 0 && declared != actual) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                                "备份文件中 %s 表声明 %d 行，实际 %d 行".formatted(table, declared, actual));
                    }
                }
            });

            List<String> absent = tables.stream()
                    .filter(table -> !seen.contains(table.toLowerCase(Locale.ROOT))).toList();
            if (!absent.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "备份文件缺少清单声明的数据表：" + String.join("、", absent));
            }
            return new ValidationResult(tables.size(), total, schema.version(), schema.migrationCount());
        }
    }

    private long applyRows(Connection connection, BufferedReader reader,
                           Map<String, Map<String, Integer>> live) throws SQLException, IOException {
        class Inserter implements BlockVisitor {
            private PreparedStatement insert;
            private int[] types = new int[0];
            private int pending;

            @Override
            public void table(String table, List<String> columns, int[] columnTypes) throws SQLException {
                flush();
                types = columnTypes;
                insert = connection.prepareStatement(insertSql(connection, table, columns));
            }

            @Override
            public void row(JsonNode row) throws SQLException {
                if (insert == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备份文件的数据行缺少表头");
                }
                for (int index = 0; index < types.length; index++) {
                    bindValue(insert, index + 1, types[index], row.get(index));
                }
                insert.addBatch();
                if (++pending >= INSERT_BATCH) {
                    insert.executeBatch();
                    pending = 0;
                }
            }

            @Override
            public void endTable(String table, long declared, long actual) throws SQLException {
                flush();
            }

            void flush() throws SQLException {
                if (insert == null) return;
                if (pending > 0) insert.executeBatch();
                insert.close();
                insert = null;
                pending = 0;
            }
        }

        Inserter inserter = new Inserter();
        try {
            long total = walk(reader, live, inserter);
            inserter.flush();
            return total;
        } finally {
            if (inserter.insert != null) inserter.insert.close();
        }
    }

    /** 备份文件按块（表头 → 数据行 → 表尾）读取时的回调，校验与写入共用同一套解析。 */
    private interface BlockVisitor {
        void table(String table, List<String> columns, int[] types) throws SQLException;

        void row(JsonNode row) throws SQLException;

        void endTable(String table, long declaredRows, long actualRows) throws SQLException;
    }

    /**
     * 逐行解析备份内容并回调访问者。表头一出现就按当前库的表结构校验字段，
     * 数据行的字段数量也在这里核对——校验和写回两条路径都靠它保证形态正确。
     */
    private long walk(BufferedReader reader, Map<String, Map<String, Integer>> live, BlockVisitor visitor)
            throws SQLException, IOException {
        long total = 0;
        long tableRows = 0;
        String current = null;
        int columnCount = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode node = readJson(line);
            if (node.isArray()) {
                if (current == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备份文件的数据行缺少表头");
                }
                if (node.size() != columnCount) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "备份文件中 %s 表的数据行有 %d 个字段，表头声明 %d 个"
                                    .formatted(current, node.size(), columnCount));
                }
                visitor.row(node);
                total++;
                tableRows++;
                continue;
            }
            if (!node.isObject()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备份文件包含无法识别的数据块");
            }
            if (node.has("endTable")) {
                if (current == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备份文件的表尾缺少对应表头");
                }
                visitor.endTable(current, node.path("rows").isNumber() ? node.path("rows").asLong() : -1,
                        tableRows);
                current = null;
                continue;
            }
            if (!node.has("table")) continue;
            String table = node.get("table").asText();
            List<String> columns = new ArrayList<>();
            node.path("columns").forEach(column -> columns.add(column.asText()));
            JsonNode typeNodes = node.path("types");
            if (columns.isEmpty() || typeNodes.size() != columns.size()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "备份文件中 " + table + " 表的表头字段定义不完整");
            }
            int[] types = new int[typeNodes.size()];
            for (int index = 0; index < types.length; index++) {
                types[index] = typeNodes.get(index).asInt();
            }
            verifyColumns(table, columns, types, live);
            visitor.table(table, columns, types);
            current = table;
            columnCount = columns.size();
            tableRows = 0;
        }
        if (current != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "备份文件在 " + current + " 表中间意外结束");
        }
        return total;
    }

    /** 表头里的字段必须与当前库的该表完全对应，类型也要属于同一个兼容族。 */
    private void verifyColumns(String table, List<String> columns, int[] types,
                               Map<String, Map<String, Integer>> live) {
        Map<String, Integer> actual = live.get(table);
        if (actual == null) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "当前数据库缺少备份中的数据表：" + table);
        }
        Map<String, Integer> declared = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            if (declared.put(columns.get(index).toLowerCase(Locale.ROOT), types[index]) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "备份文件中 " + table + " 表的字段重复：" + columns.get(index));
            }
        }
        Map<String, Integer> present = new LinkedHashMap<>();
        actual.forEach((name, type) -> present.put(name.toLowerCase(Locale.ROOT), type));

        List<String> missing = present.keySet().stream().filter(name -> !declared.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "备份文件中 %s 表缺少字段：%s".formatted(table, String.join("、", missing)));
        }
        List<String> unknown = declared.keySet().stream().filter(name -> !present.containsKey(name)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "备份文件中 %s 表包含当前表结构没有的字段：%s".formatted(table, String.join("、", unknown)));
        }
        declared.forEach((name, type) -> {
            TypeFamily backup = family(type);
            TypeFamily current = family(present.get(name));
            if (backup != TypeFamily.OTHER && current != TypeFamily.OTHER && backup != current) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "备份文件中 %s.%s 的字段类型与当前表结构不一致".formatted(table, name));
            }
        });
    }

    private TypeFamily family(int type) {
        return switch (type) {
            case Types.BIT, Types.BOOLEAN, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                    TypeFamily.INTEGRAL;
            case Types.REAL, Types.FLOAT, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC -> TypeFamily.REAL;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> TypeFamily.STRING;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> TypeFamily.BINARY;
            case Types.DATE -> TypeFamily.DATE;
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> TypeFamily.TIME;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> TypeFamily.TIMESTAMP;
            default -> TypeFamily.OTHER;
        };
    }

    private JsonNode readJson(String line) {
        try {
            return json.readTree(line);
        } catch (IOException malformed) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备份文件包含无法解析的内容");
        }
    }

    private JsonNode readManifest(BufferedReader reader) throws IOException {
        String manifestLine = reader.readLine();
        if (manifestLine == null || manifestLine.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备份文件为空");
        }
        JsonNode manifest = readJson(manifestLine);
        if (!FORMAT_NAME.equals(manifest.path("format").asText())
                || manifest.path("version").asInt() != FORMAT_VERSION) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备份文件格式无法识别");
        }
        return manifest;
    }

    private List<String> manifestTables(JsonNode manifest) {
        List<String> tables = new ArrayList<>();
        manifest.path("tables").forEach(node -> tables.add(node.asText()));
        if (tables.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备份文件没有任何数据表");
        }
        List<String> forbidden = tables.stream()
                .filter(table -> EXCLUDED_TABLES.contains(table.toLowerCase(Locale.ROOT))).toList();
        if (!forbidden.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "备份文件不得包含由系统自身维护的数据表：" + String.join("、", forbidden));
        }
        return tables;
    }

    private void verifyTablesExist(List<String> tables, Map<String, Map<String, Integer>> live) {
        List<String> missing = tables.stream().filter(table -> !live.containsKey(table)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "当前数据库缺少备份中的数据表：" + String.join("、", missing));
        }
    }

    private boolean containsIgnoreCase(List<String> values, String candidate) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(candidate));
    }

    /** 当前库里可备份的表及其字段类型，键忽略大小写。 */
    private Map<String, Map<String, Integer>> liveColumns(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<>(listTables(connection));
        Map<String, Map<String, Integer>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet columns = meta.getColumns(connection.getCatalog(), connection.getSchema(), "%", "%")) {
            while (columns.next()) {
                String table = columns.getString("TABLE_NAME");
                if (table == null || !tables.contains(table)) continue;
                result.computeIfAbsent(table, key -> new LinkedHashMap<>())
                        .put(columns.getString("COLUMN_NAME"), columns.getInt("DATA_TYPE"));
            }
        }
        return result;
    }

    private String insertSql(Connection connection, String table, List<String> columns) throws SQLException {
        if (columns.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备份文件中的表 " + table + " 没有字段定义");
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(quote(connection, table)).append(" (");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) sql.append(", ");
            sql.append(quote(connection, columns.get(index)));
        }
        sql.append(") VALUES (");
        sql.append("?, ".repeat(columns.size() - 1));
        sql.append("?)");
        return sql.toString();
    }

    private JsonNode readValue(ResultSet rows, int index, int type) throws SQLException {
        Object value = switch (type) {
            case Types.BIT, Types.BOOLEAN -> rows.getObject(index, Boolean.class);
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> rows.getObject(index, Integer.class);
            case Types.BIGINT -> rows.getObject(index, Long.class);
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> rows.getObject(index, Double.class);
            case Types.DECIMAL, Types.NUMERIC -> rows.getBigDecimal(index);
            case Types.DATE -> rows.getObject(index, LocalDate.class);
            case Types.TIME -> rows.getObject(index, LocalTime.class);
            case Types.TIMESTAMP -> rows.getObject(index, LocalDateTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE -> rows.getObject(index, OffsetDateTime.class);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> rows.getBytes(index);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> rows.getString(index);
            // JSON 列在不同驱动上分别报成 OTHER / JAVA_OBJECT，取值可能是 String 也可能是 byte[]，
            // 所以这里保留原始对象，由下面按运行时类型编码。
            default -> rows.getObject(index);
        };
        if (rows.wasNull() || value == null) return json.nullNode();
        return switch (value) {
            case byte[] bytes -> json.createObjectNode().put("b64", Base64.getEncoder().encodeToString(bytes));
            case Boolean bool -> json.getNodeFactory().booleanNode(bool);
            case Integer number -> json.getNodeFactory().numberNode(number);
            case Long number -> json.getNodeFactory().numberNode(number);
            case Double number -> json.getNodeFactory().numberNode(number);
            case BigDecimal number -> json.getNodeFactory().textNode(number.toPlainString());
            default -> json.getNodeFactory().textNode(String.valueOf(value));
        };
    }

    private void bindValue(PreparedStatement insert, int index, int type, JsonNode node) throws SQLException {
        if (node == null || node.isNull()) {
            insert.setNull(index, type);
            return;
        }
        if (node.isObject() && node.has("b64")) {
            insert.setBytes(index, Base64.getDecoder().decode(node.get("b64").asText()));
            return;
        }
        switch (type) {
            case Types.BIT, Types.BOOLEAN -> insert.setBoolean(index, node.asBoolean());
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> insert.setInt(index, node.asInt());
            case Types.BIGINT -> insert.setLong(index, node.asLong());
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> insert.setDouble(index, node.asDouble());
            case Types.DECIMAL, Types.NUMERIC -> insert.setBigDecimal(index, new BigDecimal(node.asText()));
            case Types.DATE -> insert.setObject(index, LocalDate.parse(node.asText()));
            case Types.TIME -> insert.setObject(index, LocalTime.parse(node.asText()));
            case Types.TIMESTAMP -> insert.setObject(index, LocalDateTime.parse(node.asText()));
            case Types.TIMESTAMP_WITH_TIMEZONE -> insert.setObject(index, OffsetDateTime.parse(node.asText()));
            default -> insert.setString(index, node.asText());
        }
    }

    private void writeLine(Writer writer, JsonNode node) throws IOException {
        writer.write(json.writeValueAsString(node));
        writer.write('\n');
    }

    private List<String> listTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet found = meta.getTables(connection.getCatalog(), connection.getSchema(), "%",
                new String[]{"TABLE"})) {
            while (found.next()) {
                String name = found.getString("TABLE_NAME");
                if (name == null || EXCLUDED_TABLES.contains(name.toLowerCase(Locale.ROOT))) continue;
                tables.add(name);
            }
        }
        tables.sort(String::compareToIgnoreCase);
        return tables;
    }

    private SchemaState readSchemaState(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) AS applied, MAX(installed_rank) AS latest"
                             + " FROM flyway_schema_history WHERE success = TRUE")) {
            if (!result.next()) return new SchemaState("unknown", 0);
            int applied = result.getInt("applied");
            int latest = result.getInt("latest");
            return applied == 0 ? new SchemaState("unknown", 0)
                    : new SchemaState(version(connection, latest), applied);
        } catch (SQLException missingHistory) {
            log.warn("无法读取 Flyway 迁移历史，备份将记录为 unknown", missingHistory);
            return new SchemaState("unknown", 0);
        }
    }

    private String version(Connection connection, int installedRank) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version FROM flyway_schema_history WHERE installed_rank = ?")) {
            statement.setInt(1, installedRank);
            try (ResultSet result = statement.executeQuery()) {
                String value = result.next() ? result.getString(1) : null;
                return value == null || value.isBlank() ? "unknown" : value;
            }
        }
    }

    private void verifySchemaMatches(Connection connection, JsonNode manifest) throws SQLException {
        SchemaState current = readSchemaState(connection);
        String backupVersion = manifest.path("schemaVersion").asText("unknown");
        int backupCount = manifest.path("migrationCount").asInt();
        if (!current.version().equals(backupVersion) || current.migrationCount() != backupCount) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "备份对应的数据库结构版本（%s）与当前版本（%s）不一致，无法回滚"
                            .formatted(backupVersion, current.version()));
        }
    }

    private void setReferentialIntegrity(Connection connection, boolean enabled) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        String sql;
        if (product.contains("mysql") || product.contains("mariadb")) {
            sql = "SET FOREIGN_KEY_CHECKS = " + (enabled ? "1" : "0");
        } else if (product.contains("h2")) {
            sql = "SET REFERENTIAL_INTEGRITY " + (enabled ? "TRUE" : "FALSE");
        } else {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "当前数据库（" + product + "）不支持自动回滚");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String quote(Connection connection, String identifier) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) return identifier;
        return quote + identifier.replace(quote, quote + quote) + quote;
    }
}
