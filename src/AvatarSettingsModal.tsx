import {
  DeleteOutlined, MinusOutlined, PlusOutlined, UploadOutlined,
} from '@ant-design/icons'
import { App, Button, Modal, Slider, Space, Typography, Upload } from 'antd'
import { useEffect, useRef, useState } from 'react'
import Cropper, { type Area, type Point } from 'react-easy-crop'
import 'react-easy-crop/react-easy-crop.css'
import { api } from './api'
import {
  AVATAR_ACCEPT,
  createCroppedAvatar,
  readImageDimensions,
  validateAvatarDimensions,
  validateAvatarFile,
} from './avatarImage'
import { useAuth } from './auth'
import UserAvatar from './UserAvatar'

interface AvatarMutationResult {
  avatarUrl: string | null
}

interface AvatarCandidate {
  source: string
}

function AvatarCropModal({ candidate, onCancel, onSaved }: {
  candidate: AvatarCandidate
  onCancel: () => void
  onSaved: (avatarUrl: string) => void
}) {
  const { message } = App.useApp()
  const [crop, setCrop] = useState<Point>({ x: 0, y: 0 })
  const [zoom, setZoom] = useState(1)
  const [cropPixels, setCropPixels] = useState<Area>()
  const [saving, setSaving] = useState(false)
  const [cropperReady, setCropperReady] = useState(false)

  const save = async () => {
    if (!cropPixels) {
      message.warning('请先调整头像裁切范围')
      return
    }
    setSaving(true)
    try {
      const file = await createCroppedAvatar(candidate.source, cropPixels)
      const validation = validateAvatarFile(file)
      if (!validation.valid) throw new Error(validation.message)
      const data = new FormData()
      data.append('file', file)
      const result = await api<AvatarMutationResult>({
        method: 'PUT',
        url: '/users/me/avatar',
        data,
      })
      if (!result.avatarUrl) throw new Error('服务器未返回头像地址')
      setSaving(false)
      onSaved(result.avatarUrl)
    } catch (error) {
      message.error((error as Error).message)
      setSaving(false)
    }
  }

  return <Modal
    title="裁切头像"
    open
    width={560}
    okText="保存头像"
    cancelText="取消"
    confirmLoading={saving}
    okButtonProps={{ disabled: !cropPixels }}
    maskClosable={!saving}
    closable={!saving}
    keyboard={!saving}
    afterOpenChange={setCropperReady}
    onCancel={() => { if (!saving) onCancel() }}
    onOk={() => void save()}
  >
    <Typography.Paragraph type="secondary" className="avatar-crop-help">
      拖动图片调整位置，滚轮、双指或下方滑块可放大和缩小。圆形区域是最终展示范围。
    </Typography.Paragraph>
    <div className="avatar-crop-stage">
      {cropperReady && <Cropper
        image={candidate.source}
        crop={crop}
        zoom={zoom}
        aspect={1}
        cropShape="round"
        showGrid={false}
        minZoom={1}
        maxZoom={3}
        zoomWithScroll
        keyboardStep={5}
        disableAutomaticStylesInjection
        mediaProps={{ alt: '待裁切头像' }}
        onCropChange={setCrop}
        onZoomChange={setZoom}
        onCropComplete={(_, pixels) => setCropPixels(pixels)}
      />}
    </div>
    <div className="avatar-zoom-control">
      <Button type="text" shape="circle" icon={<MinusOutlined />} aria-label="缩小头像"
        disabled={zoom <= 1} onClick={() => setZoom(value => Math.max(1, value - 0.1))} />
      <Slider min={1} max={3} step={0.01} value={zoom} tooltip={{ formatter: value => `${Math.round((value || 1) * 100)}%` }}
        aria-label="头像缩放比例" onChange={setZoom} />
      <Button type="text" shape="circle" icon={<PlusOutlined />} aria-label="放大头像"
        disabled={zoom >= 3} onClick={() => setZoom(value => Math.min(3, value + 0.1))} />
    </div>
  </Modal>
}

export default function AvatarSettingsModal({ open, onClose }: {
  open: boolean
  onClose: () => void
}) {
  const { user, updateUser } = useAuth()
  const { message, modal } = App.useApp()
  const [candidate, setCandidate] = useState<AvatarCandidate>()
  const [validating, setValidating] = useState(false)
  const [removing, setRemoving] = useState(false)
  const activeRef = useRef(true)

  useEffect(() => {
    activeRef.current = true
    return () => { activeRef.current = false }
  }, [])

  useEffect(() => {
    if (!candidate) return
    return () => URL.revokeObjectURL(candidate.source)
  }, [candidate])

  if (!user) return null

  const chooseFile = async (file: File) => {
    const metadataValidation = validateAvatarFile(file)
    if (!metadataValidation.valid) {
      message.error(metadataValidation.message)
      return
    }

    setValidating(true)
    try {
      const dimensions = await readImageDimensions(file)
      const dimensionValidation = validateAvatarDimensions(dimensions)
      if (!dimensionValidation.valid) throw new Error(dimensionValidation.message)
      const source = URL.createObjectURL(file)
      if (!activeRef.current) {
        URL.revokeObjectURL(source)
        return
      }
      setCandidate({ source })
    } catch (error) {
      if (activeRef.current) message.error((error as Error).message)
    } finally {
      if (activeRef.current) setValidating(false)
    }
  }

  const removeAvatar = () => {
    modal.confirm({
      title: '移除当前头像？',
      content: '移除后将恢复为系统默认头像。',
      okText: '移除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        setRemoving(true)
        try {
          const result = await api<AvatarMutationResult>({ method: 'DELETE', url: '/users/me/avatar' })
          updateUser({ avatarUrl: result.avatarUrl })
          message.success('已恢复默认头像')
        } catch (error) {
          message.error((error as Error).message)
          throw error
        } finally {
          setRemoving(false)
        }
      },
    })
  }

  return <>
    <Modal title="个人头像" open={open} footer={null} width={480}
      maskClosable={!validating} closable={!validating} keyboard={!validating}
      onCancel={() => { if (!validating) onClose() }}>
      <div className="avatar-settings">
        <UserAvatar className="avatar-settings-preview" size={112} avatarUrl={user.avatarUrl} label={user.displayName} />
        <div className="avatar-settings-copy">
          <Typography.Title level={4}>{user.displayName}</Typography.Title>
          <Typography.Text type="secondary">@{user.username}</Typography.Text>
          <Typography.Paragraph type="secondary">
            支持 JPEG、PNG；原图不超过 1 MiB，宽高均不超过 1024 像素。
          </Typography.Paragraph>
          <Space wrap>
            <Upload
              accept={AVATAR_ACCEPT}
              maxCount={1}
              disabled={validating}
              showUploadList={false}
              beforeUpload={file => {
                void chooseFile(file)
                return Upload.LIST_IGNORE
              }}
            >
              <Button type="primary" icon={<UploadOutlined />} loading={validating}>
                {user.avatarUrl ? '更换头像' : '上传头像'}
              </Button>
            </Upload>
            {user.avatarUrl && <Button danger icon={<DeleteOutlined />} loading={removing} onClick={removeAvatar}>
              移除头像
            </Button>}
          </Space>
        </div>
      </div>
    </Modal>
    {candidate && <AvatarCropModal
      key={candidate.source}
      candidate={candidate}
      onCancel={() => setCandidate(undefined)}
      onSaved={avatarUrl => {
        updateUser({ avatarUrl })
        setCandidate(undefined)
        message.success('头像已更新')
      }}
    />}
  </>
}
