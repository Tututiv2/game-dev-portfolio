const { promises: fs } = require("node:fs");
const path = require("node:path");
const { PNG } = require("pngjs");
const pngToIcoModule = require("png-to-ico");
const pngToIco = typeof pngToIcoModule === "function" ? pngToIcoModule : pngToIcoModule.default;

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function mixColor(a, b, t) {
  return {
    r: Math.round(a.r + (b.r - a.r) * t),
    g: Math.round(a.g + (b.g - a.g) * t),
    b: Math.round(a.b + (b.b - a.b) * t),
  };
}

function drawRoundedRectMask(x, y, width, height, radius) {
  const left = x;
  const right = x + width;
  const top = y;
  const bottom = y + height;
  const r = Math.min(radius, width * 0.5, height * 0.5);
  return (px, py) => {
    if (px < left || px >= right || py < top || py >= bottom) {
      return false;
    }
    const innerX = clamp(px, left + r, right - r);
    const innerY = clamp(py, top + r, bottom - r);
    const dx = px - innerX;
    const dy = py - innerY;
    return dx * dx + dy * dy <= r * r;
  };
}

async function generate() {
  const size = 512;
  const png = new PNG({ width: size, height: size });
  const top = { r: 21, g: 40, b: 83 };
  const bottom = { r: 76, g: 29, b: 149 };
  const glow = { r: 40, g: 208, b: 255 };
  const core = { r: 196, g: 242, b: 255 };

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const index = (y * size + x) * 4;
      const t = y / (size - 1);
      const base = mixColor(top, bottom, t);
      const dx = (x - size * 0.25) / size;
      const dy = (y - size * 0.18) / size;
      const bloom = Math.max(0, 1 - Math.sqrt(dx * dx + dy * dy) * 2.6);
      png.data[index] = clamp(Math.round(base.r + glow.r * bloom * 0.25), 0, 255);
      png.data[index + 1] = clamp(Math.round(base.g + glow.g * bloom * 0.25), 0, 255);
      png.data[index + 2] = clamp(Math.round(base.b + glow.b * bloom * 0.25), 0, 255);
      png.data[index + 3] = 255;
    }
  }

  const cardMask = drawRoundedRectMask(92, 88, 328, 336, 72);
  for (let y = 88; y < 424; y += 1) {
    for (let x = 92; x < 420; x += 1) {
      if (!cardMask(x, y)) {
        continue;
      }
      const index = (y * size + x) * 4;
      const stripe = ((x + y) % 22) / 21;
      png.data[index] = clamp(png.data[index] + 14 + stripe * 10, 0, 255);
      png.data[index + 1] = clamp(png.data[index + 1] + 16 + stripe * 10, 0, 255);
      png.data[index + 2] = clamp(png.data[index + 2] + 20 + stripe * 12, 0, 255);
    }
  }

  const shadowMask = drawRoundedRectMask(110, 108, 292, 296, 60);
  for (let y = 96; y < 420; y += 1) {
    for (let x = 98; x < 414; x += 1) {
      if (!shadowMask(x, y)) {
        continue;
      }
      const index = (y * size + x) * 4;
      const pulse = Math.sin((x + y) * 0.05) * 0.5 + 0.5;
      png.data[index] = clamp(png.data[index] + 8 + pulse * 8, 0, 255);
      png.data[index + 1] = clamp(png.data[index + 1] + 10 + pulse * 8, 0, 255);
      png.data[index + 2] = clamp(png.data[index + 2] + 16 + pulse * 10, 0, 255);
    }
  }

  const glyphMask = drawRoundedRectMask(165, 140, 182, 236, 52);
  for (let y = 140; y < 376; y += 1) {
    for (let x = 165; x < 347; x += 1) {
      if (!glyphMask(x, y)) {
        continue;
      }
      const normalizedY = (y - 140) / 235;
      const cutout = x > 236 && x < 326 && y > 210 && y < 286;
      if (cutout) {
        continue;
      }
      const index = (y * size + x) * 4;
      const textMix = mixColor(core, glow, normalizedY * 0.35);
      png.data[index] = textMix.r;
      png.data[index + 1] = textMix.g;
      png.data[index + 2] = textMix.b;
      png.data[index + 3] = 255;
    }
  }

  const rootDir = path.join(__dirname, "..");
  const assetsDir = path.join(rootDir, "assets");
  await fs.mkdir(assetsDir, { recursive: true });

  const iconPngPath = path.join(assetsDir, "icon.png");
  const pngBuffer = PNG.sync.write(png);
  await fs.writeFile(iconPngPath, pngBuffer);

  const iconIcoBuffer = await pngToIco(iconPngPath);
  await fs.writeFile(path.join(assetsDir, "icon.ico"), iconIcoBuffer);
}

generate().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
