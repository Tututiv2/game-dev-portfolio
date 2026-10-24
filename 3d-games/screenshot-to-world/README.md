# Screenshot to World

Drop in a photo and walk around inside it.

This app analyses a single photograph (horizon, sky, lighting, ground and the things standing on it) and rebuilds it as a first-person 3D world. Loading ends with a 2.8-second "dive": the scene starts as the untouched photo, then the ground folds down out of the picture into the floor under your feet and the silhouettes stand up around you. Everything is classical image analysis running in the browser, with no machine-learning model and no server.

## Features

- Drag and drop (or pick) any image; it becomes a walkable world with WASD, mouse look, jumping, gravity and terrain collision
- Automatic photo archetypes: **open** (horizon and sky, full projection), **interior** (structure but no sky: enclosed, shorter fog, no dome), **closeup** and **abstract** (no usable ground plane, built as a deliberate gallery space instead of lumpy terrain)
- Silhouette props: anything that breaks the skyline (a tree, a tower, a ridge, a person) is cut out with alpha and re-placed at the distance its base implies, so it slides past at the right rate as you walk
- A sun placed where the photo's brightest smooth sky blob was, so lighting on props agrees with the photo's baked-in light
- Ground beyond the photo's field of view extended procedurally from the photo's own palette and hidden under distance fog, so the world has no visible edge
- A desktop build with Electron, plus a browser-only mode through Vite

## How it works

- **A pinhole camera turns rows into distances.** Once the horizon row is known, every ground pixel has a real distance: `z = f * eyeHeight / (y - yHorizon)` and `x = (px - cx) * z / f`, with an assumed 60 degree vertical field of view and 1.7 m eye height. Guessing the field of view wrong just makes the world uniformly deeper or shallower, which reads as style rather than as broken.
- **Two-pass horizon detection.** Pass 1 reduces the image to a per-row "skyness" signal (brightness, smoothness, blueness) and splits it with Otsu's method, which nails open horizons. Against a city skyline it confidently picks the rooftops instead, so pass 2 scans down for the row where vertical-edge density collapses, which is where buildings meet the street. The refinement can only push the horizon down and only fires when there is structure to explain.
- **A sky model that walks the gradient.** One global sky colour fails both ways: trained on the top, the pale haze near the horizon becomes one enormous prop; trained on the whole band, buildings get learned as sky. The model re-estimates the sky colour row by row from pixels close to the row above, so it follows the gradient while a building entering the frame stays an outlier.
- **The dive is exact.** Every vertex and prop carries two poses, its place in the photo and its place in 3D, and a shader uniform interpolates between them. Because the terrain was built by inverse-projecting the photo into a top-down orthophoto, projecting it back reassembles the original image with no seam. The renderer marks every site where analysis space (+z into the photo) meets three.js (cameras look down -z), since a missed sign mirrors the world.
- **Tested against ground truth.** Six synthetic test photos (landscape, city skyline, sunset beach, night street, interior with no sky, close-up with no ground) are drawn with a known horizon row, and Puppeteer scripts render the analysis and the dive in real Chrome into contact sheets.

## Tech

- JavaScript ES modules, three.js r171, Vite 6
- Electron 33 for the desktop app
- Puppeteer (puppeteer-core) for screenshot tests

## Running it

`node_modules` is not included, so install first:

```
npm install
npm run dev          # browser version at http://localhost:5173
npm run app          # Vite + Electron desktop window
npm run debug        # analysis debug page
npm run shots        # render a debug contact sheet (needs a local Chrome)
```

**Status:** prototype. The world spec is plain JSON designed to accept an optional AI editing pass later, but that pass is not built.
