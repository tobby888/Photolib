-- 答卷在问卷截止前可以修改。submitted_at 保持第一次提交的时间——报名、抢名额一类的问卷靠它
-- 排先后，不能因为改了一个错别字就把人挪到队尾——最后一次修改的时间另记一列。
-- 从没改过的答卷为 NULL。
ALTER TABLE survey_response ADD COLUMN modified_at DATETIME(6) NULL;
