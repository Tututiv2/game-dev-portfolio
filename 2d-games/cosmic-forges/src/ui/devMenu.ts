import { devSettings, resetDevSettings, type DevSettings } from "../render/devSettings";

function row(
  label: string,
  min: number,
  max: number,
  step: number,
  get: () => number,
  set: (v: number) => void,
  valueId: string,
  nameAttr: string
): HTMLElement {
  const wrap = document.createElement("div");
  wrap.className = "dev-row";
  const lab = document.createElement("label");
  lab.htmlFor = valueId;
  lab.textContent = label;
  const inp = document.createElement("input");
  inp.id = valueId;
  inp.name = nameAttr;
  inp.type = "range";
  inp.min = String(min);
  inp.max = String(max);
  inp.step = String(step);
  inp.value = String(get());
  const out = document.createElement("output");
  out.htmlFor = valueId;
  out.textContent = inp.value;
  inp.addEventListener("input", () => {
    const v = parseFloat(inp.value);
    set(v);
    out.textContent = v.toFixed(step < 0.01 ? 4 : step < 0.1 ? 3 : 2);
  });
  wrap.appendChild(lab);
  wrap.appendChild(inp);
  wrap.appendChild(out);
  return wrap;
}

let devId = 0;
function id(): string {
  return `dev-${++devId}`;
}

/**
 * Dev menu: live sliders for skydome stars and system-map LOD. Toggle with the Dev button.
 */
export function mountDevMenu(container: HTMLElement): { toggle: () => void; open: () => void; close: () => void } {
  const panel = document.createElement("aside");
  panel.id = "dev-menu-panel";
  panel.className = "dev-menu";
  panel.hidden = true;
  const title = document.createElement("h3");
  title.textContent = "Dev — stars & bodies";
  panel.appendChild(title);

  const sk = () => devSettings.sky;
  const so = () => devSettings.solar;

  const bind = <K extends keyof DevSettings["sky"]>(key: K, min: number, max: number, step: number, lab: string) => {
    const i = id();
    return row(
      lab,
      min,
      max,
      step,
      () => sk()[key] as number,
      (v) => {
        (sk()[key] as number) = v;
      },
      i,
      `sky.${String(key)}`
    );
  };

  const bindS = (key: keyof DevSettings["solar"], min: number, max: number, step: number, lab: string) => {
    const i = id();
    return row(
      lab,
      min,
      max,
      step,
      () => so()[key] as number,
      (v) => {
        (so()[key] as number) = v;
      },
      i,
      `solar.${String(key)}`
    );
  };

  const sec = (t: string) => {
    const h = document.createElement("h4");
    h.textContent = t;
    panel.appendChild(h);
  };

  sec("Skydome (orbit distance = camera |origin|)");
  panel.appendChild(bind("starSpacing", 80, 520, 5, "Star cell density"));
  panel.appendChild(bind("starThreshold", 0.97, 0.999, 0.0005, "Star visibility threshold"));
  panel.appendChild(bind("twinkle", 0, 2.5, 0.05, "Twinkle"));
  panel.appendChild(bind("starColorWarmth", 0, 1, 0.02, "Color warmth"));
  panel.appendChild(bind("milky", 0, 2, 0.05, "Milky band"));
  panel.appendChild(bind("lodOrbitNear", 8, 60, 1, "LOD: full detail below ~"));
  panel.appendChild(bind("lodOrbitMid", 20, 90, 1, "LOD: mid band until ~"));
  panel.appendChild(bind("lodOrbitFar", 40, 160, 1, "LOD: point stars beyond ~"));
  sec("System map (camera vs body) — 3 bands");
  panel.appendChild(bindS("lodFullMax", 2, 40, 0.5, "Full mesh if distance ≤"));
  panel.appendChild(bindS("lodMidMax", 5, 80, 0.5, "Low poly if between full & this"));
  panel.appendChild(bindS("dotSize", 0.04, 0.22, 0.01, "Far “dot” size"));
  const hint = document.createElement("p");
  hint.className = "dev-hint";
  hint.textContent =
    "Orbit the surface camera (scroll out) to see skydome stars thin to points. In System map, zoom: far = dots, mid = low-poly, close = full.";
  panel.appendChild(hint);

  const res = document.createElement("button");
  res.type = "button";
  res.textContent = "Reset dev defaults";
  res.addEventListener("click", () => {
    resetDevSettings();
    const root = devSettings as unknown as Record<string, Record<string, number>>;
    panel.querySelectorAll<HTMLInputElement>('input[type="range"][name]').forEach((r) => {
      const [a, b] = r.name.split(".");
      const v = root[a]?.[b];
      if (v === undefined) return;
      r.value = String(v);
      r.dispatchEvent(new Event("input", { bubbles: true }));
    });
  });
  panel.appendChild(res);

  container.appendChild(panel);
  return {
    toggle: () => {
      panel.hidden = !panel.hidden;
    },
    open: () => {
      panel.hidden = false;
    },
    close: () => {
      panel.hidden = true;
    },
  };
}
