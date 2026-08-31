/**
 * Every element that renders a signed preview URL must set this.
 *
 * The browser's HTTP cache is keyed by URL alone — it does not record which CORS
 * mode produced an entry. A plain `<img>` request is `no-cors` and carries no
 * `Origin`, so the object store answers it without `Access-Control-Allow-Origin`;
 * that header-less response is what lands in the cache. The photo detail page then
 * asks for the *same* URL with `fetch(..., { mode: 'cors' })` (it needs pixel data
 * for the exposure histogram, which a tainted canvas cannot give), the cache serves
 * it that stored response, and the browser rejects it as a CORS failure — the
 * preview and the histogram both go blank with a CORS error in devtools.
 *
 * This stayed hidden while preview URLs were signed per request: every render got a
 * new URL, so nothing was ever reused from the cache. Quantising the signature to a
 * window and sending `Cache-Control` made the entries real and the collision with
 * them real too.
 *
 * Requesting previews as `anonymous` everywhere keeps a single cache entry that
 * carries the header, so the gallery `<img>` and the detail page's fetch share one
 * download instead of poisoning each other. It does mean an image now needs bucket
 * GET CORS to render at all; that is already true of the detail page, and
 * `AliyunObjectStorageService.initialize` provisions the rule (`OSS_CORS_ALLOWED_ORIGINS`).
 */
export const PREVIEW_CROSS_ORIGIN = 'anonymous'
