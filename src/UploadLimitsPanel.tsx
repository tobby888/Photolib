import { Alert, App, Button, Card, Col, InputNumber, Row, Space, Tag, Typography } from 'antd'
import { CloudUploadOutlined, RedoOutlined, SaveOutlined, UndoOutlined } from '@ant-design/icons'
import { useEffect, useMemo, useState } from 'react'
import { api } from './api'
import { DataState } from './components'
import { useLoad } from './hooks'
import {
  changedLimits, describeLimit, inputUnit, normalizeUploadLimits,
  type UploadLimitKey, type UploadLimitSetting,
} from './uploadLimits'
import { publishUploadLimits } from './useUploadLimits'

type Drafts = Partial<Record<UploadLimitKey, number>>

const draftsOf = (settings: UploadLimitSetting[]): Drafts =>
  Object.fromEntries(settings.map(setting => [setting.key, setting.value]))

/**
 * 系统管理 → 上传限额。所有上传的大小、ZIP 内图片张数都在这里改，保存后立即生效；
 * 服务端（UploadLimitService）是唯一的执行者，这里的范围也由服务端给出。
 */
export default function UploadLimitsPanel() {
  const { message } = App.useApp()
  const { data: settings, setData: setSettings, loading, error, reload } = useLoad(
    () => api<UploadLimitSetting[]>({ url: '/upload-limits/settings' }), [] as UploadLimitSetting[], [],
  )
  const [drafts, setDrafts] = useState<Drafts>({})
  const [saving, setSaving] = useState(false)

  useEffect(() => { setDrafts(draftsOf(settings)) }, [settings])

  const groups = useMemo(() => {
    const byGroup = new Map<string, { label: string; items: UploadLimitSetting[] }>()
    settings.forEach(setting => {
      const group = byGroup.get(setting.group) ?? { label: setting.groupLabel, items: [] }
      group.items.push(setting)
      byGroup.set(setting.group, group)
    })
    return Array.from(byGroup.entries())
  }, [settings])

  const changes = changedLimits(settings, drafts)
  const changedCount = Object.keys(changes).length

  const save = async () => {
    if (!changedCount) {
      message.info('没有需要保存的修改')
      return
    }
    setSaving(true)
    try {
      const updated = await api<UploadLimitSetting[]>({
        method: 'PUT', url: '/upload-limits', data: { values: changes },
      })
      setSettings(updated)
      setDrafts(draftsOf(updated))
      // 本页其他地方（品牌图标、占位图、备份导入的提示）立刻换成新值。
      publishUploadLimits(normalizeUploadLimits(
        Object.fromEntries(updated.map(setting => [setting.key, setting.value]))))
      message.success(`已保存 ${changedCount} 项上传限额，立即生效`)
    } catch (reason) {
      message.error((reason as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const setDraft = (setting: UploadLimitSetting, displayed: number | null) => {
    if (displayed === null) return
    const { factor } = inputUnit(setting)
    setDrafts(current => {
      const previous = current[setting.key] ?? setting.value
      const next = Math.round(displayed * factor)
      // 输入框按两位小数显示（比如 1.5 GB 显示成 1430.51 MiB），失焦时会把显示值回写一次。
      // 没真正改动的值不能因为这次舍入变成"已修改"。
      return { ...current, [setting.key]: Math.abs(next - previous) <= factor / 200 ? previous : next }
    })
  }

  return <>
    <div className="tab-toolbar">
      <div>
        <Typography.Title level={4}><CloudUploadOutlined /> 上传限额</Typography.Title>
        <Typography.Text type="secondary">
          全站所有上传的文件大小上限，以及 ZIP 压缩包里的图片张数上限，都在这里统一管理。保存后立即生效。
        </Typography.Text>
      </div>
      <Space wrap>
        <Button icon={<RedoOutlined />} onClick={() => void reload()}>刷新</Button>
        <Button icon={<UndoOutlined />} disabled={!changedCount || saving}
          onClick={() => setDrafts(draftsOf(settings))}>撤销修改</Button>
        <Button type="primary" icon={<SaveOutlined />} loading={saving} disabled={!changedCount}
          onClick={() => void save()}>保存{changedCount ? `（${changedCount} 项）` : ''}</Button>
      </Space>
    </div>
    <Alert showIcon type="info" style={{ marginBottom: 16 }}
      title="每一项只能在给定范围内调整"
      description="范围的上限取决于系统能力（图片解码的内存、对象存储单次上传的上限、服务器接收请求体的上限等），
        不是业务偏好，调不过去是有意的。浏览器端的提示会跟着变，但真正拦截超限文件的始终是服务端。" />
    <DataState loading={loading} error={error} onRetry={() => void reload()}>
      <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        {groups.map(([group, { label, items }]) => <Card key={group} size="small" title={label}>
          <Space orientation="vertical" size={20} style={{ width: '100%' }}>
            {items.map(setting => {
              const unit = inputUnit(setting)
              const draft = drafts[setting.key] ?? setting.value
              const edited = draft !== setting.value
              return <Row key={setting.key} gutter={[16, 8]} align="middle">
                <Col xs={24} md={13}>
                  <Space size={8} wrap>
                    <Typography.Text strong>{setting.label}</Typography.Text>
                    {setting.customized && <Tag color="blue">已调整</Tag>}
                    {edited && <Tag color="orange">未保存</Tag>}
                  </Space>
                  <Typography.Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
                    {setting.description}
                  </Typography.Paragraph>
                </Col>
                <Col xs={24} md={11}>
                  <Space size={8} wrap>
                    <InputNumber
                      aria-label={`${label}：${setting.label}`}
                      value={draft / unit.factor}
                      min={setting.min / unit.factor}
                      max={setting.max / unit.factor}
                      precision={unit.label === 'MiB' ? 2 : 0}
                      step={1}
                      disabled={saving}
                      style={{ width: 180 }}
                      suffix={unit.label}
                      onChange={value => setDraft(setting, typeof value === 'number' ? value : null)} />
                    <Button size="small" type="link" disabled={saving || draft === setting.defaultValue}
                      onClick={() => setDrafts(current => ({ ...current, [setting.key]: setting.defaultValue }))}>
                      恢复默认
                    </Button>
                  </Space>
                  <Typography.Text type="secondary" style={{ display: 'block', marginTop: 4, fontSize: 12 }}>
                    {[
                      `当前 ${describeLimit(setting, draft)}`,
                      `默认 ${describeLimit(setting, setting.defaultValue)}`,
                      `可调 ${describeLimit(setting, setting.min)}–${describeLimit(setting, setting.max)}`,
                    ].join(' · ')}
                  </Typography.Text>
                </Col>
              </Row>
            })}
          </Space>
        </Card>)}
      </Space>
    </DataState>
  </>
}
