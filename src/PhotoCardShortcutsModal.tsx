import { Alert, App, Button, Modal, Space, Typography } from 'antd'
import { useEffect, useState } from 'react'
import {
  DEFAULT_PHOTO_CARD_SHORTCUTS, PHOTO_CARD_SHORTCUT_LABELS, formatShortcutKey, normalizeShortcutKey,
  readPhotoCardShortcuts, savePhotoCardShortcuts, validatePhotoCardShortcuts, validateShortcutKey,
  type PhotoCardShortcutAction, type PhotoCardShortcuts,
} from './photoCardShortcuts'

/**
 * 图片卡片快捷键设置。点「修改」后按下想用的键即可录入；设置只存在这个浏览器里
 * （原因见 src/photoCardShortcuts.ts）。
 */
export default function PhotoCardShortcutsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { message } = App.useApp()
  const [draft, setDraft] = useState<PhotoCardShortcuts>(readPhotoCardShortcuts)
  const [recording, setRecording] = useState<PhotoCardShortcutAction | null>(null)
  const [recordError, setRecordError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setDraft(readPhotoCardShortcuts())
    setRecording(null)
    setRecordError(null)
  }, [open])

  useEffect(() => {
    if (!recording) return
    // 捕获阶段拦下按键：录入期间按空格 / Enter 不能顺手点到弹窗按钮，Esc 也不能关掉弹窗。
    const onKeyDown = (event: KeyboardEvent) => {
      event.preventDefault()
      event.stopPropagation()
      if (event.key === 'Escape') {
        setRecording(null)
        setRecordError(null)
        return
      }
      if (event.ctrlKey || event.altKey || event.metaKey) {
        setRecordError('快捷键不支持组合键（Ctrl / Alt / ⌘），请只按一个键')
        return
      }
      const error = validateShortcutKey(event.key)
      if (error) {
        // 单独按下 Shift 等修饰键时继续等下一个键，不打断录入。
        setRecordError(error)
        return
      }
      setDraft(current => ({ ...current, [recording]: normalizeShortcutKey(event.key) }))
      setRecording(null)
      setRecordError(null)
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [recording])

  const conflict = validatePhotoCardShortcuts(draft)

  const save = () => {
    try {
      savePhotoCardShortcuts(draft)
      message.success('快捷键已保存')
      onClose()
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  return <Modal open={open} title="图片快捷键" onCancel={onClose} keyboard={!recording} mask={{ closable: !recording }}
    destroyOnHidden
    footer={<Space>
      <Button disabled={!!recording} onClick={() => {
        setDraft(DEFAULT_PHOTO_CARD_SHORTCUTS)
        setRecordError(null)
      }}>恢复默认</Button>
      <Button onClick={onClose}>取消</Button>
      <Button type="primary" disabled={!!conflict || !!recording} onClick={save}>保存</Button>
    </Space>}>
    <Typography.Paragraph type="secondary">
      在图库、选题详情、分享页和需求交付页，用 Tab 选中一张图片后按这些键操作。
      按住 Shift 再按「选择」键，会从上次选中的图片连选到当前图片。设置只保存在当前浏览器。
    </Typography.Paragraph>
    <div className="shortcut-settings">
      {(['open', 'select'] as const).map(action => <div className="shortcut-settings-row" key={action}>
        <span>{PHOTO_CARD_SHORTCUT_LABELS[action]}</span>
        <kbd className={recording === action ? 'is-recording' : undefined} aria-live="polite">
          {recording === action ? '请按下新按键…' : formatShortcutKey(draft[action])}
        </kbd>
        <Button size="small" type={recording === action ? 'primary' : 'default'}
          disabled={!!recording && recording !== action}
          onClick={() => {
            setRecordError(null)
            setRecording(current => current === action ? null : action)
          }}>
          {recording === action ? '取消录入' : '修改'}
        </Button>
      </div>)}
    </div>
    {recording && <Typography.Text type="secondary">按 Esc 放弃这次修改。</Typography.Text>}
    {(recordError || conflict) && <Alert className="shortcut-settings-alert" type="warning" showIcon
      title={recordError || conflict} />}
  </Modal>
}
