package cn.photolib.settlement;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 清算单测试的造数和清理。非事务的用例（后台线程发信，要看得见已提交的数据）必须调 {@link #cleanup()}：
 * H2 内存库整轮共用，留下的启用账号会让别的测试类的收件人数对不上。
 *
 * <p>清算单按所属选题的结束时间取工时和被引，所以默认的「清算选题」是已完成的，结束于
 * {@link #PROJECT_COMPLETED_AT}（落在各测试用的 2026 年 10 月清算期内）。</p>
 */
final class SettlementFixtures {
    static final LocalDateTime PROJECT_COMPLETED_AT = LocalDateTime.of(2026, 10, 25, 12, 0);
    final JdbcClient jdbc;
    final long base;
    final long campusId;
    final long projectId;
    final long requestId;
    final long ownerId;
    private final List<Long> users = new ArrayList<>();
    private final List<Long> worklogs = new ArrayList<>();
    private final List<Long> photos = new ArrayList<>();
    private final List<Long> adoptions = new ArrayList<>();
    private final List<Long> projects = new ArrayList<>();
    private final List<Long> requests = new ArrayList<>();
    private final List<Long> shareLinks = new ArrayList<>();
    private long sequence = 100;

    SettlementFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.base = (System.nanoTime() & 0xFFFFFFFFFFL) * 1000;
        this.campusId = base + 1;
        this.projectId = base + 2;
        this.requestId = base + 3;
        this.ownerId = base + 4;
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '清算校区')")
                .param("id", campusId).param("code", "SETTLE-" + base).update();
        user(ownerId, "清算测试负责人" + base, null);
        jdbc.sql("""
                INSERT INTO project(id, title, status, created_by, completed_at)
                VALUES (:id, '清算选题', 'COMPLETED', :userId, :completedAt)
                """).param("id", projectId).param("userId", ownerId).param("completedAt", PROJECT_COMPLETED_AT).update();
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '清算需求', :campusId, :deadline, 'ACCEPTED', :userId)
                """).param("id", requestId).param("projectId", projectId).param("campusId", campusId)
                .param("deadline", LocalDateTime.now().plusDays(3)).param("userId", ownerId).update();
    }

    long user(String name, String email) {
        long id = base + sequence++;
        user(id, name, email);
        return id;
    }

    private void user(long id, String name, String email) {
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password,
                                     email, campus_id)
                VALUES (:id, :username, 'hash', :name, 'CAMPUS_MANAGER', TRUE, FALSE, :email, :campusId)
                """).param("id", id).param("username", "settle-" + id).param("name", name)
                .param("email", email).param("campusId", campusId).update();
        users.add(id);
    }

    long worklog(String memberName, LocalDate workDate, int shooting, int retouching, String status, String rejectReason) {
        return worklog(requestId, null, memberName, workDate, shooting, retouching, status, rejectReason);
    }

    /** 挂在 {@code request}（需求）或 {@code shareLink}（上传链接申报）下的一条工时，两者给一个。 */
    long worklog(Long request, Long shareLink, String memberName, LocalDate workDate, int shooting, int retouching,
                 String status, String rejectReason) {
        long id = base + sequence++;
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, share_link_id, user_id, work_date, shooting_minutes,
                                    retouching_minutes, member_name, member_student_id, status, reject_reason, remark)
                VALUES (:id, :requestId, :shareLinkId, :userId, :workDate, :shooting, :retouching, :name, 'S-SETTLE',
                        :status, :reason, '现场拍摄')
                """).param("id", id).param("requestId", request).param("shareLinkId", shareLink).param("userId", ownerId)
                .param("workDate", workDate).param("shooting", shooting).param("retouching", retouching)
                .param("name", memberName).param("status", status).param("reason", rejectReason).update();
        worklogs.add(id);
        return id;
    }

    void reviewEvent(long worklogId, String action, String reason, LocalDateTime at) {
        jdbc.sql("""
                INSERT INTO worklog_review_log(worklog_id, action, reason, actor_id, backfilled, created_at)
                VALUES (:worklogId, :action, :reason, :actor, FALSE, :at)
                """).param("worklogId", worklogId).param("action", action).param("reason", reason)
                .param("actor", ownerId).param("at", at).update();
    }

    /** 进行中的选题。 */
    long project(String title) {
        return project(title, "ACTIVE", null);
    }

    long project(String title, String status, LocalDateTime completedAt) {
        long id = base + sequence++;
        jdbc.sql("""
                INSERT INTO project(id, title, status, created_by, completed_at)
                VALUES (:id, :title, :status, :userId, :completedAt)
                """).param("id", id).param("title", title).param("status", status).param("userId", ownerId)
                .param("completedAt", completedAt).update();
        projects.add(id);
        return id;
    }

    /** {@code project} 下的一条需求（与默认需求同校区）。 */
    long request(long project) {
        long id = base + sequence++;
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '清算需求', :campusId, :deadline, 'ACCEPTED', :userId)
                """).param("id", id).param("projectId", project).param("campusId", campusId)
                .param("deadline", LocalDateTime.now().plusDays(3)).param("userId", ownerId).update();
        requests.add(id);
        return id;
    }

    /** {@code project} 的一条上传链接，开链接的是 {@code createdBy}。 */
    long shareLink(long project, String name, long createdBy) {
        long id = base + sequence++;
        jdbc.sql("""
                INSERT INTO project_share_link(id, token, project_id, name, password_hash, created_by, purpose)
                VALUES (:id, :token, :projectId, :name, 'hash', :userId, 'UPLOAD')
                """).param("id", id).param("token", String.format("%026d", id)).param("projectId", project)
                .param("name", name).param("userId", createdBy).update();
        shareLinks.add(id);
        return id;
    }

    /**
     * 一张拍摄者为 {@code photographerName}、在 {@code uploadedAt} 传进 {@code project} 的图片。
     * {@code viaRequest} 为真时只挂需求（老数据的样子，project_id 为空，选题从需求上取）。
     */
    long photo(String photographerName, long project, LocalDateTime uploadedAt, String status, boolean viaRequest) {
        return photo(photographerName, viaRequest ? null : project, viaRequest ? requestId : null, uploadedAt, status,
                ownerId, null);
    }

    /**
     * 一张图片，上传账号记 {@code uploadedBy}，通过上传链接传的给 {@code shareLink}。
     * {@code project} 与 {@code request} 至少给一个；只给需求时选题从需求上取。
     */
    long photo(String photographerName, Long project, Long request, LocalDateTime uploadedAt, String status,
               long uploadedBy, Long shareLink) {
        long photoId = base + sequence++;
        jdbc.sql("""
                INSERT INTO photo(id, request_id, project_id, title, photographer_student_id, photographer_name,
                                  uploaded_by, share_link_id, campus_id, taken_at, size, content_type, object_key,
                                  stored_file_name, sha256, status, created_at)
                VALUES (:id, :requestId, :projectId, '清算图片', 'S-SETTLE', :name, :userId, :shareLinkId, :campusId,
                        :takenAt, 100, 'image/jpeg', :objectKey, 'settle.jpg', :sha256, :status, :createdAt)
                """).param("id", photoId).param("requestId", request).param("projectId", project)
                .param("shareLinkId", shareLink)
                .param("name", photographerName).param("userId", uploadedBy).param("campusId", campusId)
                .param("takenAt", uploadedAt.minusHours(2)).param("objectKey", "settle-" + photoId + ".jpg")
                .param("sha256", String.format("%064x", photoId)).param("status", status)
                .param("createdAt", uploadedAt).update();
        photos.add(photoId);
        return photoId;
    }

    /** 被引的图片在被引前一天传进本选题，所以它同时也算一次「参与选题」。 */
    long adoption(String photographerName, LocalDateTime adoptedAt) {
        return adoption(photographerName, projectId, adoptedAt);
    }

    long adoption(String photographerName, long project, LocalDateTime adoptedAt) {
        long photoId = photo(photographerName, project, adoptedAt.minusDays(1), "AVAILABLE", false);
        long adoptionId = base + sequence++;
        jdbc.sql("""
                INSERT INTO adoption(id, project_id, photo_id, photographer_student_id, photographer_name,
                                     adopted_by, adopted_at)
                VALUES (:id, :projectId, :photoId, 'S-SETTLE', :name, :userId, :adoptedAt)
                """).param("id", adoptionId).param("projectId", project).param("photoId", photoId)
                .param("name", photographerName).param("userId", ownerId).param("adoptedAt", adoptedAt).update();
        adoptions.add(adoptionId);
        return photoId;
    }

    void cleanup() {
        jdbc.sql("""
                DELETE FROM settlement_statement WHERE batch_id IN
                    (SELECT id FROM settlement_mail_batch WHERE created_by IN (:users))
                """).param("users", users).update();
        jdbc.sql("""
                DELETE FROM settlement_mail_delivery WHERE batch_id IN
                    (SELECT id FROM settlement_mail_batch WHERE created_by IN (:users))
                """).param("users", users).update();
        jdbc.sql("DELETE FROM settlement_mail_batch WHERE created_by IN (:users)").param("users", users).update();
        jdbc.sql("DELETE FROM user_notification WHERE user_id IN (:users)").param("users", users).update();
        if (!worklogs.isEmpty()) {
            jdbc.sql("DELETE FROM worklog_review_log WHERE worklog_id IN (:ids)").param("ids", worklogs).update();
            jdbc.sql("DELETE FROM worklog WHERE id IN (:ids)").param("ids", worklogs).update();
        }
        if (!adoptions.isEmpty()) jdbc.sql("DELETE FROM adoption WHERE id IN (:ids)").param("ids", adoptions).update();
        if (!photos.isEmpty()) jdbc.sql("DELETE FROM photo WHERE id IN (:ids)").param("ids", photos).update();
        if (!shareLinks.isEmpty()) {
            jdbc.sql("DELETE FROM project_share_link WHERE id IN (:ids)").param("ids", shareLinks).update();
        }
        if (!requests.isEmpty()) jdbc.sql("DELETE FROM photo_request WHERE id IN (:ids)").param("ids", requests).update();
        jdbc.sql("DELETE FROM photo_request WHERE id=:id").param("id", requestId).update();
        jdbc.sql("DELETE FROM project WHERE id=:id").param("id", projectId).update();
        if (!projects.isEmpty()) jdbc.sql("DELETE FROM project WHERE id IN (:ids)").param("ids", projects).update();
        jdbc.sql("DELETE FROM app_user WHERE id IN (:users)").param("users", users).update();
        jdbc.sql("DELETE FROM campus WHERE id=:id").param("id", campusId).update();
    }
}
