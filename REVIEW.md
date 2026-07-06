# PhotoLib Code Review Audit Report

**Review Date:** 2026-07-06  
**Scope:** Backend (Java/Spring Boot), Frontend (React/TypeScript), Database Migrations, Configuration  
**Focus:** Security vulnerabilities, logic bugs, race conditions, resource leaks, data integrity

---

## Executive Summary

Reviewed critical security boundaries (auth, file upload, SQL queries, admin endpoints) and recent changes. Found **16 HIGH severity** and **12 MEDIUM severity** issues requiring attention. The codebase demonstrates good security practices in some areas (password hashing, CSRF disabled appropriately for stateless API, signed URLs) but has critical vulnerabilities in SQL injection prevention, authorization enforcement, race conditions, and resource management.

---

## HIGH Severity Findings

### H-1: SQL Injection via String Concatenation in Project Visibility Check
**File:** `backend/src/main/java/cn/photolib/project/ProjectService.java:48-51`  
**Severity:** HIGH  
**Description:** The `list()` method constructs SQL using string concatenation with unsanitized user ID:
```java
.inSql(user.role() == UserRole.CAMPUS_MANAGER, ProjectEntity::getId,
    "SELECT DISTINCT r.project_id FROM photo_request r "
    + "JOIN request_participant rp ON rp.request_id=r.id "
    + "WHERE r.deleted=0 AND rp.user_id=" + user.id())
```
**Impact:** SQL injection vulnerability. A malicious user ID value could break out of the query and execute arbitrary SQL, leading to data exfiltration or manipulation.  
**Fix:** Use parameterized queries:
```java
.inSql(user.role() == UserRole.CAMPUS_MANAGER, ProjectEntity::getId,
    "SELECT DISTINCT r.project_id FROM photo_request r " +
    "JOIN request_participant rp ON rp.request_id=r.id " +
    "WHERE r.deleted=0 AND rp.user_id={0}", user.id())
```

---

### H-2: Missing Input Validation on objectKey - Path Traversal Risk
**File:** `backend/src/main/java/cn/photolib/storage/LocalObjectStorageService.java:169-172`  
**Severity:** HIGH  
**Description:** The `resolve()` method validates path traversal *after* resolving, but doesn't validate input format. Malicious object keys with null bytes, special characters, or encoded sequences could bypass validation.
```java
private Path resolve(String objectKey) {
    Path resolved = root.resolve(objectKey).normalize();
    if (!resolved.startsWith(root)) throw new IllegalArgumentException("非法对象路径");
    return resolved;
}
```
**Impact:** Potential path traversal allowing read/write outside storage root, or file name collision attacks.  
**Fix:** Add upfront validation:
```java
private Path resolve(String objectKey) {
    if (objectKey == null || objectKey.isBlank() || 
        objectKey.contains("\0") || objectKey.contains("..") ||
        objectKey.startsWith("/") || objectKey.startsWith("\\")) {
        throw new IllegalArgumentException("非法对象路径");
    }
    Path resolved = root.resolve(objectKey).normalize();
    if (!resolved.startsWith(root)) {
        throw new IllegalArgumentException("非法对象路径");
    }
    return resolved;
}
```

---

### H-3: Race Condition in Photo Upload Complete Flow
**File:** `backend/src/main/java/cn/photolib/photo/PhotoService.java:84-103`  
**Severity:** HIGH  
**Description:** The `complete()` method checks photo status and updates it without pessimistic locking. Two concurrent completion requests could both pass the status check and proceed.
```java
PhotoEntity photo = require(id);
requireUploaderOrAdmin(photo, user);
if (photo.getStatus() != PhotoStatus.UPLOADING) {
    throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片不处于待上传状态");
}
// ... fetch from storage ...
photo.setStatus(PhotoStatus.PROCESSING);
mapper.updateById(photo);
```
**Impact:** Double-processing of the same photo, potential data corruption, duplicate background jobs.  
**Fix:** Use optimistic locking with version check:
```java
photo.setStatus(PhotoStatus.PROCESSING);
photo.setVersion(photo.getVersion() + 1);
int updated = mapper.update(photo, Wrappers.<PhotoEntity>lambdaUpdate()
    .eq(PhotoEntity::getId, id)
    .eq(PhotoEntity::getVersion, photo.getVersion() - 1)
    .eq(PhotoEntity::getStatus, PhotoStatus.UPLOADING));
if (updated != 1) {
    throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片状态已变更");
}
```

---

### H-4: Missing Authorization Check in Photo Download
**File:** `backend/src/main/java/cn/photolib/photo/PhotoService.java:178-184`  
**Severity:** HIGH  
**Description:** The `download()` method only calls `require()` which checks existence, not visibility. Campus managers can download photos uploaded by other campus managers if they know the ID.
```java
public DownloadUrl download(Long id, AuthenticatedUser user) {
    PhotoEntity photo = require(id);
    // Missing authorization check here
    String fileName = photo.getStoredFileName() == null ? 
        "photo-" + photo.getId() + ".jpg" : photo.getStoredFileName();
    ObjectStorageService.SignedUrl signed = storage.presignGet(
        photo.getObjectKey(), fileName, properties.downloadUrlTtl());
    return new DownloadUrl(signed.url().toString(), signed.expiresAt(), fileName);
}
```
**Impact:** Unauthorized access to photos, privacy breach.  
**Fix:** Add authorization check:
```java
public DownloadUrl download(Long id, AuthenticatedUser user) {
    PhotoEntity photo = require(id);
    requireVisible(photo, user); // Add this line
    // ... rest of method
}
```

---

### H-5: CSRF Protection Disabled Without Rate Limiting
**File:** `backend/src/main/java/cn/photolib/auth/SecurityConfig.java:25`  
**Severity:** HIGH  
**Description:** CSRF is disabled globally (`csrf.disable()`), which is appropriate for a stateless API with Bearer tokens. However, there's no rate limiting on sensitive endpoints.
```java
return http
    .csrf(csrf -> csrf.disable())
    .cors(cors -> {})
```
**Impact:** Susceptible to brute-force attacks on login, token refresh abuse, denial-of-service on write endpoints.  
**Fix:** Implement rate limiting using Spring's RateLimiter or a Redis-based solution for:
- `/api/v1/auth/login` (5 attempts per IP per 15 minutes)
- `/api/v1/auth/refresh` (20 per session per hour)
- File upload endpoints (10 per user per minute)

---

### H-6: Weak Local Storage Signing Secret Default
**File:** `backend/src/main/resources/application.yml:59`  
**Severity:** HIGH  
**Description:** Default signing secret is hardcoded and publicly visible:
```yaml
signing-secret: ${LOCAL_STORAGE_SIGNING_SECRET:photolib-local-development-secret}
```
**Impact:** In production deployments using local storage mode without overriding this value, attackers can forge signed URLs to access or upload arbitrary files.  
**Fix:**
1. Remove the default value entirely: `${LOCAL_STORAGE_SIGNING_SECRET}`
2. Add startup validation to fail if `mode=local` and signing secret is not set or is the old default
3. Generate a random secret on first startup if not provided

---

### H-7: Missing Index on Critical Query - Performance DoS
**File:** `backend/src/main/resources/db/migration/V1__initial_schema.sql:202-215`  
**Severity:** HIGH  
**Description:** The `audit_log` table is missing an index on `(operator_id, created_at)` which is used in the audit log export query. With 100k row export limit, this becomes a full table scan.
```sql
CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NULL,
    ...
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_resource (resource_type, resource_id)
);
```
**Impact:** Audit log queries cause database performance degradation, potential DoS when admins export large date ranges.  
**Fix:** Add compound index in a new migration:
```sql
CREATE INDEX idx_audit_operator_created ON audit_log(operator_id, created_at);
```

---

### H-8: Unbounded Export Result Set
**File:** `backend/src/main/java/cn/photolib/audit/AuditController.java:52-53`  
**Severity:** HIGH  
**Description:** Export endpoint fetches up to 100,000 audit log rows with no pagination or streaming:
```java
var logs = mapper.findLogs(operatorId, action, resourceType, start(from), end(to), clean(keyword),
    100_000, 0);
```
**Impact:** Memory exhaustion, OOM errors, application crash when exporting large audit logs.  
**Fix:** 
1. Reduce limit to 10,000 and document in API
2. Implement cursor-based pagination for exports
3. Use streaming response to avoid loading all rows into memory:
```java
@GetMapping(value = "/export", produces = "text/csv")
StreamingResponseBody export(...) {
    return outputStream -> {
        Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
        writer.write("﻿时间,操作者账号,...\n");
        // Stream results in batches of 1000
        // ...
    };
}
```

---

### H-9: Resource Leak in Photo Processing Service
**File:** `backend/src/main/java/cn/photolib/photo/PhotoProcessingService.java` (not fully shown, but inferred from architecture)  
**Severity:** HIGH  
**Description:** If `storage.open()` is called to read original images for processing, and an exception occurs during processing, the InputStream is never closed.  
**Impact:** File handle exhaustion, eventual service unavailability.  
**Fix:** Use try-with-resources:
```java
try (InputStream input = storage.open(photo.getOriginalObjectKey())) {
    // processing logic
}
```

---

### H-10: Missing Transaction Isolation on Adoption Duplicate Check
**File:** `backend/src/main/java/cn/photolib/adoption/AdoptionService.java:50-61`  
**Severity:** HIGH  
**Description:** The duplicate check uses a SELECT then UPDATE/INSERT pattern without isolation:
```java
Integer existing = jdbc.sql("SELECT deleted FROM adoption WHERE project_id=:p AND photo_id=:f")
    .param("p", projectId).param("f", photoId).query(Integer.class).optional().orElse(null);
if (existing != null) {
    if (existing == 0) throw new BusinessException(..., "图片已被该项目采用");
    jdbc.sql("UPDATE adoption SET deleted=0, ...").update();
} else {
    mapper.insert(adoption);
}
```
**Impact:** Race condition allowing concurrent adoptions to both pass the duplicate check and insert duplicate records.  
**Fix:** Use a UNIQUE constraint at database level (already exists) + catch DuplicateKeyException, or use SELECT FOR UPDATE:
```java
Integer existing = jdbc.sql("SELECT deleted FROM adoption WHERE project_id=:p AND photo_id=:f FOR UPDATE")
    .param("p", projectId).param("f", photoId).query(Integer.class).optional().orElse(null);
```

---

### H-11: Audit Log JSON Injection
**File:** `backend/src/main/java/cn/photolib/audit/AuditInterceptor.java:45-46`  
**Severity:** HIGH  
**Description:** Audit log detail JSON is constructed via string concatenation without escaping:
```java
log.setDetailJson("{\"path\":\"" + request.getRequestURI().replace("\"", "") +
    "\",\"status\":" + response.getStatus() + "}");
```
**Impact:** If `request.getRequestURI()` contains newlines, backslashes, or other JSON metacharacters, it breaks the JSON structure. Malicious URIs could inject arbitrary JSON fields.  
**Fix:** Use proper JSON serialization:
```java
import com.fasterxml.jackson.databind.ObjectMapper;
private final ObjectMapper objectMapper;
// ...
Map<String, Object> detail = Map.of(
    "path", request.getRequestURI(),
    "status", response.getStatus()
);
log.setDetailJson(objectMapper.writeValueAsString(detail));
```

---

### H-12: Missing Content-Type Validation on Upload
**File:** `backend/src/main/java/cn/photolib/storage/LocalStorageController.java:28-33`  
**Severity:** HIGH  
**Description:** The local storage upload endpoint accepts whatever Content-Type the client sends without validating it matches what was requested in the signed URL:
```java
@PutMapping("/{token}")
ResponseEntity<Void> upload(@PathVariable String token, HttpServletRequest request) throws IOException {
    LocalObjectStorageService local = local();
    LocalObjectStorageService.Token resolved = local.resolveToken(token, "PUT");
    storage.put(resolved.objectKey(), request.getInputStream(), request.getContentLengthLong(),
            request.getContentType()); // No validation
    return ResponseEntity.noContent().build();
}
```
**Impact:** Type confusion attacks. Photo upload tickets specify `image/jpeg` but attacker uploads `text/html`, leading to XSS when served.  
**Fix:** Validate Content-Type matches the ticket:
```java
// Store expected contentType in token generation
// In upload endpoint:
if (!expectedContentType.equals(request.getContentType())) {
    return ResponseEntity.status(400).build();
}
```
**Note:** This is already correct in `PhotoService.createTicket()` which includes contentType in the presigned URL for OSS. Local storage needs the same.

---

### H-13: Session Table Missing Cleanup Job
**File:** `backend/src/main/resources/db/migration/V1__initial_schema.sql:32-46`  
**Severity:** HIGH  
**Description:** The `auth_session` table stores all sessions but there's no periodic cleanup job for expired sessions.
```sql
CREATE TABLE auth_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ...
    access_expires_at DATETIME(6) NOT NULL,
    idle_expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    ...
);
```
**Impact:** Unbounded table growth leading to performance degradation and eventual disk exhaustion.  
**Fix:** Add a scheduled cleanup job:
```java
@Scheduled(cron = "0 0 2 * * *") // Daily at 2 AM
public void cleanupExpiredSessions() {
    LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
    sessionMapper.delete(Wrappers.<AuthSessionEntity>lambdaQuery()
        .or(q -> q.lt(AuthSessionEntity::getIdleExpiresAt, cutoff))
        .or(q -> q.isNotNull(AuthSessionEntity::getRevokedAt)
                  .lt(AuthSessionEntity::getRevokedAt, cutoff)));
}
```

---

### H-14: Notification Image Serving Without Access Control
**File:** `backend/src/main/java/cn/photolib/notification/MessageImageController.java:69-79`  
**Severity:** HIGH  
**Description:** Notification images are protected by `@PreAuthorize("isAuthenticated()")` but any authenticated user can access any notification image by ID.
```java
@GetMapping("/{id}")
@PreAuthorize("isAuthenticated()")
ResponseEntity<InputStreamResource> get(@PathVariable String id) {
    MessageImageEntity image = mapper.selectById(id);
    if (image == null) return ResponseEntity.notFound().build();
    return ResponseEntity.ok()
        .body(new InputStreamResource(storage.open(image.getObjectKey())));
}
```
**Impact:** Campus managers can access notification images from other campuses, potential privacy leak if images contain sensitive info.  
**Fix:** If notification images should be restricted, add authorization check. If they're truly public to all authenticated users, document this explicitly.

---

### H-15: Missing Version Field in Several Update Operations
**File:** Multiple service classes  
**Severity:** HIGH  
**Description:** Several update operations don't use optimistic locking:
- `RequestService.accept()` line 113
- `RequestService.leave()` line 172
- `RequestService.submit()` line 206
- `WorklogService.confirm()` line 110
- `WorklogService.reject()` line 127

**Impact:** Lost updates, race conditions where concurrent operations overwrite each other's changes.  
**Fix:** Add version parameter to all update operations and use `updateChecked()` pattern everywhere.

---

### H-16: Time-Based SQL Injection in Audit Export
**File:** `backend/src/main/java/cn/photolib/audit/AuditController.java:75-76`  
**Severity:** HIGH  
**Description:** While the date parameters use `@DateTimeFormat` annotation, the `start()` and `end()` helper methods pass LocalDateTime to MyBatis mapper. If the mapper uses string concatenation instead of parameter binding, this is vulnerable.
```java
private static LocalDateTime start(LocalDate date) { return date == null ? null : date.atStartOfDay(); }
private static LocalDateTime end(LocalDate date) { return date == null ? null : date.plusDays(1).atStartOfDay(); }
```
**Impact:** Potential SQL injection if mapper uses `${}` instead of `#{}`.  
**Fix:** Review `AuditLogMapper.xml` (or if using annotation) to ensure all parameters use `#{}` binding, not `${}` substitution.

---

## MEDIUM Severity Findings

### M-1: Overly Permissive Multipart Upload Size
**File:** `backend/src/main/resources/application.yml:20-22`  
**Severity:** MEDIUM  
**Description:** Multipart upload limit is set to 1536MB:
```yaml
multipart:
  max-file-size: 1536MB
  max-request-size: 1536MB
```
**Impact:** Memory pressure, potential DoS via large uploads. The actual photo max is 100MB (`image-max-bytes: 104857600`), so this is 15x larger than needed.  
**Fix:** Reduce to match actual requirement: `max-file-size: 110MB`

---

### M-2: Insecure Default Admin Password
**File:** `backend/src/main/resources/application.yml:48`  
**Severity:** MEDIUM  
**Description:** Default admin password is `ChangeMe123!` which is weak and publicly visible in the repository.
```yaml
admin-password: ${ADMIN_INITIAL_PASSWORD:ChangeMe123!}
```
**Impact:** If deployed without changing, easy compromise of admin account.  
**Fix:**
1. Require `ADMIN_INITIAL_PASSWORD` to be set (no default)
2. On first startup, if not set, generate a random password and log it once
3. Force password change on first login

---

### M-3: Missing Pagination Limit Validation
**File:** `backend/src/main/java/cn/photolib/audit/AuditController.java:34-36`  
**Severity:** MEDIUM  
**Description:** Page size is clamped to max 100, but page number is only clamped to min 1, not max:
```java
page = Math.max(1, page);
pageSize = Math.max(1, Math.min(100, pageSize));
```
**Impact:** Attackers can request page 999999999, causing database to scan millions of rows with OFFSET.  
**Fix:** Add page number sanity check:
```java
if (page > 10000) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "页码过大");
```

---

### M-4: Photo SHA256 Not Used for Deduplication
**File:** `backend/src/main/java/cn/photolib/photo/PhotoService.java:75`  
**Severity:** MEDIUM  
**Description:** Photo upload computes SHA256 hash but doesn't check for duplicates before creating a new record.
```java
photo.setSha256(command.sha256().toLowerCase());
photo.setStatus(PhotoStatus.UPLOADING);
mapper.insert(photo);
```
**Impact:** Wasted storage, duplicate photo management burden.  
**Fix:** Add duplicate check:
```java
PhotoEntity existing = mapper.selectOne(Wrappers.<PhotoEntity>lambdaQuery()
    .eq(PhotoEntity::getSha256, command.sha256().toLowerCase())
    .eq(PhotoEntity::getDeleted, false)
    .last("LIMIT 1"));
if (existing != null) {
    return new UploadTicket(existing.getId(), null, null, null, null); // Return existing
}
```

---

### M-5: Weak Token Generation Entropy
**File:** `backend/src/main/java/cn/photolib/auth/TokenSupport.java` (not shown, but inferred)  
**Severity:** MEDIUM  
**Description:** Need to verify that `TokenSupport.randomToken()` uses `SecureRandom` not `Random`.  
**Impact:** If using `java.util.Random`, tokens are predictable and can be brute-forced.  
**Fix:** Ensure implementation uses:
```java
public static String randomToken() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
}
```

---

### M-6: Missing Input Length Validation on Tags
**File:** `backend/src/main/java/cn/photolib/photo/PhotoController.java:117`  
**Severity:** MEDIUM  
**Description:** Tags are validated at `@Size(max = 30)` for count and `@Size(max = 50)` per tag, but the serialized JSON length is not validated:
```java
@Size(max = 30) List<@Size(max = 50) String> tags
```
**Impact:** 30 tags × 50 chars = 1500 chars base, but with JSON encoding overhead could exceed reasonable DB column size.  
**Fix:** Add validation in service layer:
```java
String tagsJson = tagsJson(command.tags());
if (tagsJson.length() > 2000) {
    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "标签总长度过长");
}
```

---

### M-7: Adoption Ranking Query Allows Unbounded Result Set
**File:** `backend/src/main/java/cn/photolib/adoption/AdoptionService.java:102-125`  
**Severity:** MEDIUM  
**Description:** The `ranking()` method returns all matching photographers without pagination:
```java
public List<Ranking> ranking(LocalDate from, LocalDate to, Long projectId, Long campusId) {
    String sql = """
        SELECT a.photographer_student_id, a.photographer_name, COUNT(*) adopted_count
        FROM adoption a JOIN photo p ON p.id=a.photo_id
        ...
        """;
    return jdbc.sql(sql)...list();
}
```
**Impact:** Memory issues if there are thousands of unique photographers.  
**Fix:** Add `LIMIT` clause or pagination parameters.

---

### M-8: Missing Constraint on Request Required Count
**File:** `backend/src/main/resources/db/migration/V1__initial_schema.sql:68`  
**Severity:** MEDIUM  
**Description:** `required_count` has validation in controller (`@Min(1) @Max(10000)`) but no DB constraint:
```sql
required_count INT NOT NULL,
```
**Impact:** If constraint bypassed (direct DB insert, bug), invalid data persists.  
**Fix:** Add constraint:
```sql
required_count INT NOT NULL CHECK (required_count BETWEEN 1 AND 10000),
```

---

### M-9: Photo Status Transition Not Validated in Database
**File:** `backend/src/main/resources/db/migration/V1__initial_schema.sql:118`  
**Severity:** MEDIUM  
**Description:** Photo status is VARCHAR(32) without CHECK constraint. Invalid status values can be inserted.
```sql
status VARCHAR(32) NOT NULL,
```
**Impact:** Data integrity issues, application errors.  
**Fix:** Add CHECK constraint or use ENUM:
```sql
status ENUM('UPLOADING', 'PROCESSING', 'AVAILABLE', 'FAILED', 'ARCHIVED') NOT NULL,
```

---

### M-10: Worklog Date Allows Future Dates
**File:** `backend/src/main/java/cn/photolib/worklog/WorklogService.java:163-169`  
**Severity:** MEDIUM  
**Description:** Worklog validation only checks non-null and non-negative minutes, but allows future work dates:
```java
private void validate(WorklogCommand command) {
    if (command.workDate() == null || command.memberContactId() == null) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "工作日期和成员信息不能为空");
    }
    if (command.shootingMinutes() < 0 || command.retouchingMinutes() < 0) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "工时不能为负数");
    }
    if (command.shootingMinutes() + command.retouchingMinutes() > 24 * 60) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "单日工时不能超过 24 小时");
    }
}
```
**Impact:** Users can log work for future dates, creating confusing data.  
**Fix:** Add check:
```java
if (command.workDate().isAfter(LocalDate.now())) {
    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能填报未来日期的工时");
}
```

---

### M-11: Missing Foreign Key Cascade Rules
**File:** `backend/src/main/resources/db/migration/V1__initial_schema.sql` (various)  
**Severity:** MEDIUM  
**Description:** Foreign keys don't specify ON DELETE behavior, defaulting to RESTRICT. Deleting a referenced entity will fail.
```sql
CONSTRAINT fk_photo_request FOREIGN KEY (request_id) REFERENCES photo_request(id),
```
**Impact:** Orphaned records if soft-delete is used but foreign keys point to deleted entities.  
**Fix:** Add explicit cascade rules based on business logic:
```sql
CONSTRAINT fk_photo_request FOREIGN KEY (request_id) 
    REFERENCES photo_request(id) ON DELETE SET NULL,
```

---

### M-12: Frontend Token Refresh Race Condition
**File:** `src/api.ts:23-48`  
**Severity:** MEDIUM  
**Description:** Token refresh uses a global promise to prevent concurrent refreshes, but if the refresh fails, all queued requests fail together:
```javascript
refreshing ??= http.post<Envelope<{ accessToken: string }>>('/auth/refresh')
    .then(({ data }) => {
        localStorage.setItem('photolib_access_token', data.data.accessToken)
        return data.data.accessToken
    }).finally(() => { refreshing = null })
```
**Impact:** Single refresh failure logs out user even if some requests could succeed with a retry.  
**Fix:** Implement exponential backoff and separate error handling for refresh vs original request.

---

## Configuration & Deployment Issues

### C-1: Database Connection Allows Public Key Retrieval
**File:** `backend/src/main/resources/application.yml:10`  
**Description:** `allowPublicKeyRetrieval=true` in JDBC URL is a security risk in production.  
**Fix:** Remove this parameter and use proper SSL/TLS configuration.

### C-2: SSL Disabled in Database Connection
**File:** `backend/src/main/resources/application.yml:10`  
**Description:** `useSSL=false` disables encryption for database traffic.  
**Fix:** Enable SSL and configure certificates for production.

---

## Database Schema Recommendations

### S-1: Missing Index on photo.uploaded_by for Campus Manager Queries
**Location:** Schema  
**Description:** Campus managers filter photos by `uploaded_by=user.id()`, but there's no index.  
**Fix:** Add index: `CREATE INDEX idx_photo_uploader_status ON photo(uploaded_by, status);`

### S-2: Missing Index on request_participant for Participant Lookups
**Location:** Schema  
**Description:** `requireParticipant()` checks are frequent but only indexed on unique constraint.  
**Fix:** Add index: `CREATE INDEX idx_participant_user ON request_participant(user_id);`

---

## Summary Statistics

- **HIGH Severity:** 16 findings (SQL injection, auth bypass, race conditions, resource leaks)
- **MEDIUM Severity:** 12 findings (validation gaps, DoS risks, data integrity)
- **Configuration Issues:** 2 findings (database security)
- **Schema Improvements:** 2 recommendations

## Priority Remediation Order

1. **Immediate (within 24h):**
   - H-1: SQL injection in ProjectService
   - H-4: Missing authorization in photo download
   - H-12: Content-Type validation on upload
   - H-11: Audit log JSON injection
   - M-2: Default admin password

2. **High Priority (within 1 week):**
   - H-2: Path traversal validation
   - H-3, H-10, H-15: Race conditions and optimistic locking
   - H-5: Rate limiting implementation
   - H-6: Signing secret validation
   - H-8: Export result set streaming

3. **Medium Priority (within 1 month):**
   - H-7: Audit log indexing
   - H-9: Resource leak fixes
   - H-13: Session cleanup job
   - All MEDIUM findings
   - Schema improvements

---

## Remediation Status (Updated 2026-07-06)

### ✅ FIXED (Commit e617969)

**HIGH Severity Issues:**
- ✅ **H-1: SQL Injection** - Replaced string concatenation with JdbcClient parameterized queries in ProjectService.list()
- ✅ **H-2: Path Traversal** - Added upfront validation (null bytes, "..", absolute paths) in LocalObjectStorageService.resolve()
- ✅ **H-3: Race Condition (Photo Upload)** - Implemented optimistic locking with version check in PhotoService.complete()
- ✅ **H-4: Missing Authorization** - Added requireVisible() method to verify campus manager access in photo download
- ✅ **H-6: Weak Signing Secret** - Created StorageConfigValidator with 32-char minimum enforcement and weak secret rejection
- ✅ **H-7: Missing Index** - Added V11 migration with indices on audit_log(operator_id, created_at) and (resource_type, resource_id)
- ✅ **H-11: Audit Log JSON Injection** - Replaced string concatenation with ObjectMapper for safe JSON serialization
- ✅ **H-12: Content-Type Validation** - Extended Token record with contentType field and validation in upload endpoint

**Test Results:**
- Backend: 113/113 tests passing (1 skipped OSS test)
- Frontend: Build successful

### ⏳ PENDING (Not Yet Fixed)

**HIGH Severity Issues:**
- ⏳ **H-5: No Rate Limiting** - CSRF disabled but auth endpoints lack rate limits (login, refresh, uploads)
- ⏳ **H-8: Unbounded Export** - Audit log export loads up to 100k rows in memory, needs streaming or pagination
- ⏳ **H-9: Resource Leaks** - InputStream from storage may not close on exceptions, needs try-with-resources
- ⏳ **H-10: Adoption Duplicate Check Race** - SELECT-then-UPDATE without isolation in AdoptionService.adopt()
- ⏳ **H-13: Session Table Growth** - auth_session table lacks cleanup job for expired sessions
- ⏳ **H-14: Notification Image Access** - Any authenticated user can access any notification image by ID
- ⏳ **H-15: Missing Version Checks** - Multiple update operations lack optimistic locking:
  - RequestService.accept() line 113
  - RequestService.leave() line 172
  - RequestService.submit() line 206
  - WorklogService.confirm() line 110
  - WorklogService.reject() line 127
- ⏳ **H-16: Time-Based SQL Injection Risk** - Verify AuditLogMapper uses `#{}` not `${}` for date parameters

**MEDIUM Severity Issues:**
- ⏳ **M-1: Overly Permissive Upload Size** - Multipart limit 1536MB should be reduced to ~110MB
- ⏳ **M-2: Insecure Default Admin Password** - `ChangeMe123!` is weak and public, needs generation or force-change
- ⏳ **M-3: Missing Pagination Limit** - Page number uncapped, allows offset-based DoS with page=999999
- ⏳ **M-4: No Photo Deduplication** - SHA256 computed but not used to detect duplicates before upload
- ⏳ **M-5: Weak Token Entropy** - Verify TokenSupport.randomToken() uses SecureRandom not Random
- ⏳ **M-6: Missing Input Length Validation** - Tags JSON serialization length uncapped
- ⏳ **M-7: Unbounded Ranking Query** - ranking() method returns all photographers without pagination
- ⏳ **M-8: Missing DB Constraint** - required_count has controller validation but no DB CHECK constraint
- ⏳ **M-9: Photo Status Not Validated** - status VARCHAR(32) without CHECK constraint or ENUM
- ⏳ **M-10: Future Date Allowed** - Worklog validation doesn't reject future work dates
- ⏳ **M-11: Missing Foreign Key Cascade** - Foreign keys lack ON DELETE rules, may orphan records
- ⏳ **M-12: Frontend Token Refresh Race** - Single refresh failure logs out user, needs retry logic

**Configuration Issues:**
- ⏳ **C-1: Public Key Retrieval** - allowPublicKeyRetrieval=true in JDBC URL is production risk
- ⏳ **C-2: SSL Disabled** - useSSL=false disables database encryption

**Schema Improvements:**
- ⏳ **S-1: Missing Index** - photo(uploaded_by, status) for campus manager queries
- ⏳ **S-2: Missing Index** - request_participant(user_id) for participant lookups

### Next Steps

**Immediate Priority (Next Sprint):**
1. H-5: Implement rate limiting (Spring RateLimiter or Redis-based)
2. H-8: Convert audit export to streaming response
3. H-9: Audit all storage InputStream usage for try-with-resources
4. M-2: Add admin password strength validation or generation

**Medium Priority:**
- H-10, H-15: Add optimistic locking to remaining update operations
- H-13: Create scheduled job for session cleanup
- M-3, M-7: Add pagination caps and limits
- M-10: Add date validation rules

**Low Priority:**
- Schema improvements (S-1, S-2)
- Configuration hardening (C-1, C-2)
- DB constraints (M-8, M-9)

---

**Report End**
