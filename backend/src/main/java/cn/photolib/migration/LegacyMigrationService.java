package cn.photolib.migration;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.net.URLDecoder;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LegacyMigrationService {
    private final JdbcTemplate target;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public Result migrate(LegacyMigrationProperties properties) {
        JdbcTemplate source = sourceTemplate(properties);
        // Fail before touching the new database if the legacy connection/schema is unavailable.
        source.queryForObject("SELECT COUNT(*) FROM users", Long.class);

        Result result = transactionTemplate.execute(status -> {
            int users = migrateUsers(source);
            Long fallbackUser = target.queryForObject(
                    "SELECT id FROM app_user WHERE deleted = FALSE ORDER BY CASE WHEN role = 'ADMIN' THEN 0 ELSE 1 END, id LIMIT 1",
                    Long.class);
            int projects = migrateProjects(source, fallbackUser);
            int photos = migratePhotos(source, fallbackUser);
            return new Result(users, projects, photos);
        });
        if (result == null) {
            throw new IllegalStateException("迁移事务未返回结果");
        }
        return result;
    }

    private int migrateUsers(JdbcTemplate source) {
        int count = 0;
        for (Map<String, Object> row : source.queryForList("""
                SELECT id, username, hashed_password, role, created_date, real_name, student_id, email, is_frozen
                FROM users ORDER BY id
                """)) {
            long sourceId = number(row.get("id"));
            if (mapped("USER", sourceId) != null) {
                continue;
            }
            String username = text(row.get("username"), "legacy-" + sourceId);
            List<Long> existing = target.queryForList(
                    "SELECT id FROM app_user WHERE username = ? LIMIT 1", Long.class, username);
            long targetId;
            if (!existing.isEmpty()) {
                targetId = existing.getFirst();
            } else {
                target.update("""
                        INSERT INTO app_user
                        (username, password_hash, display_name, role, email, enabled, must_change_password,
                         version, deleted, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, FALSE, 1, FALSE, ?, ?)
                        """,
                        username,
                        text(row.get("hashed_password"), "!legacy-account-without-password!"),
                        limit(text(row.get("real_name"), username), 100),
                        legacyRole(row.get("role")),
                        row.get("email"),
                        !Boolean.TRUE.equals(row.get("is_frozen")),
                        timestamp(row.get("created_date")),
                        timestamp(row.get("created_date")));
                targetId = target.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
            }
            remember("USER", sourceId, targetId);
            count++;
        }
        return count;
    }

    private int migrateProjects(JdbcTemplate source, long fallbackUser) {
        int count = 0;
        for (Map<String, Object> row : source.queryForList("""
                SELECT id, title, description, created_by, created_date FROM projects ORDER BY id
                """)) {
            long sourceId = number(row.get("id"));
            if (mapped("PROJECT", sourceId) != null) {
                continue;
            }
            Long creator = mapped("USER", nullableNumber(row.get("created_by")));
            target.update("""
                    INSERT INTO project
                    (title, description, status, created_by, version, deleted, created_at, updated_at)
                    VALUES (?, ?, 'ACTIVE', ?, 1, FALSE, ?, ?)
                    """,
                    limit(text(row.get("title"), "旧项目 " + sourceId), 200),
                    row.get("description"),
                    creator == null ? fallbackUser : creator,
                    timestamp(row.get("created_date")),
                    timestamp(row.get("created_date")));
            Long id = target.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            remember("PROJECT", sourceId, id);
            count++;
        }
        return count;
    }

    private int migratePhotos(JdbcTemplate source, long fallbackUser) {
        Map<Long, List<String>> tags = source.query("""
                SELECT pt.photo_id, t.name
                FROM photo_tags pt JOIN tags t ON t.id = pt.tag_id
                ORDER BY pt.photo_id, t.name
                """, rs -> {
            Map<Long, List<String>> grouped = new java.util.HashMap<>();
            while (rs.next()) {
                grouped.computeIfAbsent(rs.getLong(1), ignored -> new java.util.ArrayList<>()).add(rs.getString(2));
            }
            return grouped;
        });

        int count = 0;
        for (Map<String, Object> row : source.queryForList("""
                SELECT p.id, p.original_filename, p.file_path, p.file_size, p.mime_type, p.width, p.height,
                       p.taken_date, p.imported_date, p.uploaded_by, p.preview_path, p.author,
                       u.real_name, u.student_id, pr.id AS sample_project_id
                FROM photos p
                LEFT JOIN users u ON u.id = p.uploaded_by
                LEFT JOIN projects pr ON pr.sample_photo_id = p.id
                ORDER BY p.id
                """)) {
            long sourceId = number(row.get("id"));
            if (mapped("PHOTO", sourceId) != null) {
                continue;
            }
            String objectKey = text(row.get("file_path"), null);
            if (!StringUtils.hasText(objectKey)) {
                throw new IllegalStateException("旧照片 " + sourceId + " 缺少 file_path");
            }
            Long uploader = mapped("USER", nullableNumber(row.get("uploaded_by")));
            Long project = mapped("PROJECT", nullableNumber(row.get("sample_project_id")));
            LocalDateTime takenAt = dateTime(row.get("taken_date"));
            LocalDateTime createdAt = dateTime(row.get("imported_date"));
            target.update("""
                    INSERT INTO photo
                    (project_id, title, photographer_student_id, photographer_name, uploaded_by, taken_at,
                     tags_json, width, height, size, content_type, object_key, thumbnail_object_key,
                     stored_file_name, sha256, status, version, deleted, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', 1, FALSE, ?, ?)
                    """,
                    project,
                    limit(text(row.get("original_filename"), null), 200),
                    limit(text(row.get("student_id"), "legacy-" + sourceId), 64),
                    limit(text(row.get("author"), text(row.get("real_name"), "旧系统用户")), 100),
                    uploader == null ? fallbackUser : uploader,
                    takenAt == null ? createdAt : takenAt,
                    json(tags.getOrDefault(sourceId, List.of())),
                    row.get("width"),
                    row.get("height"),
                    row.get("file_size") == null ? 0L : row.get("file_size"),
                    text(row.get("mime_type"), "application/octet-stream"),
                    objectKey,
                    row.get("preview_path"),
                    limit(text(row.get("original_filename"), null), 255),
                    sha256(objectKey),
                    createdAt,
                    createdAt);
            Long id = target.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            remember("PHOTO", sourceId, id);
            // 归属链接：项目相册/计数以 photo_project 为准。此处仅样图归属；完整的标签归属由
            // scripts/restore_project_photos.py 重建。
            if (project != null && id != null) {
                target.update("INSERT INTO photo_project (photo_id, project_id) VALUES (?, ?)", id, project);
            }
            count++;
        }
        return count;
    }

    private JdbcTemplate sourceTemplate(LegacyMigrationProperties properties) {
        if (!StringUtils.hasText(properties.databaseUrl())) {
            throw new IllegalArgumentException("IS_MIGRATE=true 时必须设置 OLD_DATABASE_URL");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        SourceConnection connection = sourceConnection(properties);
        dataSource.setUrl(connection.url());
        dataSource.setUsername(connection.username());
        dataSource.setPassword(connection.password());
        return new JdbcTemplate(dataSource);
    }

    private static SourceConnection sourceConnection(LegacyMigrationProperties properties) {
        String rawUrl = properties.databaseUrl();
        if (rawUrl.startsWith("postgresql://") || rawUrl.startsWith("postgres://")) {
            URI uri = URI.create(rawUrl.replaceFirst("^postgres://", "postgresql://"));
            String username = properties.databaseUsername();
            String password = properties.databasePassword();
            if (uri.getRawUserInfo() != null) {
                String[] credentials = uri.getRawUserInfo().split(":", 2);
                username = decode(credentials[0]);
                password = credentials.length == 2 ? decode(credentials[1]) : "";
            }
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost()
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + uri.getRawPath() + query;
            return new SourceConnection(jdbcUrl, username, password);
        }
        return new SourceConnection(normalizeJdbcUrl(rawUrl),
                properties.databaseUsername(), properties.databasePassword());
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    static String normalizeJdbcUrl(String url) {
        if (url.startsWith("jdbc:")) {
            return url;
        }
        if (url.startsWith("postgresql://") || url.startsWith("postgres://")) {
            return "jdbc:postgresql://" + url.substring(url.indexOf("://") + 3);
        }
        throw new IllegalArgumentException("OLD_DATABASE_URL 必须是 jdbc: URL 或 PostgreSQL URL");
    }

    private Long mapped(String type, Long sourceId) {
        if (sourceId == null) return null;
        List<Long> ids = target.queryForList(
                "SELECT target_id FROM legacy_migration_item WHERE source_type = ? AND source_id = ?",
                Long.class, type, sourceId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private void remember(String type, long sourceId, long targetId) {
        target.update("INSERT INTO legacy_migration_item(source_type, source_id, target_id) VALUES (?, ?, ?)",
                type, sourceId, targetId);
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法序列化旧照片标签", exception);
        }
    }

    private static String legacyRole(Object value) {
        String role = text(value, "").toLowerCase(Locale.ROOT);
        return role.contains("admin") ? "ADMIN" : "MINISTER";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static Long nullableNumber(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static String text(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static LocalDateTime dateTime(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof LocalDateTime dateTime) return dateTime;
        return LocalDateTime.now();
    }

    private static Timestamp timestamp(Object value) {
        return Timestamp.valueOf(dateTime(value));
    }

    public record Result(int users, int projects, int photos) {
    }

    private record SourceConnection(String url, String username, String password) {
    }
}
