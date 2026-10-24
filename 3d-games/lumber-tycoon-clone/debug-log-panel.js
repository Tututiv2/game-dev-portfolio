/**
 * Bottom-right "Debug log" button + panel that mirrors console.log / warn / error.
 * Exposes {@link globalThis.LT_appendDebugLog} for modules that load before user opens DevTools.
 */
function formatArgs(args) {
  return args.map((a) => {
    if (a instanceof Error) return a.stack || a.message;
    if (typeof a === 'object') {
      try {
        return JSON.stringify(a);
      } catch {
        return String(a);
      }
    }
    return String(a);
  }).join(' ');
}

function install() {
  if (typeof document === 'undefined') return;
  if (globalThis.__LT_DEBUG_LOG_INSTALLED__) return;
  globalThis.__LT_DEBUG_LOG_INSTALLED__ = true;

  const btn = document.createElement('button');
  btn.id = 'lt-debug-log-toggle';
  btn.type = 'button';
  btn.textContent = 'Debug log';
  btn.title = 'Show or hide live console mirror (also logs to F12 console)';
  btn.setAttribute('aria-expanded', 'false');
  btn.setAttribute('aria-controls', 'lt-debug-log-panel');
  Object.assign(btn.style, {
    position: 'fixed',
    bottom: '12px',
    left: '12px',
    zIndex: '10000',
    padding: '8px 14px',
    borderRadius: '8px',
    border: '1px solid rgba(255,255,255,0.28)',
    background: 'rgba(22,24,32,0.96)',
    color: '#e8eaed',
    fontSize: '13px',
    fontFamily: 'system-ui, sans-serif',
    cursor: 'pointer',
    boxShadow: '0 4px 18px rgba(0,0,0,0.5)',
  });

  const panel = document.createElement('div');
  panel.id = 'lt-debug-log-panel';
  panel.setAttribute('role', 'region');
  panel.setAttribute('aria-label', 'Debug log');
  panel.hidden = true;
  Object.assign(panel.style, {
    position: 'fixed',
    top: '72px',
    left: '12px',
    width: 'min(440px, 94vw)',
    maxHeight: '48vh',
    zIndex: '9999',
    background: 'rgba(8,10,14,0.94)',
    border: '1px solid rgba(120,180,255,0.25)',
    borderRadius: '10px',
    fontFamily: 'ui-monospace, Consolas, "Cascadia Code", monospace',
    fontSize: '11px',
    lineHeight: '1.35',
    color: '#c8e0ff',
    display: 'flex',
    flexDirection: 'column',
    boxShadow: '0 10px 40px rgba(0,0,0,0.55)',
  });

  const header = document.createElement('div');
  header.style.cssText =
    'padding:8px 10px;border-bottom:1px solid rgba(255,255,255,0.1);display:flex;justify-content:space-between;align-items:center;gap:8px;flex-shrink:0;';
  const title = document.createElement('span');
  title.textContent = 'Console (live)';
  title.style.fontWeight = '600';
  title.style.color = '#e8f0ff';

  const clearBtn = document.createElement('button');
  clearBtn.type = 'button';
  clearBtn.textContent = 'Clear';
  clearBtn.style.cssText =
    'padding:4px 10px;font-size:11px;cursor:pointer;border-radius:6px;border:1px solid #555;background:#2a2e38;color:#eee;';

  const pre = document.createElement('div');
  pre.id = 'lt-debug-log-pre';
  pre.style.cssText = 'margin:0;padding:8px 10px;overflow:auto;flex:1;min-height:80px;';

  const maxLines = 500;

  function appendLine(text, level = 'log') {
    const line = document.createElement('div');
    const t = new Date().toLocaleTimeString();
    line.textContent = `[${t}] ${text}`;
    if (level === 'warn') line.style.color = '#fc6';
    if (level === 'error') line.style.color = '#faa';
    pre.appendChild(line);
    while (pre.childNodes.length > maxLines) pre.removeChild(pre.firstChild);
    pre.scrollTop = pre.scrollHeight;
  }

  globalThis.LT_appendDebugLog = (msg, level = 'log') => {
    appendLine(String(msg), level);
  };

  const origLog = console.log.bind(console);
  const origWarn = console.warn.bind(console);
  const origErr = console.error.bind(console);

  console.log = (...args) => {
    origLog(...args);
    appendLine(formatArgs(args), 'log');
  };
  console.warn = (...args) => {
    origWarn(...args);
    appendLine(formatArgs(args), 'warn');
  };
  console.error = (...args) => {
    origErr(...args);
    appendLine(formatArgs(args), 'error');
  };

  clearBtn.addEventListener('click', () => {
    pre.innerHTML = '';
  });

  btn.addEventListener('click', () => {
    const open = panel.hidden;
    panel.hidden = !open;
    btn.setAttribute('aria-expanded', String(open));
  });

  header.appendChild(title);
  header.appendChild(clearBtn);
  panel.appendChild(header);
  panel.appendChild(pre);
  document.body.appendChild(btn);
  document.body.appendChild(panel);

  appendLine('Debug log ready. Click this panel’s title area — use F12 for full DevTools.', 'log');
}

install();
