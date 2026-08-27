import { AimOutlined, BulbOutlined, CameraOutlined, PictureOutlined, StarOutlined } from '@ant-design/icons'
import { cloneElement, createContext, useContext, useEffect, useState, type CSSProperties, type ReactElement, type ReactNode } from 'react'
import { api } from './api'
import type { BrandingSettings } from './types'

export const BRANDING_UPDATED_EVENT = 'branding-updated'

// Neutral placeholder used only until `/branding` answers, or when it fails: the
// product identity on every screen belongs to whatever the administrator set.
export const defaultBranding: BrandingSettings = {
  title: '摄影工作站', iconType: 'builtin', builtinIcon: 'camera', slogan: '影像协作平台',
}

interface GlyphProps { className?: string; style?: CSSProperties }

export const brandIcons: Record<BrandingSettings['builtinIcon'], ReactElement<GlyphProps>> = {
  camera: <CameraOutlined />, aperture: <AimOutlined />, picture: <PictureOutlined />,
  bulb: <BulbOutlined />, star: <StarOutlined />,
}

const BrandingContext = createContext<BrandingSettings>(defaultBranding)

export function useBranding() {
  return useContext(BrandingContext)
}

export function BrandingProvider({ children }: { children: ReactNode }) {
  const [branding, setBranding] = useState<BrandingSettings>(defaultBranding)

  useEffect(() => {
    const loadBranding = () => void api<BrandingSettings>({ url: '/branding' })
      .then(setBranding).catch(() => setBranding(defaultBranding))
    loadBranding()
    window.addEventListener(BRANDING_UPDATED_EVENT, loadBranding)
    return () => window.removeEventListener(BRANDING_UPDATED_EVENT, loadBranding)
  }, [])

  useEffect(() => {
    if (!branding.nextIconRefreshAt) return
    const refreshAt = Date.parse(branding.nextIconRefreshAt)
    if (!Number.isFinite(refreshAt)) return
    const timer = window.setTimeout(() => {
      window.dispatchEvent(new Event(BRANDING_UPDATED_EVENT))
    }, Math.max(1000, refreshAt - Date.now() + 1000))
    return () => window.clearTimeout(timer)
  }, [branding.nextIconRefreshAt])

  useEffect(() => {
    document.title = branding.slogan ? `${branding.title} · ${branding.slogan}` : branding.title
  }, [branding.title, branding.slogan])

  return <BrandingContext.Provider value={branding}>{children}</BrandingContext.Provider>
}

// The scheduled icon (`display*`) wins over the standing one whenever a rule is active today.
export function BrandGlyph({ branding, className, style }: {
  branding: BrandingSettings
  className?: string
  style?: CSSProperties
}) {
  const iconType = branding.displayIconType ?? branding.iconType
  const iconUrl = branding.displayIconUrl ?? branding.customIconUrl
  if (iconType === 'custom' && iconUrl) return <img className={className} style={style} src={iconUrl} alt="" />
  return cloneElement(brandIcons[branding.builtinIcon] || brandIcons.camera, { className, style })
}
