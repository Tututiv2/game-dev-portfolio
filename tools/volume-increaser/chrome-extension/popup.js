const slider = document.getElementById("gain");
const val = document.getElementById("val");

function label() {
  const p = parseInt(slider.value, 10);
  val.textContent = p + "%";
}

function save() {
  const g = parseInt(slider.value, 10) / 100;
  chrome.storage.local.set({ gain: g });
}

chrome.storage.local.get({ gain: 1.0 }, (r) => {
  const n = typeof r.gain === "number" && r.gain >= 0 ? r.gain : 1.0;
  const pct = Math.min(1000, Math.max(0, Math.round(n * 100)));
  slider.value = String(pct);
  label();
});

slider.addEventListener("input", () => {
  label();
  save();
});
