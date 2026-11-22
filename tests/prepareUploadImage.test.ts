import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  PROGRESSIVE_TRANSCODE_MIN_PIXELS,
  computeTranscodeDimensions,
  inspectJpegFrame,
  shouldTranscodeProgressiveJpeg,
} from '../src/prepareUploadImage.ts'

/** 构造仅含 SOI + 可选 APP1 + SOF 段的最小 JPEG 头部字节，用于单测纯字节解析。 */
function buildJpegHeader(options: {
  sofMarker: number
  width: number
  height: number
  sampling?: [number, number, number]
  app1Bytes?: number
}): Uint8Array {
  const bytes: number[] = [0xff, 0xd8]
  if (options.app1Bytes && options.app1Bytes > 0) {
    const length = options.app1Bytes + 2
    bytes.push(0xff, 0xe1, (length >> 8) & 0xff, length & 0xff)
    for (let i = 0; i < options.app1Bytes; i++) bytes.push(0x00)
  }
  const sampling = options.sampling ?? [0x22, 0x11, 0x11]
  // SOF length = 2 (len) + 1 (precision) + 2 (H) + 2 (W) + 1 (Nf=3) + 3*3 = 17
  bytes.push(
    0xff, options.sofMarker,
    0x00, 17,
    8,
    (options.height >> 8) & 0xff, options.height & 0xff,
    (options.width >> 8) & 0xff, options.width & 0xff,
    3,
    1, sampling[0], 0,
    2, sampling[1], 1,
    3, sampling[2], 1,
    0xff, 0xda, 0x00, 0x02,
  )
  return new Uint8Array(bytes)
}

describe('inspectJpegFrame & progressive upload transcode decision', () => {
  it('recognizes baseline SOF0 JPEG and skips transcode', () => {
    const header = buildJpegHeader({ sofMarker: 0xc0, width: 12000, height: 8000 })
    const info = inspectJpegFrame(header)
    assert.deepEqual(info, {
      progressive: false,
      width: 12000,
      height: 8000,
      unsubsampled: false,
    })
    assert.equal(shouldTranscodeProgressiveJpeg(info), false)
  })

  it('recognizes progressive SOF2 JPEG across APP1 metadata segments', () => {
    const header = buildJpegHeader({
      sofMarker: 0xc2,
      width: 11648,
      height: 8736,
      sampling: [0x22, 0x11, 0x11],
      app1Bytes: 256,
    })
    const info = inspectJpegFrame(header)
    assert.deepEqual(info, {
      progressive: true,
      width: 11648,
      height: 8736,
      unsubsampled: false,
    })
    assert.equal(shouldTranscodeProgressiveJpeg(info), true)
  })

  it('detects 4:4:4 unsubsampled progressive JPEG and skips small progressive files', () => {
    const small444 = buildJpegHeader({
      sofMarker: 0xc2,
      width: 6000,
      height: 4000,
      sampling: [0x11, 0x11, 0x11],
    })
    const info = inspectJpegFrame(small444)
    assert.ok(info)
    assert.equal(info.progressive, true)
    assert.equal(info.unsubsampled, true)
    assert.equal(info.width * info.height < PROGRESSIVE_TRANSCODE_MIN_PIXELS, true)
    assert.equal(shouldTranscodeProgressiveJpeg(info), false)
  })

  it('returns null for non-JPEG buffers', () => {
    const pngHeader = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
    assert.equal(inspectJpegFrame(pngHeader), null)
    assert.equal(shouldTranscodeProgressiveJpeg(null), false)
  })

  it('computes bounded transcode dimensions without upscaling', () => {
    assert.deepEqual(computeTranscodeDimensions(8000, 6000, 10000), { width: 8000, height: 6000 })
    assert.deepEqual(computeTranscodeDimensions(12000, 9000, 10000), { width: 10000, height: 7500 })
    assert.deepEqual(computeTranscodeDimensions(9000, 12000, 10000), { width: 7500, height: 10000 })
  })
})
