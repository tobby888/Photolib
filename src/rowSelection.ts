import type { Key } from 'react'

/**
 * 服务端分页的表格里跨页保留勾选。
 *
 * ## 为什么需要它
 *
 * antd 的 `rowSelection` 默认只认**当前 `dataSource` 里存在的行**：勾选变化时它先把不在
 * `dataSource` 里的 key 全部滤掉，再把剩下的交给 `onChange`（见 antd 的 `useSelection`，
 * `preserveSelectedRowKeys` 那个分支）。服务端分页时 `dataSource` 只有当前这一页，于是第 1 页
 * 勾了几条、翻到第 2 页随手再勾一条，第 1 页那几条就在这一下里被悄悄丢掉了——用户翻回去才
 * 发现勾选没了，而且看不出是哪一步丢的。所以这类表格必须同时做两件事：
 *
 * 1. 给 `rowSelection` 加上 `preserveSelectedRowKeys`，antd 才会把别的页的 key 留在列表里；
 * 2. 自己按 id 存住**整行对象**（批量操作要读状态、版本号，光有 id 不够），用下面这个函数
 *    把 key 列表还原成行对象。
 *
 * 顺带一提：勾选按 id 累加、翻页不清空，和分享页（`src/shareSelection.ts`）、图库卡片列表
 * 是同一套约定——「看不见的勾选」必须在界面上有个说法（已选 N 条 + 一个清空按钮）。
 */

/**
 * 按勾选后的 key 顺序还原行对象。
 *
 * `sources` 里靠后的来源优先：把刚从服务端取回来的当前页放在最后，勾着的行就会被换成新数据，
 * 而不是留着一份可能已经过期的快照（版本号对不上，批量操作会被乐观锁拒掉）。
 * 认不出来的 key 直接丢掉——那是数据被别人删了，留着也只会让批量操作报错。
 */
export function keepSelectedRows<T>(
  keys: readonly Key[],
  sources: readonly (readonly T[] | undefined)[],
  keyOf: (row: T) => Key,
): T[] {
  const known = new Map<string, T>()
  for (const source of sources) {
    for (const row of source ?? []) known.set(String(keyOf(row)), row)
  }
  const picked: T[] = []
  const seen = new Set<string>()
  for (const key of keys) {
    const id = String(key)
    if (seen.has(id)) continue
    const row = known.get(id)
    if (!row) continue
    seen.add(id)
    picked.push(row)
  }
  return picked
}
