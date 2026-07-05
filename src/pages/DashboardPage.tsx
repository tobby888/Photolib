import { Button, Card, Col, Image, List, Progress, Row, Space, Statistic, Typography } from 'antd'
import {
  ArrowRightOutlined, CameraOutlined, CheckCircleOutlined, ClockCircleOutlined,
  FolderOutlined, HeartOutlined, PlusOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { useAuth } from '../auth'
import { api, emptyPage } from '../api'
import type { PageData, Photo, PhotoRequest, Project } from '../types'
import { DataState, StatusTag, roleName } from '../components'
import { useLoad } from '../hooks'

const requestProgress: Record<PhotoRequest['status'], number> = {
  DRAFT: 10, PUBLISHED: 20, ACCEPTED: 45, SUBMITTED: 80, COMPLETED: 100, CANCELLED: 0,
}

export default function DashboardPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const { data, loading, error, reload } = useLoad(async () => {
    const [projects, requests, photos] = await Promise.all([
      api<PageData<Project>>({ url: '/projects', params: { page: 1, pageSize: 8 } }).catch(() => emptyPage<Project>()),
      api<PageData<PhotoRequest>>({ url: '/requests', params: { page: 1, pageSize: 8 } }).catch(() => emptyPage<PhotoRequest>()),
      api<PageData<Photo>>({ url: '/photos', params: { page: 1, pageSize: 8, status: 'AVAILABLE' } }).catch(() => emptyPage<Photo>()),
    ])
    return { projects, requests, photos }
  }, {
    projects: emptyPage<Project>(), requests: emptyPage<PhotoRequest>(), photos: emptyPage<Photo>(),
  }, [])

  const active = data.projects.items.filter((item) => item.status === 'ACTIVE').length
  const pending = data.requests.items.filter((item) => ['PUBLISHED', 'ACCEPTED'].includes(item.status)).length
  const completed = data.requests.items.filter((item) => item.status === 'COMPLETED').length
  const nearDeadlines = data.requests.items
    .filter((item) => item.status !== 'COMPLETED' && item.status !== 'CANCELLED')
    .sort((a, b) => dayjs(a.deadline).valueOf() - dayjs(b.deadline).valueOf())
    .slice(0, 4)

  return <>
    <section className="dashboard-header">
      <div>
        <Typography.Text className="eyebrow">{dayjs().format('YYYY 年 M 月 D 日 · dddd')}</Typography.Text>
        <Typography.Title level={1}>早上好，{user?.displayName}</Typography.Title>
        <Typography.Paragraph>{roleName[user?.role || '']}，今天也一起把好照片送到需要它的地方。</Typography.Paragraph>
      </div>
      <Space wrap>
        {user?.role !== 'CAMPUS_MANAGER' &&
          <Button size="large" icon={<PlusOutlined />} onClick={() => navigate('/projects')}>新建项目</Button>}
        <Button type="primary" size="large" icon={<CameraOutlined />} onClick={() => navigate('/photos')}>上传图片</Button>
      </Space>
    </section>

    <DataState loading={loading} error={error} onRetry={reload}>
      <Row className="studio-metrics">
        <Col xs={12} md={8} xl={4}><Statistic title="进行中项目" value={active} prefix={<FolderOutlined />} suffix={<small>个</small>} /></Col>
        <Col xs={12} md={8} xl={5}><Statistic title="待处理需求" value={pending} prefix={<CheckCircleOutlined />} suffix={<small>项</small>} /></Col>
        <Col xs={12} md={8} xl={5}><Statistic title="图库资产" value={data.photos.total} prefix={<CameraOutlined />} suffix={<small>张</small>} /></Col>
        <Col xs={12} md={8} xl={5}><Statistic title="近期交付" value={completed} prefix={<ClockCircleOutlined />} suffix={<small>项</small>} /></Col>
        <Col xs={24} md={16} xl={5}><Statistic title="项目健康度" value={active ? '良好' : '平稳'} prefix={<HeartOutlined />} /></Col>
      </Row>

      <Row gutter={[18, 18]} className="gallery-dashboard">
        <Col xs={24} xl={16}>
          <Card className="recent-gallery" title="最近入库"
            extra={<Button type="link" onClick={() => navigate('/photos')}>查看全部 <ArrowRightOutlined /></Button>}>
            {data.photos.items.length ? <div className="contact-sheet">
              {data.photos.items.slice(0, 8).map((photo) =>
                <button key={photo.id} className="contact-frame" onClick={() => navigate('/photos')} aria-label={`查看图片：${photo.title}`}>
                  {photo.thumbnailUrl
                    ? <Image preview={false} src={photo.thumbnailUrl} alt={photo.title} />
                    : <div className="contact-placeholder"><CameraOutlined /><span>{photo.title || '未命名图片'}</span></div>}
                  <span className="contact-caption">{photo.title || '未命名图片'}</span>
                </button>)}
            </div> : <div className="gallery-empty">
              <CameraOutlined />
              <strong>图库等待第一组作品</strong>
              <span>上传图片后，最新素材会在这里形成工作联系表。</span>
              <Button type="primary" onClick={() => navigate('/photos')}>前往图片库</Button>
            </div>}
          </Card>

          <Card className="work-queue" title="我的待办"
            extra={<Button type="link" onClick={() => navigate('/requests')}>查看全部 <ArrowRightOutlined /></Button>}>
            <List dataSource={data.requests.items.slice(0, 5)} locale={{ emptyText: '暂无待处理任务' }}
              renderItem={(item) => <List.Item className="queue-item" onClick={() => navigate('/requests')}>
                <div className="queue-type"><CameraOutlined /></div>
                <div className="queue-copy">
                  <Space wrap><Typography.Text strong>{item.title}</Typography.Text><StatusTag value={item.status} /></Space>
                  <Typography.Text type="secondary">需要 {item.requiredCount} 张 · {dayjs(item.deadline).format('M 月 D 日 HH:mm')} 截止</Typography.Text>
                </div>
                <Progress percent={requestProgress[item.status]} showInfo={false} strokeColor="#28594f" />
              </List.Item>} />
          </Card>
        </Col>

        <Col xs={24} xl={8}>
          <Card className="deadline-card" title="即将截止"
            extra={<Button type="link" onClick={() => navigate('/requests')}>查看全部 <ArrowRightOutlined /></Button>}>
            <List dataSource={nearDeadlines} locale={{ emptyText: '近期没有截止任务' }}
              renderItem={(item) => <List.Item className="deadline-item" onClick={() => navigate('/requests')}>
                <div className="deadline-date"><strong>{dayjs(item.deadline).format('DD')}</strong><span>{dayjs(item.deadline).format('MM 月')}</span></div>
                <div><Typography.Text strong>{item.title}</Typography.Text><Typography.Text type="secondary">剩余 {Math.max(0, dayjs(item.deadline).diff(dayjs(), 'day'))} 天</Typography.Text></div>
              </List.Item>} />
          </Card>

          <Card className="health-card" title="项目健康度"
            extra={<Button type="link" onClick={() => navigate('/projects')}>查看全部 <ArrowRightOutlined /></Button>}>
            <List dataSource={data.projects.items.slice(0, 5)} locale={{ emptyText: '暂无项目' }}
              renderItem={(item) => {
                const percent = item.status === 'COMPLETED' ? 100 : item.status === 'ACTIVE' ? 68 : item.status === 'DRAFT' ? 18 : 0
                return <List.Item className="health-item" onClick={() => navigate(`/projects/${item.id}`)}>
                  <div><Typography.Text strong>{item.title}</Typography.Text><StatusTag value={item.status} /></div>
                  <Progress percent={percent} size="small" strokeColor="#28594f" />
                </List.Item>
              }} />
          </Card>
        </Col>
      </Row>
    </DataState>
  </>
}
