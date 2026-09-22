/**
 * 登录成功后该落在哪一页。
 *
 * 被外壳弹来登录的人带着 `state.from`（外壳传的是整个 location，所以 query 也在里面），
 * 登录完要回到那一页——MCP 批准页尤其明显：配对号在 query 里，只回 pathname 就等于把它
 * 丢了，人只能回终端重发一次配对。
 *
 * 这个判断有**两个**调用方，而且它们在赛跑：`LoginPage` 拿到结果后自己 navigate，
 * 同时 `App` 里 `/login` 那条路由一旦看到 user 就渲染成 `<Navigate replace>`，
 * 后者的 effect 有机会在前者之后才跑、把结果 replace 掉（谁先谁后取决于 antd 的
 * message 有没有顺手 flush 掉那次状态更新，不该去赌）。抽成一个函数是为了让赛跑的
 * 结果无所谓：两条路指向同一个地方。**不要把这段逻辑复制回任何一个调用方。**
 *
 * 两步验证的两种跳转也在这里：被强制却没绑定的，先去绑定（排在首次改密之前——重置过
 * 密码的账号设备已被清空，后端在绑定前不放行改密）；被建议却没绑定的，每次登录后先看
 * 一眼建议页。两种都把原本的去向放进 `next`，绑定或跳过之后接着去。
 */
export interface LoginOrigin {
  pathname: string
  search?: string
}

/** 只需要登录身份里的这几个字段；localStorage 里旧版本写下的缓存可能一个都没有。 */
export interface LoginTarget {
  mustChangePassword?: boolean
  mfa?: { enrollmentRequired?: boolean; suggested?: boolean } | null
}

export const TWO_FACTOR_ROUTE = '/two-factor'

/**
 * @param user 缺字段时按"不需要改密 / 不涉及两步验证"处理即可——真要改密或绑定，
 *   后端会在下一次请求上把人挡回去。
 * @param state 被弹来登录时带上的路由 state，形如 `{ from: location }`。
 */
export function afterLoginRoute(user: LoginTarget | null | undefined, state: unknown): string {
  const from = (state as { from?: LoginOrigin } | null | undefined)?.from
  const destination = from?.pathname ? `${from.pathname}${from.search ?? ''}` : '/'
  if (user?.mfa?.enrollmentRequired) return twoFactorRoute(destination)
  if (user?.mustChangePassword) return '/initial-password'
  if (user?.mfa?.suggested) return twoFactorRoute(destination)
  return destination
}

function twoFactorRoute(destination: string): string {
  return destination === '/' ? TWO_FACTOR_ROUTE : `${TWO_FACTOR_ROUTE}?next=${encodeURIComponent(destination)}`
}

/**
 * 两步验证页处理完后回到哪里。`next` 来自地址栏，只认站内路径：以单个 `/` 开头，
 * 不能是 `//evil.example` 这种协议相对地址，也不能绕回两步验证页自己。
 */
export function twoFactorNext(search: string): string {
  const next = new URLSearchParams(search).get('next')
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return '/'
  if (next === TWO_FACTOR_ROUTE || next.startsWith(`${TWO_FACTOR_ROUTE}?`)) return '/'
  return next
}
