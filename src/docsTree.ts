import type { DocManageNode, DocNodeType, DocReaderNode } from './types'

/**
 * 文档树的纯逻辑：拖拽落点换算、祖先展开、顺序遍历。
 *
 * <p>这些计算和 antd Tree 的渲染分开放，因为拖拽落点的算法是整个文档功能里
 * 最容易算错、又最难靠肉眼发现错误的一段——差一位不会报错，只会让文档
 * 悄悄排到别的地方去。放在这里可以直接对着 tests/docsTree.test.ts 验。</p>
 */
export interface DocTreeNodeLike {
  key: string
  nodeType: DocNodeType
  children: DocTreeNodeLike[]
}

export interface DocDropTarget {
  /** null 表示根目录。 */
  parentKey: string | null
  /** 在目标父节点下的落点，已经排除掉被拖动的节点自身。 */
  index: number
}

export function manageNodesToTree(nodes: DocManageNode[]): DocTreeNodeLike[] {
  return nodes.map(node => ({
    key: String(node.id),
    nodeType: node.nodeType,
    children: manageNodesToTree(node.children || []),
  }))
}

export function readerNodesToTree(nodes: DocReaderNode[]): DocTreeNodeLike[] {
  return nodes.map(node => ({
    key: node.publicId,
    nodeType: node.nodeType,
    children: readerNodesToTree(node.children || []),
  }))
}

/** 某个节点的父节点 key；根节点返回 null，找不到返回 undefined。 */
export function parentKeyOf(tree: DocTreeNodeLike[], key: string): string | null | undefined {
  const walk = (nodes: DocTreeNodeLike[], parent: string | null): string | null | undefined => {
    for (const node of nodes) {
      if (node.key === key) return parent
      const found = walk(node.children, node.key)
      if (found !== undefined) return found
    }
    return undefined
  }
  return walk(tree, null)
}

export function childKeysOf(tree: DocTreeNodeLike[], parentKey: string | null): string[] {
  if (parentKey === null) return tree.map(node => node.key)
  const node = findNode(tree, parentKey)
  return node ? node.children.map(child => child.key) : []
}

export function findNode(tree: DocTreeNodeLike[], key: string): DocTreeNodeLike | undefined {
  for (const node of tree) {
    if (node.key === key) return node
    const found = findNode(node.children, key)
    if (found) return found
  }
  return undefined
}

/** target 是否在 ancestor 的子树里（不含 ancestor 自身）。 */
export function isDescendant(tree: DocTreeNodeLike[], ancestorKey: string, targetKey: string) {
  const ancestor = findNode(tree, ancestorKey)
  return ancestor ? !!findNode(ancestor.children, targetKey) : false
}

/** 从根到该节点的所有祖先 key，用来在跳转后自动展开到选中的文档。 */
export function ancestorKeysOf(tree: DocTreeNodeLike[], key: string): string[] {
  const walk = (nodes: DocTreeNodeLike[], trail: string[]): string[] | undefined => {
    for (const node of nodes) {
      if (node.key === key) return trail
      const found = walk(node.children, [...trail, node.key])
      if (found) return found
    }
    return undefined
  }
  return walk(tree, []) || []
}

/** 在编辑树里按 id 找节点。树很小，直接递归即可。 */
export function findManageNode(nodes: DocManageNode[], id: string): DocManageNode | undefined {
  for (const node of nodes) {
    if (String(node.id) === id) return node
    const found = findManageNode(node.children || [], id)
    if (found) return found
  }
  return undefined
}

/** 按目录顺序展开所有文档节点的 key，用于"默认打开第一篇"。 */
export function documentKeysInOrder(tree: DocTreeNodeLike[]): string[] {
  return tree.flatMap(node => node.nodeType === 'DOCUMENT'
    ? [node.key]
    : documentKeysInOrder(node.children))
}

/**
 * 把 antd Tree 的 onDrop 信息换算成"移到哪个父节点下的第几位"。
 *
 * <p>返回的 index 是<b>排除被拖动节点之后</b>的位置，和服务端 DocService.move
 * 的语义一致：服务端先把被移动的节点从兄弟列表里摘掉，再插到 index 处。
 * 同父节点内往后拖时如果不先摘掉自己，落点就会整体偏一位。</p>
 *
 * @param relativePosition antd 的相对落点：-1 落在目标之前，0 落在目标内部，1 落在目标之后
 * @returns 不合法的落点返回 null（拖到文档内部、或把文件夹拖进自己的子树）
 */
export function resolveDrop(
  tree: DocTreeNodeLike[],
  dragKey: string,
  dropKey: string,
  dropToGap: boolean,
  relativePosition: number,
): DocDropTarget | null {
  if (dragKey === dropKey) return null
  // 文件夹不能塞进自己的子树，否则这棵树就断成了一个环。
  if (isDescendant(tree, dragKey, dropKey)) return null

  if (!dropToGap) {
    const target = findNode(tree, dropKey)
    // 文档不是容器；拖到一篇文档"内部"没有意义，返回 null 让调用方给出提示，
    // 而不是替用户猜一个落点。
    if (!target || target.nodeType !== 'FOLDER') return null
    const siblings = childKeysOf(tree, dropKey).filter(key => key !== dragKey)
    return { parentKey: dropKey, index: siblings.length }
  }

  const parentKey = parentKeyOf(tree, dropKey)
  if (parentKey === undefined) return null
  const siblings = childKeysOf(tree, parentKey).filter(key => key !== dragKey)
  const base = siblings.indexOf(dropKey)
  if (base < 0) return null
  return { parentKey, index: relativePosition === -1 ? base : base + 1 }
}

/**
 * antd 把落点给成绝对序号，要减去目标节点在其父节点下的序号才得到 -1/0/1。
 * 这行换算在 antd 的官方示例里也是照抄的，单独拎出来是为了能被测试覆盖。
 */
export function relativeDropPosition(nodePos: string, dropPosition: number) {
  const parts = nodePos.split('-')
  return dropPosition - Number(parts[parts.length - 1])
}
