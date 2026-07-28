export interface ExposureHistogramData {
  red: number[]
  green: number[]
  blue: number[]
  luminance: number[]
  pixelCount: number
  meanLuminance: number
  shadowPercent: number
  highlightPercent: number
}

export function calculateExposureHistogram(pixels: Uint8ClampedArray): ExposureHistogramData {
  const red = Array<number>(256).fill(0)
  const green = Array<number>(256).fill(0)
  const blue = Array<number>(256).fill(0)
  const luminance = Array<number>(256).fill(0)
  let pixelCount = 0
  let luminanceTotal = 0
  let shadows = 0
  let highlights = 0

  for (let index = 0; index + 3 < pixels.length; index += 4) {
    if (pixels[index + 3] === 0) continue

    const redValue = pixels[index]
    const greenValue = pixels[index + 1]
    const blueValue = pixels[index + 2]
    const luminanceValue = Math.round(0.2126 * redValue + 0.7152 * greenValue + 0.0722 * blueValue)

    red[redValue] += 1
    green[greenValue] += 1
    blue[blueValue] += 1
    luminance[luminanceValue] += 1
    luminanceTotal += luminanceValue
    pixelCount += 1
    if (luminanceValue <= 5) shadows += 1
    if (luminanceValue >= 250) highlights += 1
  }

  return {
    red,
    green,
    blue,
    luminance,
    pixelCount,
    meanLuminance: pixelCount ? luminanceTotal / pixelCount : 0,
    shadowPercent: pixelCount ? shadows / pixelCount * 100 : 0,
    highlightPercent: pixelCount ? highlights / pixelCount * 100 : 0,
  }
}
