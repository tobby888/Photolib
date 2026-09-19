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
 */
export interface LoginOrigin {
  pathname: string
  search?: string
}

/**
 * @param mustChangePassword 允许为 undefined：localStorage 里那份缓存身份可能是旧版本
 *   写下的，缺这个字段时按"不需要改密"处理即可——真要改密，后端会在下一次请求上把人挡回去。
 * @param state 被弹来登录时带上的路由 state，形如 `{ from: location }`。
 */
export function afterLoginRoute(mustChangePassword: boolean | undefined, state: unknown): string {
  if (mustChangePassword) return '/initial-password'
  const from = (state as { from?: LoginOrigin } | null | undefined)?.from
  if (!from?.pathname) return '/'
  return `${from.pathname}${from.search ?? ''}`
}
