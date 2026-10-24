import * as THREE from "three";
import { galacticNavUnit } from "../sim/universe/galacticNavDirection";

/**
 * M2: unit vector in world space pointing “toward” a neighbor sector on the galactic map,
 * for HUD / on-sky hints. Not an ephemeris — only needs to feel consistent and not planet-locked.
 */
export function worldDirectionToSector(ux: number, uy: number, tx: number, ty: number): THREE.Vector3 {
  const v = galacticNavUnit(ux, uy, tx, ty);
  return new THREE.Vector3(v.x, v.y, v.z);
}
