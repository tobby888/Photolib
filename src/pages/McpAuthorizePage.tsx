import { Alert, App, Button, Card, Descriptions, Input, Result, Space, Typography } from 'antd'
import { CheckCircleOutlined, CloseCircleOutlined, ApiOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { DataState, PageTitle } from '../components'
import { useLoad } from '../hooks'
import type { McpAuthorizationRequest } from '../types'

/**
 * MCP 客户端的批准页。
 *
 * <p>成员在自己机器上启动 MCP 服务后，终端里会打印一条链接和一串配对码；链接落到这一页，
 * 配对码要在这里手敲一遍。**这一步不是形式**：光凭链接就能批准的话，把链接发给别人、
 * 由别人替自己点一下，就等于把一个以他身份说话的令牌交了出去。配对码只出现在发起配对
 * 的那个终端上，所以"手上有码"约等于"这台机器就是我自己的"。后端同样只存配对码的哈希，
 * 这一页拿不到正确答案，猜错 5 次该配对直接作废。
 *
 * <p>这一页挂在工作台外壳里，所以未登录会被外壳弹去登录页并在登录后带着 query 回来
 * （见 `src/App.tsx` 与 `LoginPage`）——批准必须以一个真实的登录会话做出。
 */
/**
 * 后端回的是不带时区的本地时刻（`2026-09-19T16:55:51.546791`）。这里只截到分钟：
 * 要回答的是"这是不是我刚刚发起的那一条"，微秒帮不上忙，反而把这一行撑得难读。
 */
function localTime(value?: string | null) {
  if (!value) return '未知'
  return value.replace('T', ' ').slice(0, 16)
}

export default function McpAuthorizePage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const requestId = params.get('request') ?? ''
  const [userCode, setUserCode] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [outcome, setOutcome] = useState<'APPROVED' | 'DENIED' | null>(null)

  const { data: request, loading, error, reload } = useLoad(
    () => requestId
      ? api<McpAuthorizationRequest>({ url: `/auth/mcp/authorizations/${encodeURIComponent(requestId)}` })
      : Promise.resolve(null),
    null as McpAuthorizationRequest | null,
    [requestId],
  )

  const approve = async () => {
    setSubmitting(true)
    try {
      await api({
        method: 'POST',
        url: `/auth/mcp/authorizations/${encodeURIComponent(requestId)}/approve`,
        data: { userCode },
      })
      setOutcome('APPROVED')
    } catch (e) {
      message.error((e as Error).message)
      // 猜错一次就少一次机会，把剩余次数重新取回来显示，别让人以为可以一直试。
      await reload()
    } finally {
      setSubmitting(false)
    }
  }

  const deny = async () => {
    setSubmitting(true)
    try {
      await api({
        method: 'POST',
        url: `/auth/mcp/authorizations/${encodeURIComponent(requestId)}/deny`,
      })
      setOutcome('DENIED')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  if (!requestId) return <Result status="404" title="链接不完整"
    subTitle="这条地址里没有配对号。请回到终端，复制客户端打印的完整链接。"
    extra={<Button type="primary" onClick={() => navigate('/')}>返回工作台</Button>} />

  if (outcome === 'APPROVED') return <Result status="success" title="已批准"
    subTitle="可以回到终端了，客户端会在几秒内拿到授权。之后它以你的身份操作，权限和你在网页上完全一致。"
    extra={<Button type="primary" onClick={() => navigate('/')}>返回工作台</Button>} />

  if (outcome === 'DENIED') return <Result status="info" title="已拒绝"
    subTitle="客户端不会拿到任何授权。如果这次配对不是你发起的，建议顺手改一次密码。"
    extra={<Button type="primary" onClick={() => navigate('/')}>返回工作台</Button>} />

  return <>
    <PageTitle eyebrow="MCP 客户端" title="批准客户端登录"
      description="把 AI 客户端接入本系统。批准之后它以你的身份调用接口，能做的事和你在网页上完全一致。" />
    <DataState loading={loading} error={error} onRetry={reload}>
      {!request ? null : request.status !== 'PENDING'
        ? <Result status="warning" title="这条配对请求已经不能批准了"
          subTitle={request.status === 'APPROVED' ? '它已经被批准过了。'
            : request.status === 'CONSUMED' ? '客户端已经完成登录。'
              : '它已被拒绝，或者已经超过 10 分钟的有效期。请在客户端重新发起。'}
          extra={<Button type="primary" onClick={() => navigate('/')}>返回工作台</Button>} />
        : <Card className="mcp-authorize-card">
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Alert type="warning" showIcon
              message="只批准你自己刚刚发起的配对"
              description="如果这条链接是别人发给你的，请直接拒绝：批准等于把一个能以你身份操作的令牌交给对方。" />
            <Descriptions column={1} size="small" bordered items={[
              { key: 'client', label: '客户端', children: <><ApiOutlined /> {request.clientName}</> },
              { key: 'device', label: '设备', children: request.deviceLabel || '未提供' },
              { key: 'ip', label: '发起地址', children: request.requestedIp || '未知' },
              { key: 'created', label: '发起时间', children: localTime(request.createdAt) },
              { key: 'expires', label: '有效期至', children: localTime(request.expiresAt) },
            ]} />
            <div>
              <Typography.Paragraph>
                请输入终端里显示的配对码（形如 <Typography.Text code>ABCD-EFGH</Typography.Text>）。
                还可以尝试 {request.remainingAttempts} 次。
              </Typography.Paragraph>
              <Input size="large" allowClear value={userCode} maxLength={16}
                placeholder="ABCD-EFGH" autoComplete="off"
                onChange={event => setUserCode(event.target.value)}
                onPressEnter={() => { if (userCode.trim()) void approve() }} />
            </div>
            <Space wrap>
              <Button type="primary" size="large" icon={<CheckCircleOutlined />}
                loading={submitting} disabled={!userCode.trim()}
                onClick={approve}>批准并登录</Button>
              <Button danger size="large" icon={<CloseCircleOutlined />}
                loading={submitting} onClick={deny}>拒绝</Button>
            </Space>
          </Space>
        </Card>}
    </DataState>
  </>
}
