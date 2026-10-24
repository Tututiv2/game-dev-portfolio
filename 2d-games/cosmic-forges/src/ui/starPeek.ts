const LINE = "???  ·  ???  ·  ???";

const PINNED = "star-peek--pinned";
const AT_STAR = "star-peek--at-star";

export function setStarPeekVisible(root: HTMLElement, vis: boolean): void {
  root.hidden = !vis;
  if (!vis) {
    root.classList.remove(PINNED, AT_STAR);
  }
}

function formatDistLy(distLy: number): string {
  if (!Number.isFinite(distLy) || distLy <= 0) return "—";
  if (distLy < 0.01) return "<0.01";
  if (distLy < 10) {
    return distLy < 1 ? distLy.toFixed(3) : distLy.toFixed(2);
  }
  if (distLy < 200) return distLy.toFixed(1);
  if (distLy < 100_000) return String(Math.round(distLy));
  return distLy.toExponential(3);
}

export function updateStarPeekContent(
  root: HTMLElement,
  name: string,
  distLy: number,
  metaLine?: string
): void {
  const n = root.querySelector(".star-peek__name");
  const d = root.querySelector(".star-peek__dist");
  const m = root.querySelector(".star-peek__meta");
  if (n) n.textContent = name;
  if (d) {
    const label = formatDistLy(distLy);
    d.textContent = `Distance: ${label} ly`;
  }
  if (m) m.textContent = metaLine ?? LINE;
}

/**
 * - **cursor** — top-left of panel near the pointer (`transform: none` in CSS).
 * - **star** — `(left, top)` is the star’s screen point; panel sits above it (`star-peek--at-star` centers above).
 */
export function placeStarPeek(
  root: HTMLElement,
  left: number,
  top: number,
  mode: "cursor" | "star" = "cursor"
): void {
  root.style.left = `${left}px`;
  root.style.top = `${top}px`;
  root.classList.toggle(AT_STAR, mode === "star");
}

export function setStarPeekPinnedHighlight(root: HTMLElement, on: boolean): void {
  root.classList.toggle(PINNED, on);
}
