-- 清算单除了邮件，也可以发站内信（发送人可以只发一种，也可以两种都发）。
--
-- 1. 批次记下这次发了哪几种：send_email / send_inbox。V71 的批次全是邮件。
-- 2. 投递行按渠道各记一个结果：email_status / inbox_status，NULL 表示这次没发这一种。
--    status 仍是整行的状态（认领用的 PENDING→SENDING 条件更新照旧），所选渠道全部成功才算 SENT，
--    有一种失败就是 FAILED，「重发失败的」据此只重发失败的那一种，不会给已经收到的人再发一遍。
--    只发站内信时收件人可以没有邮箱，所以 recipient_email 改为可空。
-- 3. settlement_statement：站内信里的清算单是发送那一刻的快照（之后工时再被审核也不变），
--    收件人在消息里打开、下载 PDF 看到的都是这一份。正文里的品牌图标存成 cid:brand-logo，
--    取的时候再换成当前图标，免得每份快照各存一遍图片。

ALTER TABLE settlement_mail_batch ADD COLUMN send_email BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE settlement_mail_batch ADD COLUMN send_inbox BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE settlement_mail_delivery MODIFY COLUMN recipient_email VARCHAR(255) NULL;
-- PENDING / SENT / FAILED / SKIPPED（两种都发、但这个人没有邮箱）；NULL = 这次没发邮件
ALTER TABLE settlement_mail_delivery ADD COLUMN email_status VARCHAR(16) NULL;
-- PENDING / SENT / FAILED；NULL = 这次没发站内信
ALTER TABLE settlement_mail_delivery ADD COLUMN inbox_status VARCHAR(16) NULL;
ALTER TABLE settlement_mail_delivery ADD COLUMN statement_id BIGINT NULL;

UPDATE settlement_mail_delivery SET email_status = CASE
    WHEN status IN ('SENT', 'FAILED') THEN status
    WHEN status = 'SENDING' THEN 'FAILED'
    ELSE 'PENDING' END;

CREATE TABLE settlement_statement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    recipient_name VARCHAR(100) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    subject VARCHAR(200) NOT NULL,
    html_body MEDIUMTEXT NOT NULL,
    created_by_name VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_settlement_statement_batch FOREIGN KEY (batch_id) REFERENCES settlement_mail_batch(id)
);
CREATE INDEX idx_settlement_statement_user ON settlement_statement(user_id, created_at);
