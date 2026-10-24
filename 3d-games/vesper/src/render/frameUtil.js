/* Small shared helper: every shader here works in its own object space, so the
   world-space sun direction has to be rotated in. Doing this once, in one
   place, avoids the class of bug where the terminator and the ring shadow
   disagree about where the star is. */
import * as T from 'three';

const _m3 = new T.Matrix3();

/** rotate a WORLD-space direction into `obj`'s local space */
export function toLocalDir(obj, worldDir, out) {
  obj.updateWorldMatrix(true, false);
  _m3.setFromMatrix4(obj.matrixWorld).invert();
  return out.copy(worldDir).applyMatrix3(_m3).normalize();
}
