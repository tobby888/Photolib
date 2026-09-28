-- 选题结束时名下需求要随之结束（ProjectService.endOpenRequests）。在那之前结束的选题
-- 名下还挂着没结束的需求，而这些选题已经没法再走一次状态流转（取消的不能重开，
-- 完成的不能再完成一次），这里按同一套规则把存量一次性补齐：
--   * 已完成选题：有人接手的（ACCEPTED / SUBMITTED）按完成处理，完成时间取选题的完成时间；
--   * 已完成选题：没人接过的（DRAFT / PUBLISHED）取消并写明原因；
--   * 已取消选题：全部未结束需求取消并写明原因。
-- 先跑"完成"那一句，否则 ACCEPTED / SUBMITTED 会被后面的取消语句先吃掉。

UPDATE photo_request
SET status = 'COMPLETED',
    completed_at = COALESCE(
        (SELECT p.completed_at FROM project p WHERE p.id = photo_request.project_id),
        CURRENT_TIMESTAMP(6)),
    return_reason = NULL,
    returned_by = NULL,
    returned_at = NULL,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE deleted = FALSE
  AND status IN ('ACCEPTED', 'SUBMITTED')
  AND project_id IN (SELECT id FROM project WHERE status = 'COMPLETED');

UPDATE photo_request
SET status = 'CANCELLED',
    cancel_reason = '所属选题已完成，需求自动结束',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE deleted = FALSE
  AND status IN ('DRAFT', 'PUBLISHED')
  AND project_id IN (SELECT id FROM project WHERE status = 'COMPLETED');

UPDATE photo_request
SET status = 'CANCELLED',
    cancel_reason = '所属选题已取消，需求自动结束',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE deleted = FALSE
  AND status IN ('DRAFT', 'PUBLISHED', 'ACCEPTED', 'SUBMITTED')
  AND project_id IN (SELECT id FROM project WHERE status = 'CANCELLED');
