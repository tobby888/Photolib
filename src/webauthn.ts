/**
 * 安全密钥 / 通行密钥（WebAuthn）的浏览器端。调 `navigator.credentials` 会弹出系统自带的
 * 那个"用手机扫码 / 插入 USB 密钥 / Windows Hello"窗口。
 *
 * 后端给的参数和要回传的结果里，二进制字段一律是 base64url 字符串；浏览器 API 要的是
 * ArrayBuffer，转换都在这里。没有用 `PublicKeyCredential.parseCreationOptionsFromJSON`，
 * 它在 Safari 和较旧的 Chromium 上还不可用。
 */

export interface WebAuthnAssertion {
  credentialId: string
  clientDataJSON: string
  authenticatorData: string
  signature: string
  userHandle?: string
}

export interface WebAuthnAttestation {
  clientDataJSON: string
  attestationObject: string
  transports: string[]
}

type JsonOptions = Record<string, unknown>

export function webAuthnSupported(): boolean {
  return typeof window !== 'undefined' && typeof window.PublicKeyCredential === 'function'
    && !!navigator.credentials && window.isSecureContext
}

export function toBase64Url(buffer: ArrayBuffer | ArrayBufferView): string {
  const bytes = buffer instanceof ArrayBuffer
    ? new Uint8Array(buffer)
    : new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function fromBase64Url(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = base64 + '='.repeat((4 - base64.length % 4) % 4)
  const binary = atob(padded)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index)
  return bytes.buffer
}

function descriptors(value: unknown): PublicKeyCredentialDescriptor[] {
  return ((value as { type: string; id: string; transports?: string[] }[] | undefined) ?? []).map(item => ({
    type: 'public-key',
    id: fromBase64Url(item.id),
    ...(item.transports ? { transports: item.transports as AuthenticatorTransport[] } : {}),
  }))
}

export async function createCredential(options: JsonOptions): Promise<WebAuthnAttestation> {
  const user = options.user as { id: string; name: string; displayName: string }
  const publicKey = {
    ...options,
    challenge: fromBase64Url(options.challenge as string),
    user: { ...user, id: fromBase64Url(user.id) },
    excludeCredentials: descriptors(options.excludeCredentials),
  } as unknown as PublicKeyCredentialCreationOptions
  const credential = await callBrowser(() => navigator.credentials.create({ publicKey })) as PublicKeyCredential
  const response = credential.response as AuthenticatorAttestationResponse
  return {
    clientDataJSON: toBase64Url(response.clientDataJSON),
    attestationObject: toBase64Url(response.attestationObject),
    transports: typeof response.getTransports === 'function' ? response.getTransports() : [],
  }
}

export async function getAssertion(options: JsonOptions): Promise<WebAuthnAssertion> {
  const publicKey = {
    ...options,
    challenge: fromBase64Url(options.challenge as string),
    allowCredentials: descriptors(options.allowCredentials),
  } as unknown as PublicKeyCredentialRequestOptions
  const credential = await callBrowser(() => navigator.credentials.get({ publicKey })) as PublicKeyCredential
  const response = credential.response as AuthenticatorAssertionResponse
  return {
    credentialId: toBase64Url(credential.rawId),
    clientDataJSON: toBase64Url(response.clientDataJSON),
    authenticatorData: toBase64Url(response.authenticatorData),
    signature: toBase64Url(response.signature),
    ...(response.userHandle ? { userHandle: toBase64Url(response.userHandle) } : {}),
  }
}

/** 把浏览器抛的 DOMException 换成能给人看的话。 */
async function callBrowser<T>(action: () => Promise<T | null>): Promise<T> {
  if (!webAuthnSupported()) throw new Error('当前浏览器或网址不支持安全密钥，请改用验证器 App')
  try {
    const result = await action()
    if (!result) throw new Error('没有拿到安全密钥的响应，请重试')
    return result
  } catch (error) {
    const name = (error as { name?: string }).name
    if (name === 'NotAllowedError') throw new Error('已取消，或等待安全密钥超时')
    if (name === 'InvalidStateError') throw new Error('这把安全密钥已经绑定过了')
    if (name === 'SecurityError') throw new Error('当前网址不支持安全密钥（需要 HTTPS 域名）')
    throw error
  }
}
