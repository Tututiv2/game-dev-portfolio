/* Live agent activity.
 *
 * Server-sent events rather than a websocket: the traffic is one-way, the
 * server is stdlib http.server, and an EventSource reconnects by itself when
 * the .bat gets restarted mid-session -- which it will, because rebuilding the
 * map restarts the server.
 */
export class Live {
  constructor({ onEvent, onHello, onStatus }) {
    this.onEvent = onEvent;
    this.onHello = onHello;
    this.onStatus = onStatus || (() => {});
    this.connected = false;
    this.connect();
  }

  connect() {
    try {
      this.es = new EventSource('/api/stream');
    } catch (err) {
      this.onStatus(false, String(err));
      return;
    }
    this.es.addEventListener('open', () => {
      this.connected = true;
      this.onStatus(true);
    });
    this.es.addEventListener('hello', e => {
      try { this.onHello(JSON.parse(e.data)); } catch (err) { /* ignore */ }
    });
    this.es.addEventListener('replay', e => {
      try { this.onEvent(JSON.parse(e.data), true); } catch (err) { /* ignore */ }
    });
    this.es.addEventListener('act', e => {
      try { this.onEvent(JSON.parse(e.data), false); } catch (err) { /* ignore */ }
    });
    this.es.addEventListener('error', () => {
      // EventSource retries on its own; just reflect the state in the HUD
      if (this.connected) { this.connected = false; this.onStatus(false); }
    });
  }

  static demo(body = {}) {
    return fetch('/api/demo', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(r => r.ok);
  }
}
