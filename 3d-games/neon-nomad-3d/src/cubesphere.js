// Cube-sphere mapping.
//
// Six square faces, each with its own (u,v) in [0,1], projected onto the ball.
// This is the whole reason the project is built this way: a sphere has no
// natural grid, but a cube does — so when towers need to snap to cells that
// wrap all the way around the planet, the grid is already here and it is just
// (face, u, v). No retrofit later.
//
// The u/v -> cube step uses a tangent warp. Plain normalisation bunches cells
// up at the face corners; tan() spreads them nearly evenly AND inverts exactly
// (atan), which is what lets us go from a world position back to a grid cell.

const QUARTER_PI = Math.PI / 4;

// face -> (a, b) in [-1,1] -> cube point in [-1,1]^3
export const FACES = [
  { name: '+X', cube: (a, b, o) => { o.x = 1;  o.y = b;  o.z = -a; } },
  { name: '-X', cube: (a, b, o) => { o.x = -1; o.y = b;  o.z = a;  } },
  { name: '+Y', cube: (a, b, o) => { o.x = a;  o.y = 1;  o.z = -b; } },
  { name: '-Y', cube: (a, b, o) => { o.x = a;  o.y = -1; o.z = b;  } },
  { name: '+Z', cube: (a, b, o) => { o.x = a;  o.y = b;  o.z = 1;  } },
  { name: '-Z', cube: (a, b, o) => { o.x = -a; o.y = b;  o.z = -1; } },
];

// Some of the six mappings are left-handed, which would emit inside-out
// triangles. Rather than hand-tabulating winding per face (the exact kind of
// hand-typed table that goes wrong silently), measure it once at load: build
// the face normal from three sample points and see if it faces outward.
export const FACE_FLIP = FACES.map((f) => {
  const p00 = uvToUnit(f, 0.25, 0.25, {});
  const p10 = uvToUnit(f, 0.75, 0.25, {});
  const p01 = uvToUnit(f, 0.25, 0.75, {});
  const ax = p10.x - p00.x, ay = p10.y - p00.y, az = p10.z - p00.z;
  const bx = p01.x - p00.x, by = p01.y - p00.y, bz = p01.z - p00.z;
  const nx = ay * bz - az * by;
  const ny = az * bx - ax * bz;
  const nz = ax * by - ay * bx;
  return nx * p00.x + ny * p00.y + nz * p00.z < 0;
});

// (u,v) in [0,1] on a face -> unit vector on the sphere. Writes into `out`.
export function uvToUnit(face, u, v, out) {
  const a = Math.tan((u * 2 - 1) * QUARTER_PI);
  const b = Math.tan((v * 2 - 1) * QUARTER_PI);
  face.cube(a, b, out);
  const inv = 1 / Math.hypot(out.x, out.y, out.z);
  out.x *= inv; out.y *= inv; out.z *= inv;
  return out;
}

// Unit vector -> { face, u, v }. The exact inverse of uvToUnit, which is what
// the tangent warp bought us.
export function unitToUV(x, y, z, out = {}) {
  const ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
  let faceIndex, a, b;
  if (ax >= ay && ax >= az) {
    if (x > 0) { faceIndex = 0; a = -z / ax; b = y / ax; }
    else       { faceIndex = 1; a = z / ax;  b = y / ax; }
  } else if (ay >= az) {
    if (y > 0) { faceIndex = 2; a = x / ay;  b = -z / ay; }
    else       { faceIndex = 3; a = x / ay;  b = z / ay;  }
  } else {
    if (z > 0) { faceIndex = 4; a = x / az;  b = y / az;  }
    else       { faceIndex = 5; a = -x / az; b = y / az;  }
  }
  out.face = faceIndex;
  out.u = (Math.atan(a) / QUARTER_PI + 1) * 0.5;
  out.v = (Math.atan(b) / QUARTER_PI + 1) * 0.5;
  return out;
}
