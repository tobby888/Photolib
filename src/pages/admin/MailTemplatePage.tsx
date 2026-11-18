import { Segmented, Space, Tabs } from 'antd'
import { useState } from 'react'
import NotificationMailEditor from './NotificationMailEditor'
import SettlementMailContentPanel from './SettlementMailContentPanel'

const submission = 'WORKLOG_SUBMITTED'
const approval = 'WORKLOG_APPROVED'
const settlement = 'SETTLEMENT'

export default function MailTemplatePage() {
  const [kind, setKind] = useState<typeof submission | typeof approval | typeof settlement>(submission)
  const [layoutRevision, setLayoutRevision] = useState(0)

  return <Tabs items={[
    { key: 'layout', label: '公共模板', children: <NotificationMailEditor onLayoutSaved={() => setLayoutRevision(revision => revision + 1)} /> },
    { key: 'content', label: '邮件内容', children: <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Segmented aria-label="邮件类型" value={kind} onChange={setKind} options={[
        { value: submission, label: '工时申报成功' },
        { value: approval, label: '工时审核通过' },
        { value: settlement, label: '清算单' },
      ]} />
      {kind === settlement ? <SettlementMailContentPanel layoutRevision={layoutRevision} />
        : <NotificationMailEditor key={kind} kind={kind} layoutRevision={layoutRevision} />}
    </Space> },
  ]} />
}
