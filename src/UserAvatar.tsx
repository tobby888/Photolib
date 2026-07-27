import { UserOutlined } from '@ant-design/icons'
import { Avatar } from 'antd'
import { useEffect, useState, type ComponentProps } from 'react'
import { http } from './api'
import { avatarRequestPath } from './avatarImage'

type AntAvatarProps = ComponentProps<typeof Avatar>

export interface UserAvatarProps extends Omit<AntAvatarProps, 'icon' | 'src'> {
  avatarUrl?: string | null
  label?: string
}

/** Loads private avatars through the authenticated HTTP client and owns its Blob URL. */
export default function UserAvatar({ avatarUrl, label, ...avatarProps }: UserAvatarProps) {
  const [objectUrl, setObjectUrl] = useState<string>()
  const requestPath = avatarRequestPath(avatarUrl)

  useEffect(() => {
    setObjectUrl(undefined)
    if (!requestPath) return

    let active = true
    let loadedUrl: string | undefined
    void http.get<Blob>(requestPath, { responseType: 'blob' })
      .then(response => {
        if (!active) return
        loadedUrl = URL.createObjectURL(response.data)
        setObjectUrl(loadedUrl)
      })
      .catch(() => {
        if (active) setObjectUrl(undefined)
      })

    return () => {
      active = false
      if (loadedUrl) URL.revokeObjectURL(loadedUrl)
    }
  }, [requestPath])

  return <Avatar
    {...avatarProps}
    alt={label}
    src={objectUrl}
    icon={!objectUrl ? <UserOutlined /> : undefined}
  />
}
