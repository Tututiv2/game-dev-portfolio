# Shadow Open Questions (Pre-Implementation)

These are the decisions to lock before or during Week 1. Each item includes a recommendation to reduce decision friction.

## 1) Frontend App Framework

- **Question:** Next.js App Router or React SPA (Vite)?
- **Impact:** routing model, deployment strategy, server/client boundaries
- **Recommendation:** Next.js for long-term integrated product features; Vite React if you want maximum MVP speed with minimal framework overhead.
- **Decision owner:** Product + lead dev
- **Target date:** Day 1

## 2) Backend Framework

- **Question:** FastAPI or Django?
- **Impact:** API velocity, async job integration, admin tooling
- **Recommendation:** FastAPI for MVP speed and clean API contracts.
- **Decision owner:** backend lead
- **Target date:** Day 1

## 3) Queue Strategy

- **Question:** Redis queue now, or lightweight DB polling worker first?
- **Impact:** reliability and complexity of async processing
- **Recommendation:** Redis-backed queue for anything beyond local-only demo.
- **Decision owner:** backend lead
- **Target date:** Day 2

## 4) Initial AI Providers

- **Question:** Which T2I provider and which I2V provider for MVP?
- **Impact:** cost, quality, latency, integration effort
- **Recommendation:** pick one provider each with best docs and predictable billing; optimize quality in Phase 2.5.
- **Decision owner:** product + backend
- **Target date:** Day 2

## 5) Asset Storage

- **Question:** local disk only for MVP, or S3-compatible from day one?
- **Impact:** portability, deployment readiness
- **Recommendation:** abstract storage interface immediately; local disk in dev, S3-compatible in staging/prod.
- **Decision owner:** backend/devops
- **Target date:** Day 2

## 6) Auth Scope

- **Question:** single-user mode first or full account system now?
- **Impact:** timeline and schema complexity
- **Recommendation:** single-user mode for MVP, keep schema auth-ready.
- **Decision owner:** product
- **Target date:** Day 1

## 7) Masking Strategy for First Milestone

- **Question:** automatic CorridorKey first or manual chroma first?
- **Impact:** algorithm complexity and UI work
- **Recommendation:** manual chroma first for deterministic behavior and faster ship; add automatic mode after baseline.
- **Decision owner:** graphics pipeline lead
- **Target date:** Day 7

## 8) Frame Extraction and Processing Stack

- **Question:** OpenCV only, ffmpeg + PIL, or hybrid?
- **Impact:** quality, performance, implementation complexity
- **Recommendation:** ffmpeg for extraction + OpenCV for masking/alignment operations.
- **Decision owner:** backend lead
- **Target date:** Day 7

## 9) Canvas Rendering Library

- **Question:** raw HTML5 canvas, Konva, or Pixi?
- **Impact:** editor speed and maintainability
- **Recommendation:** raw canvas for MVP editor controls, evaluate Pixi if complexity grows.
- **Decision owner:** frontend lead
- **Target date:** Day 10

## 10) Export Packing Rules

- **Question:** horizontal-only sheets in MVP, or selectable grid layouts now?
- **Impact:** implementation scope
- **Recommendation:** horizontal-only for MVP plus metadata for future layout support.
- **Decision owner:** graphics pipeline lead
- **Target date:** Day 14

## 11) Code Snippet Targets

- **Question:** vanilla Canvas snippet only, or Phaser helper too?
- **Impact:** integrator value and support surface
- **Recommendation:** vanilla snippet first, Phaser helper as P1.
- **Decision owner:** product
- **Target date:** Day 20

## 12) Realtime Update Mechanism

- **Question:** polling only or websockets/SSE for job updates?
- **Impact:** complexity and UX responsiveness
- **Recommendation:** polling for MVP, websocket/SSE as post-MVP optimization.
- **Decision owner:** frontend + backend
- **Target date:** Day 3

---

## Known Risks

- Provider API instability or model deprecation.
- Unexpected generation latency spikes.
- Chroma key artifacts on non-uniform backgrounds.
- Alignment drift for large intra-frame movement.
- Storage growth costs if failed/intermediate outputs are never cleaned up.

## Mitigations

- Provider adapter abstraction and fallback options.
- Timeouts + retries + user-visible job states.
- Frame-by-frame selection and manual override controls.
- Retention policy for temporary assets.

## Final Pre-Build Decision Gate

Before writing implementation code, lock these six:
- frontend framework
- backend framework
- queue strategy
- first T2I provider
- first I2V provider
- first masking mode

If these are decided, implementation can start immediately with low churn.
