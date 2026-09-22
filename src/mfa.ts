import { api, type LoginResult } from './api'
import type { MfaDevice, MfaOverview } from './types'
import { createCredential, getAssertion } from './webauthn'

/** 两步验证相关的接口调用，页面和弹窗共用。 */

export interface StepUpStatus {
  required: boolean
  verified: boolean
  verifiedUntil?: string | null
}

interface WebAuthnChallenge {
  challengeToken: string
  publicKey: Record<string, unknown>
}

export interface TotpSetup {
  deviceId: string
  secret: string
  otpauthUri: string
}

export const loadMfaOverview = () => api<MfaOverview>({ url: '/auth/mfa' })

export const beginTotp = () => api<TotpSetup>({ method: 'POST', url: '/auth/mfa/totp' })

export const confirmTotp = (deviceId: string, code: string, name: string) =>
  api<MfaDevice>({ method: 'POST', url: `/auth/mfa/totp/${deviceId}/confirm`, data: { code, name } })

export async function registerSecurityKey(name: string) {
  const options = await api<WebAuthnChallenge>({ method: 'POST', url: '/auth/mfa/webauthn/options' })
  const attestation = await createCredential(options.publicKey)
  return api<MfaDevice>({
    method: 'POST', url: '/auth/mfa/webauthn',
    data: { challengeToken: options.challengeToken, name, ...attestation },
  })
}

export const deleteMfaDevice = (id: string) => api<void>({ method: 'DELETE', url: `/auth/mfa/devices/${id}` })

export const revokeTrustedBrowser = (id: string) =>
  api<void>({ method: 'DELETE', url: `/auth/mfa/trusted-browsers/${id}` })

export const loadStepUpStatus = () => api<StepUpStatus>({ url: '/auth/mfa/step-up' })

export const stepUpWithCode = (code: string) =>
  api<StepUpStatus>({ method: 'POST', url: '/auth/mfa/step-up', data: { code } })

export async function stepUpWithSecurityKey() {
  const options = await api<WebAuthnChallenge>({ method: 'POST', url: '/auth/mfa/step-up/webauthn-options' })
  const assertion = await getAssertion(options.publicKey)
  return api<StepUpStatus>({
    method: 'POST', url: '/auth/mfa/step-up',
    data: { assertion, challengeToken: options.challengeToken },
  })
}

export const loginWithCode = (ticket: string, code: string, trustDevice: boolean) =>
  api<LoginResult>({ method: 'POST', url: '/auth/login/mfa', data: { ticket, code, trustDevice } })

export async function loginWithSecurityKey(ticket: string, trustDevice: boolean) {
  const options = await api<Record<string, unknown>>({
    method: 'POST', url: '/auth/login/mfa/webauthn-options', data: { ticket },
  })
  const assertion = await getAssertion(options)
  return api<LoginResult>({ method: 'POST', url: '/auth/login/mfa', data: { ticket, assertion, trustDevice } })
}

/** 设备类型的中文名。 */
export const deviceTypeLabel = (type: MfaDevice['type']) => type === 'TOTP' ? '验证器 App' : '安全密钥 / 通行密钥'
