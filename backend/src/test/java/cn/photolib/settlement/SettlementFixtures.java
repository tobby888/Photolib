package cn.photolib.settlement;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 清算单测试的造数和清理。非事务的用例（后台线程发信，要看得见已提交的数据）必须调 {@link #cleanup()}：
 * H2 内存库整轮共用，留下的启用账号会让别的测试类的收件人数对不上。
 */
final class SettlementFixtures {
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
                VALUES (:id, '清算选题', 'ACTIVE', :userId, NULL)
                """).param("id", projectId).param("userId", ownerId).update();
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
        long id = base + sequence++;
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, user_id, work_date, shooting_minutes, retouching_minutes,
                                    member_name, member_student_id, status, reject_reason, remark)
                VALUES (:id, :requestId, :userId, :workDate, :shooting, :retouching, :name, 'S-SETTLE', :status,
                        :reason, '现场拍摄')
                """).param("id", id).param("requestId", requestId).param("userId", ownerId)
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

    long adoption(String photographerName, LocalDateTime adoptedAt) {
        long photoId = base + sequence++;
        jdbc.sql("""
                INSERT INTO photo(id, request_id, project_id, title, photographer_student_id, photographer_name,
                                  uploaded_by, campus_id, taken_at, size, content_type, object_key,
                                  stored_file_name, sha256, status)
                VALUES (:id, :requestId, :projectId, '清算图片', 'S-SETTLE', :name, :userId, :campusId, :takenAt,
                        100, 'image/jpeg', :objectKey, 'settle.jpg', :sha256, 'AVAILABLE')
                """).param("id", photoId).param("requestId", requestId).param("projectId", projectId)
                .param("name", photographerName).param("userId", ownerId).param("campusId", campusId)
                .param("takenAt", adoptedAt.minusDays(1)).param("objectKey", "settle-" + photoId + ".jpg")
                .param("sha256", String.format("%064x", photoId)).update();
        photos.add(photoId);
        long adoptionId = base + sequence++;
        jdbc.sql("""
                INSERT INTO adoption(id, project_id, photo_id, photographer_student_id, photographer_name,
                                     adopted_by, adopted_at)
                VALUES (:id, :projectId, :photoId, 'S-SETTLE', :name, :userId, :adoptedAt)
                """).param("id", adoptionId).param("projectId", projectId).param("photoId", photoId)
                .param("name", photographerName).param("userId", ownerId).param("adoptedAt", adoptedAt).update();
        adoptions.add(adoptionId);
        return photoId;
    }

    void cleanup() {
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
        jdbc.sql("DELETE FROM photo_request WHERE id=:id").param("id", requestId).update();
        jdbc.sql("DELETE FROM project WHERE id=:id").param("id", projectId).update();
        jdbc.sql("DELETE FROM app_user WHERE id IN (:users)").param("users", users).update();
        jdbc.sql("DELETE FROM campus WHERE id=:id").param("id", campusId).update();
    }
}
