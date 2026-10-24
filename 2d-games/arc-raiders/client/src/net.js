export class NetClient {
  constructor({ wsUrl, roomId = 'default' } = {}) {
    this.wsUrl = wsUrl || 'ws://localhost:8080';
    this.roomId = roomId;

    this.socket = null;
    this.connected = false;

    this.playerId = null;
    this.onSnapshot = () => {};
    this.onEvent = () => {};

    this.seq = 1;
    this.lastSendAt = 0;
  }

  connect() {
    this.socket = new WebSocket(this.wsUrl);
    this.socket.addEventListener('open', () => {
      this.connected = true;
      this._sendJoin();
    });
    this.socket.addEventListener('close', () => {
      this.connected = false;
    });
    this.socket.addEventListener('message', (ev) => {
      let msg;
      try {
        msg = JSON.parse(ev.data);
      } catch {
        return;
      }
      this._handleMessage(msg);
    });
  }

  _handleMessage(msg) {
    if (!msg || !msg.type) return;
    if (msg.type === 'welcome') {
      this.playerId = msg.playerId;
      return;
    }
    if (msg.type === 'snapshot') {
      this.onSnapshot(msg);
      return;
    }
    if (msg.type === 'event') {
      this.onEvent(msg);
      return;
    }
  }

  _sendJoin() {
    const name = `Player_${Math.floor(Math.random() * 10000)}`;
    this.socket.send(
      JSON.stringify({
        type: 'joinRoom',
        roomId: this.roomId,
        playerName: name,
        loadout: {
          weaponId: 'tempest_placeholder',
        },
      }),
    );
  }

  setCallbacks({ onSnapshot, onEvent } = {}) {
    if (onSnapshot) this.onSnapshot = onSnapshot;
    if (onEvent) this.onEvent = onEvent;
  }

  sendInput({ tickId, moveVec, aimDir, actions } = {}) {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return;

    // Client prediction will be layered in later; for net-protocol/movement scaffolding,
    // we still send inputs at a stable cadence.
    const seq = this.seq++;
    this.socket.send(
      JSON.stringify({
        type: 'input',
        tickId: tickId ?? 0,
        seq,
        moveVec: moveVec ?? { x: 0, z: 0 },
        aimDir: aimDir ?? { x: 0, y: 0, z: 1 },
        actions: actions ?? [],
      }),
    );
    return seq;
  }
}

