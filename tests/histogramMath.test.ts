import assert from 'node:assert/strict'
import test from 'node:test'
import { calculateExposureHistogram } from '../src/histogramMath.ts'

test('calculates luminance distribution and clipping ratios', () => {
  const histogram = calculateExposureHistogram(new Uint8ClampedArray([
    0, 0, 0, 255,
    255, 255, 255, 255,
  ]))

  assert.equal(histogram.pixelCount, 2)
  assert.equal(histogram.red[0], 1)
  assert.equal(histogram.red[255], 1)
  assert.equal(histogram.luminance[0], 1)
  assert.equal(histogram.luminance[255], 1)
  assert.equal(histogram.meanLuminance, 127.5)
  assert.equal(histogram.shadowPercent, 50)
  assert.equal(histogram.highlightPercent, 50)
})

test('uses perceptual luminance and ignores fully transparent pixels', () => {
  const histogram = calculateExposureHistogram(new Uint8ClampedArray([
    255, 0, 0, 255,
    0, 255, 0, 255,
    0, 0, 255, 255,
    255, 255, 255, 0,
  ]))

  assert.equal(histogram.pixelCount, 3)
  assert.equal(histogram.luminance[54], 1)
  assert.equal(histogram.luminance[182], 1)
  assert.equal(histogram.luminance[18], 1)
  assert.equal(histogram.red[255], 1)
  assert.equal(histogram.green[255], 1)
  assert.equal(histogram.blue[255], 1)
  assert.equal(histogram.shadowPercent, 0)
  assert.equal(histogram.highlightPercent, 0)
})
