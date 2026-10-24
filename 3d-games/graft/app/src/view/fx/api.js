// COSMIC EVENTS — the render side of the contract.
//
// One FX module per event id. It owns a three.js Group and nothing else. It may
// read the world; it may NEVER change it. All the state that matters lives in
// the event's own `ev.data`, which the sim owns and saves.
//
// An FX module exports:
//
//   export const id = 'blackhole';
//
//   export function build(ctx) {
//     // ctx = { THREE, scene, sky, root, P, C, camera }
//     //   root   a Group already added to the scene, in WORLD space, centred on
//     //          the planet's centre. Put ground-level things here.
//     //   sky    a Group that is NOT fogged and is drawn far away. Put anything
//     //          that should hang in the sky here (radius ~1200 is the star
//     //          field; keep sky objects between 300 and 1100 from the centre).
//     // return anything you want handed back to update()/dispose() as `self`
//     return { mesh, ... };
//   }
//
//   export function update(self, ev, w, dt, ctx) { }   // every frame while alive
//   export function dispose(self, ctx) { }             // tidy up; remove meshes
//
// RULES, all of them learned the hard way in this portfolio:
//  - Nothing allocates per frame. Build geometry and materials in build().
//  - A glow needs a FALLOFF. An additive sphere at constant opacity renders as
//    a flat disc. Use a radial-gradient sprite (ctx.glowTexture() gives you one).
//  - Anything in the sky must be `fog: false` or it will be erased.
//  - Never transition a value that carries INFORMATION. A backgrounded tab
//    parks CSS/threejs transitions at their start value.
//  - Prefer InstancedMesh for anything you make more than ~30 of.

export const CONTRACT = 'graft-fx-1';
