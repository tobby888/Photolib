import type { ImgHTMLAttributes } from 'react'

/**
 * 所有渲染签名预览地址的元素统一用这个 CORS 模式——而它刻意是"不用 CORS"。
 *
 * 浏览器的 HTTP 缓存只按 URL 索引，不记录条目是哪种 CORS 模式取回的。普通 `<img>`
 * 是 no-cors 请求、不带 `Origin`，对象存储的响应里就没有 `Access-Control-Allow-Origin`，
 * 进缓存的正是这条无头响应；把它交给一个需要 CORS 的请求，浏览器就判成跨域失败——
 * 图片空白，而且会一直空白到该条目被淘汰、或者签名窗口滚动产生一条新 URL 为止。
 * 用户能看到的唯一"修法"是在开发者工具里勾上停用缓存。
 *
 * **原来的对策是反过来的：让所有请求都带 `crossOrigin="anonymous"`**，这样每个请求
 * 产出和接受的都是同一条带头的缓存条目。它成立的前提是"每一个请求都由我们发出"，
 * 而这个前提不成立：antd 的 `<Image>`（rc-image 的 `useStatus` → `isImageValid`）会用
 * `document.createElement('img')` 把同一个 `src` **再加载一遍**，只为判断要不要显示
 * `fallback`。那个探测 `<img>` 没有 `crossOrigin`，也没有任何配置项能给它加上。更糟的是
 * 它是即时发起的，而我们可见的那张图是 `loading="lazy"`——长列表里探测请求几乎总是
 * 先把无头响应写进缓存，可见的那张图随后就撞在上面。表现就是"部分预览图打不开，
 * 只有停用缓存才正常"。
 *
 * 所以不变量反过来定：**预览一律以 no-cors 模式请求**。这是第三方代码的默认行为，
 * 也是 `<img>` 无法拒绝的行为，因此任何我们看不见的请求都不会再和我们模式不一致。
 * 唯一真正需要像素的消费者——图片详情页的直方图——改成**绕开 HTTP 缓存**
 * （`src/useLocalImageUrl.ts` 里的 `cache: 'reload'`），它于是必然走网络、必然带
 * `Origin`、必然拿回 CORS 头；而它写回缓存的那条带头响应，对所有 no-cors 读者同样可用。
 *
 * 代价：从图库进详情页会重新下载一次预览（一张图，通常 20 KB 上下），不再复用网格
 * 里已经下好的那一份。换来的是缩略图不再依赖 Bucket 的 GET CORS，也不再依赖"站内
 * 每一处、以及所有第三方组件都记得带 crossOrigin"这条守不住的约定。
 *
 * 这个常量保留下来（而不是直接删掉 `crossOrigin`）是为了让这条决定有一个具名的落点：
 * `tests/previewCorsMode.test.ts` 钉住它，改动预览的 CORS 模式只会有这一个入口。
 */
export const PREVIEW_CROSS_ORIGIN: ImgHTMLAttributes<HTMLImageElement>['crossOrigin'] = undefined

/**
 * 取一条新的签名预览地址。预览图加载失败时用它再试一次，语义和"只重试一次"的
 * 理由见 `src/previewRetry.ts`。
 *
 * 返回 `undefined`/`null` 表示这张图确实取不回来了（被删、无权查看、后端出错）。
 */
export type PreviewUrlRefresher = () => Promise<string | null | undefined>
