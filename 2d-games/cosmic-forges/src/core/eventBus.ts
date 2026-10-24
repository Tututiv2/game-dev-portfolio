export type GameEvent =
  | { type: "wave"; wave: number }
  | { type: "spaceWave"; wave: number }
  | { type: "gameover" }
  | { type: "place_fail"; reason: "funds" | "block" | "tier" }
  | { type: "tech"; tier: number };

type Listener = (e: GameEvent) => void;

export class EventBus {
  private listeners: Listener[] = [];

  on(fn: Listener): () => void {
    this.listeners.push(fn);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== fn);
    };
  }

  emit(e: GameEvent): void {
    for (const l of this.listeners) l(e);
  }
}
