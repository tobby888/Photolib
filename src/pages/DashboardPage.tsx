import { Button, Card, Col, List, Progress, Row, Space, Statistic, Tag, Typography } from 'antd'
import {
  ArrowRightOutlined, CameraOutlined, CheckCircleOutlined, ClockCircleOutlined,
  FolderOutlined, PlusOutlined, RiseOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { useAuth } from '../auth'
import { api, emptyPage } from '../api'
import type { PageData, PhotoRequest, Project } from '../types'
import { DataState, StatusTag, roleName } from '../components'
import { useLoad } from '../hooks'

export default function DashboardPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const { data, loading, error, reload } = useLoad(async () => {
    const [projects, requests] = await Promise.all([
      api<PageData<Project>>({ url: '/projects', params: { page: 1, pageSize: 8 } }),
      api<PageData<PhotoRequest>>({ url: '/requests', params: { page: 1, pageSize: 6 } }),
    ])
    return { projects, requests }
  }, { projects: emptyPage<Project>(), requests: emptyPage<PhotoRequest>() }, [])
  const active = data.projects.items.filter((item) => item.status === 'ACTIVE').length
  const pending = data.requests.items.filter((item) => ['PUBLISHED', 'ACCEPTED'].includes(item.status)).length

  return <>
    <section className="welcome-panel">
      <div>
        <Typography.Text className="eyebrow">{dayjs().format('YYYY 年 M 月 D 日 · dddd')}</Typography.Text>
        <Typography.Title level={1}>早上好，{user?.displayName}</Typography.Title>
        <Typography.Paragraph>{roleName[user?.role || '']}，今天也一起把好照片送到需要它的地方。</Typography.Paragraph>
      </div>
      <Space wrap>
        {user?.role !== 'CAMPUS_MANAGER' && <Button size="large" icon={<PlusOutlined />} onClick={() => navigate('/projects')}>新建项目</Button>}
        <Button type="primary" size="large" icon={<CameraOutlined />} onClick={() => navigate('/photos')}>上传图片</Button>
      </Space>
    </section>
    <DataState loading={loading} error={error} onRetry={reload}>
      <Row gutter={[16, 16]} className="metric-grid">
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="进行中的项目" value={active} prefix={<FolderOutlined />} /><span className="metric-note"><RiseOutlined /> 全部 {data.projects.total} 个项目</span></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="待处理需求" value={pending} prefix={<ClockCircleOutlined />} /><span className="metric-note amber">需要持续跟进</span></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="本页需求总量" value={data.requests.total} prefix={<CameraOutlined />} /><span className="metric-note">覆盖拍摄协作全程</span></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="已完成需求" value={data.requests.items.filter(i => i.status === 'COMPLETED').length} prefix={<CheckCircleOutlined />} /><span className="metric-note">稳稳收进图库</span></Card></Col>
      </Row>
      <Row gutter={[16, 16]} className="dashboard-row">
        <Col xs={24} xl={15}>
          <Card title="近期图片需求" extra={<Button type="link" onClick={() => navigate('/requests')}>查看全部 <ArrowRightOutlined /></Button>}>
            <List dataSource={data.requests.items.slice(0, 5)} locale={{ emptyText: '暂无图片需求' }}
              renderItem={(item) => <List.Item className="task-item" onClick={() => navigate('/requests')}>
                <div className="task-date"><strong>{dayjs(item.deadline).format('DD')}</strong><span>{dayjs(item.deadline).format('MMM')}</span></div>
                <div className="task-main"><Space><Typography.Text strong>{item.title}</Typography.Text><StatusTag value={item.status} /></Space>
                  <Typography.Text type="secondary">项目 #{item.projectId} · 需要 {item.requiredCount} 张 · {dayjs(item.deadline).format('M 月 D 日 HH:mm')} 截止</Typography.Text>
                </div>
                <Progress type="circle" size={42} percent={item.status === 'COMPLETED' ? 100 : item.status === 'SUBMITTED' ? 80 : item.status === 'ACCEPTED' ? 45 : 15} strokeColor="#28594f" />
              </List.Item>} />
          </Card>
        </Col>
        <Col xs={24} xl={9}>
          <Card title="项目动态">
            <List dataSource={data.projects.items.slice(0, 5)} locale={{ emptyText: '暂无项目' }}
              renderItem={(item) => <List.Item>
                <List.Item.Meta avatar={<div className="project-bullet"><FolderOutlined /></div>}
                  title={<Space><span>{item.title}</span><StatusTag value={item.status} /></Space>}
                  description={item.description || '暂未填写项目说明'} />
              </List.Item>} />
          </Card>
          <Card className="tip-card" variant="borderless">
            <Tag variant="filled">今日提示</Tag>
            <Typography.Title level={4}>让元数据完整一点</Typography.Title>
            <Typography.Paragraph>准确填写拍摄者学号、姓名和拍摄时间，会让后续检索与贡献统计更可靠。</Typography.Paragraph>
          </Card>
        </Col>
      </Row>
    </DataState>
  </>
}
