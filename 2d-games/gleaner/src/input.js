const MAP = {
  KeyW: 'up', ArrowUp: 'up',
  KeyS: 'down', ArrowDown: 'down',
  KeyA: 'left', ArrowLeft: 'left',
  KeyD: 'right', ArrowRight: 'right',
  Space: 'cut',
  KeyR: 'repair',
};

export class Input {
  constructor(canvas) {
    this.keys = new Set();
    this.mx = 0; this.my = 0;
    this.clicked = false;
    this.rightClicked = false;
    this.pressed = new Set();   // edge-triggered, cleared each frame
    this.canvas = canvas;

    addEventListener('keydown', (e) => {
      if (e.repeat) return;
      this.keys.add(e.code);
      this.pressed.add(e.code);
      if (MAP[e.code] || /^Digit[1-9]$/.test(e.code)) e.preventDefault();
    });
    addEventListener('keyup', (e) => this.keys.delete(e.code));
    addEventListener('blur', () => this.keys.clear());

    canvas.addEventListener('mousemove', (e) => {
      const r = canvas.getBoundingClientRect();
      this.mx = e.clientX - r.left;
      this.my = e.clientY - r.top;
    });
    canvas.addEventListener('mousedown', (e) => {
      if (e.button === 0) this.clicked = true;
      if (e.button === 2) this.rightClicked = true;
    });
    canvas.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  down(action) {
    for (const code in MAP) if (MAP[code] === action && this.keys.has(code)) return true;
    return false;
  }

  hit(code) { return this.pressed.has(code); }

  /** Digit pressed this frame, or -1. */
  digit() {
    for (let i = 1; i <= 9; i++) if (this.pressed.has('Digit' + i)) return i - 1;
    return -1;
  }

  endFrame() {
    this.pressed.clear();
    this.clicked = false;
    this.rightClicked = false;
  }
}
