import { GALAXY_GRID, inBounds, neighbors8, starAtSector, WARP_ENERGY, sectorId } from "../sim/universe/galaxyModel";
import type { GameState } from "../sim/types";

/**
 * 2D panning galaxy map: neighbor sectors, click to set warp target. Render only; sim updates in main.
 */
export class GalaxyMap2D {
  readonly domElement: HTMLCanvasElement;
  private offX = 0;
  private offY = 0;
  private drag = false;
  private last = { x: 0, y: 0 };
  /** (gx, gy) to warp, set by pick */
  pendingWarp: { x: number; y: number } | null = null;
  onWarpClick: (() => void) | null = null;
  /** M2: Shift+click a sector — set nav only (no warp). */
  onNavTarget: ((x: number, y: number) => void) | null = null;

  constructor(canvas: HTMLCanvasElement) {
    this.domElement = canvas;
    this.domElement.addEventListener("pointerdown", (e) => {
      this.drag = true;
      this.last = { x: e.clientX, y: e.clientY };
    });
    this.domElement.addEventListener("pointerup", (e) => {
      if (!this.drag) return;
      this.drag = false;
      if (Math.hypot(e.clientX - this.last.x, e.clientY - this.last.y) < 4) {
        this.pick(e.offsetX, e.offsetY, e.shiftKey);
      }
    });
    this.domElement.addEventListener("pointermove", (e) => {
      if (this.drag && e.buttons) {
        this.offX += e.clientX - this.last.x;
        this.offY += e.clientY - this.last.y;
        this.last = { x: e.clientX, y: e.clientY };
        if (this._lastState) this.draw(this._lastState);
      }
    });
  }

  private _lastState: GameState | null = null;
  private cell = 22;

  private pick(ox: number, oy: number, shiftKey: boolean): void {
    if (!this._lastState) return;
    const w = this.domElement.width;
    const h = this.domElement.height;
    const cx = w / 2 + this.offX;
    const cy = h / 2 + this.offY;
    const gx0 = this._lastState.universe.galaxyX;
    const gy0 = this._lastState.universe.galaxyY;
    for (const { x, y } of neighbors8(gx0, gy0)) {
      if (!inBounds(x, y)) continue;
      const dx = x - gx0;
      const dy = y - gy0;
      const px = cx + dx * this.cell;
      const py = cy - dy * this.cell;
      if (ox >= px - 8 && ox <= px + 8 && oy >= py - 8 && oy <= py + 8) {
        if (shiftKey) {
          this.onNavTarget?.(x, y);
        } else {
          this.pendingWarp = { x, y };
          this.onWarpClick?.();
        }
        return;
      }
    }
  }

  draw(state: GameState): void {
    this._lastState = state;
    const ctx = this.domElement.getContext("2d");
    if (!ctx) return;
    const w = this.domElement.clientWidth;
    const h = this.domElement.clientHeight;
    this.domElement.width = w;
    this.domElement.height = h;
    ctx.fillStyle = "#04060c";
    ctx.fillRect(0, 0, w, h);
    const cx = w / 2 + this.offX;
    const cy = h / 2 + this.offY;
    const gx0 = state.universe.galaxyX;
    const gy0 = state.universe.galaxyY;
    ctx.font = "10px system-ui";
    for (const { x, y } of neighbors8(gx0, gy0)) {
      if (!inBounds(x, y)) continue;
      const dx = x - gx0;
      const dy = y - gy0;
      const px = cx + dx * this.cell;
      const py = cy - dy * this.cell;
      const { systemSeed, label } = starAtSector(x, y);
      const d = state.universe.discovered.includes(sectorId(x, y));
      const isHere = x === gx0 && y === gy0;
      const brightness = 0.3 + (systemSeed % 100) * 0.005;
      ctx.beginPath();
      ctx.fillStyle = isHere
        ? `rgb(${120 + (systemSeed & 0xff) * 0.3},${180 - brightness * 20},${220})`
        : d
          ? `rgb(${50 + brightness * 40},${80 + brightness * 30},${140 + brightness * 20})`
          : `rgb(${20 + brightness * 15},${25 + brightness * 20},${40 + brightness * 20})`;
      if (d || isHere) {
        const r = isHere ? 5 : 3.5;
        ctx.arc(px, py, r, 0, Math.PI * 2);
        ctx.fill();
      } else {
        ctx.fillRect(px - 1.5, py - 1.5, 3, 3);
      }
      ctx.fillStyle = "#88a0c0";
      if (d || isHere) {
        ctx.fillText(label, px - 20, py - 8);
        ctx.fillText(`(${x},${y})`, px - 14, py + 14);
      }
    }
    ctx.fillStyle = "#dde8ff";
    ctx.font = "12px system-ui";
    ctx.fillText(
      `FTL: ${state.universe.ftlEnergy}   Warp cost: ${WARP_ENERGY}   Grid ${GALAXY_GRID}×${GALAXY_GRID}   Current (${gx0},${gy0})`,
      12,
      20
    );
  }
}
