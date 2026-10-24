/**
 * Procedural color-map canvases for the prefab editor (no external assets).
 * @typedef {{ canvas: HTMLCanvasElement, repeat: [number, number] }} PresetCanvasResult
 */

const W = 256;
const H = 256;

function blankCanvas() {
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = H;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;
  return { canvas, ctx };
}

/** @param {string} id @returns {PresetCanvasResult | null} */
export function createPresetCanvas(id) {
  const base = blankCanvas();
  if (!base) return null;
  const { canvas, ctx } = base;

  switch (id) {
    case 'wood_fine': {
      const lg = ctx.createLinearGradient(0, 0, W, 0);
      lg.addColorStop(0, '#352218');
      lg.addColorStop(0.35, '#5c3d28');
      lg.addColorStop(0.65, '#4a3020');
      lg.addColorStop(1, '#382418');
      ctx.fillStyle = lg;
      ctx.fillRect(0, 0, W, H);
      for (let i = 0; i < 380; i++) {
        ctx.strokeStyle = `rgba(0,0,0,${0.025 + Math.random() * 0.09})`;
        ctx.lineWidth = 0.8 + Math.random() * 2.2;
        ctx.beginPath();
        const x = Math.random() * W;
        ctx.moveTo(x, 0);
        ctx.lineTo(x + (Math.random() - 0.5) * 10, H);
        ctx.stroke();
      }
      for (let i = 0; i < 140; i++) {
        ctx.fillStyle = `rgba(255,210,170,${0.04 + Math.random() * 0.07})`;
        ctx.fillRect(Math.random() * W, Math.random() * H, 1 + Math.random() * 24, 1);
      }
      return { canvas, repeat: [2, 2] };
    }
    case 'wood_light': {
      const g = ctx.createLinearGradient(0, 0, 0, H);
      g.addColorStop(0, '#c4a574');
      g.addColorStop(1, '#8b6f45');
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, W, H);
      for (let i = 0; i < 300; i++) {
        ctx.strokeStyle = `rgba(90,60,30,${0.04 + Math.random() * 0.08})`;
        ctx.beginPath();
        ctx.moveTo(Math.random() * W, 0);
        ctx.lineTo(Math.random() * W, H);
        ctx.stroke();
      }
      return { canvas, repeat: [2, 2] };
    }
    case 'wood_planks': {
      ctx.fillStyle = '#5a3d28';
      ctx.fillRect(0, 0, W, H);
      const plank = Math.floor(H / 5);
      for (let row = 0; row < 5; row++) {
        const y0 = row * plank;
        const g = ctx.createLinearGradient(0, y0, W, y0 + plank);
        g.addColorStop(0, '#4a3220');
        g.addColorStop(0.5, '#6d4a32');
        g.addColorStop(1, '#3d2818');
        ctx.fillStyle = g;
        ctx.fillRect(0, y0, W, plank - 2);
        ctx.strokeStyle = 'rgba(0,0,0,0.35)';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(0, y0 + plank - 2);
        ctx.lineTo(W, y0 + plank - 2);
        ctx.stroke();
      }
      for (let i = 0; i < 200; i++) {
        ctx.fillStyle = `rgba(0,0,0,${0.02 + Math.random() * 0.05})`;
        ctx.fillRect(Math.random() * W, Math.random() * H, 2, 20);
      }
      return { canvas, repeat: [1, 1] };
    }
    case 'metal_brushed': {
      const g = ctx.createLinearGradient(0, 0, 0, H);
      g.addColorStop(0, '#9aa5b0');
      g.addColorStop(0.5, '#c5ccd4');
      g.addColorStop(1, '#7a8490');
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, W, H);
      for (let y = 0; y < H; y += 2) {
        ctx.fillStyle = `rgba(255,255,255,${0.04 + (y % 7) * 0.012})`;
        ctx.fillRect(0, y, W, 1);
      }
      for (let i = 0; i < 600; i++) {
        ctx.fillStyle = `rgba(0,0,0,${0.02 + Math.random() * 0.04})`;
        ctx.fillRect(Math.random() * W, Math.random() * H, 20 + Math.random() * 40, 1);
      }
      return { canvas, repeat: [3, 2] };
    }
    case 'checker_gray': {
      const a = '#6a6f78';
      const b = '#4a5058';
      const n = 8;
      const cw = W / n;
      const ch = H / n;
      for (let i = 0; i < n; i++) {
        for (let j = 0; j < n; j++) {
          ctx.fillStyle = (i + j) % 2 === 0 ? a : b;
          ctx.fillRect(i * cw, j * ch, cw + 0.5, ch + 0.5);
        }
      }
      return { canvas, repeat: [2, 2] };
    }
    case 'stripes_teal': {
      ctx.fillStyle = '#1e3a4a';
      ctx.fillRect(0, 0, W, H);
      const stripe = 14;
      for (let x = 0; x < W + H; x += stripe * 2) {
        ctx.fillStyle = '#2a8a7a';
        ctx.fillRect(x, 0, stripe, H);
      }
      return { canvas, repeat: [2, 2] };
    }
    case 'stone_speckled': {
      ctx.fillStyle = '#6b6d72';
      ctx.fillRect(0, 0, W, H);
      for (let i = 0; i < 8000; i++) {
        const v = 40 + Math.random() * 80;
        ctx.fillStyle = `rgb(${v},${v - 5},${v - 8})`;
        ctx.fillRect((Math.random() * W) | 0, (Math.random() * H) | 0, 1, 1);
      }
      for (let i = 0; i < 12; i++) {
        ctx.strokeStyle = 'rgba(0,0,0,0.12)';
        ctx.lineWidth = 1 + Math.random() * 2;
        ctx.beginPath();
        ctx.moveTo(Math.random() * W, Math.random() * H);
        ctx.lineTo(Math.random() * W, Math.random() * H);
        ctx.stroke();
      }
      return { canvas, repeat: [2, 2] };
    }
    case 'honeycomb': {
      ctx.fillStyle = '#2a3038';
      ctx.fillRect(0, 0, W, H);
      ctx.strokeStyle = 'rgba(180, 200, 220, 0.35)';
      ctx.lineWidth = 2;
      const r = 18;
      const dx = r * 1.73;
      const dy = r * 1.5;
      for (let row = -1; row < 12; row++) {
        const oy = row * dy;
        const off = row % 2 ? dx / 2 : 0;
        for (let col = -1; col < 12; col++) {
          const ox = col * dx + off;
          ctx.beginPath();
          for (let k = 0; k < 6; k++) {
            const ang = (Math.PI / 3) * k - Math.PI / 6;
            const x = ox + r * Math.cos(ang);
            const y = oy + r * Math.sin(ang);
            if (k === 0) ctx.moveTo(x, y);
            else ctx.lineTo(x, y);
          }
          ctx.closePath();
          ctx.stroke();
        }
      }
      return { canvas, repeat: [2, 2] };
    }
    case 'leather': {
      ctx.fillStyle = '#4a3020';
      ctx.fillRect(0, 0, W, H);
      for (let i = 0; i < 5000; i++) {
        const x = Math.random() * W;
        const y = Math.random() * H;
        const s = 1 + Math.random() * 2;
        ctx.fillStyle = `rgba(0,0,0,${0.08 + Math.random() * 0.12})`;
        ctx.beginPath();
        ctx.arc(x, y, s, 0, Math.PI * 2);
        ctx.fill();
      }
      for (let i = 0; i < 2000; i++) {
        ctx.fillStyle = `rgba(255,200,160,${0.03 + Math.random() * 0.06})`;
        ctx.fillRect(Math.random() * W, Math.random() * H, 1, 1);
      }
      return { canvas, repeat: [3, 3] };
    }
    case 'rust_streaks': {
      const g = ctx.createLinearGradient(0, 0, W, H);
      g.addColorStop(0, '#6b3a28');
      g.addColorStop(0.4, '#8b5a30');
      g.addColorStop(0.7, '#4a3020');
      g.addColorStop(1, '#5c4030');
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, W, H);
      for (let i = 0; i < 40; i++) {
        ctx.strokeStyle = `rgba(40,20,10,${0.15 + Math.random() * 0.25})`;
        ctx.lineWidth = 3 + Math.random() * 8;
        ctx.beginPath();
        ctx.moveTo(Math.random() * W, 0);
        ctx.bezierCurveTo(
          Math.random() * W,
          H * 0.4,
          Math.random() * W,
          H * 0.6,
          Math.random() * W,
          H
        );
        ctx.stroke();
      }
      return { canvas, repeat: [2, 2] };
    }
    case 'diamond_plate': {
      ctx.fillStyle = '#3a4048';
      ctx.fillRect(0, 0, W, H);
      const step = 32;
      for (let y = 0; y < H + step; y += step) {
        for (let x = 0; x < W + step; x += step) {
          const cx = x + step / 2;
          const cy = y + step / 2;
          ctx.strokeStyle = 'rgba(0,0,0,0.45)';
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.moveTo(cx, cy - 10);
          ctx.lineTo(cx + 14, cy);
          ctx.lineTo(cx, cy + 10);
          ctx.lineTo(cx - 14, cy);
          ctx.closePath();
          ctx.stroke();
          ctx.strokeStyle = 'rgba(255,255,255,0.08)';
          ctx.lineWidth = 1;
          ctx.stroke();
        }
      }
      return { canvas, repeat: [2, 2] };
    }
    default:
      return null;
  }
}

/** Preset ids that appear in the editor dropdown (value → label). */
export const TEXTURE_PRESET_OPTIONS = [
  { id: 'wood_fine', label: 'Wood — fine grain' },
  { id: 'wood_light', label: 'Wood — light' },
  { id: 'wood_planks', label: 'Wood — planks' },
  { id: 'metal_brushed', label: 'Metal — brushed' },
  { id: 'checker_gray', label: 'Checker — gray' },
  { id: 'stripes_teal', label: 'Stripes — teal' },
  { id: 'stone_speckled', label: 'Stone — speckled' },
  { id: 'honeycomb', label: 'Pattern — honeycomb' },
  { id: 'leather', label: 'Leather — pebbled' },
  { id: 'rust_streaks', label: 'Rust — streaks' },
  { id: 'diamond_plate', label: 'Metal — diamond plate' },
];
