import { app, BrowserWindow, ipcMain, dialog, shell } from 'electron';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { readFile, writeFile, mkdir } from 'node:fs/promises';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');

// Vite dev server when running `npm run app`, built files otherwise.
const DEV_URL = process.env.SAGM_DEV_URL || 'http://localhost:5173';
const isDev = !app.isPackaged && process.env.SAGM_PROD !== '1';

let win = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#07070b',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      // The renderer reads user photos off disk via the main process, so it
      // never needs file:// fetch privileges of its own.
      sandbox: false,
    },
  });

  win.once('ready-to-show', () => win.show());

  if (isDev) {
    win.loadURL(DEV_URL);
  } else {
    win.loadFile(join(ROOT, 'dist', 'index.html'));
  }

  // Keep stray navigation (a dragged link, an <a href>) from replacing the app.
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith(DEV_URL) && !url.startsWith('file://')) e.preventDefault();
  });
}

app.whenReady().then(() => {
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

// --- IPC -------------------------------------------------------------------

const IMAGE_EXT = /\.(png|jpe?g|webp|gif|bmp|avif)$/i;

const MIME = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
  '.bmp': 'image/bmp',
  '.avif': 'image/avif',
};

/**
 * Electron's renderer gets a File object on drop, but for large photos it is
 * cheaper (and works regardless of sandbox rules) to read the bytes here and
 * hand back a data URL the renderer can feed straight into an <img>.
 */
ipcMain.handle('read-image', async (_evt, filePath) => {
  if (typeof filePath !== 'string' || !IMAGE_EXT.test(filePath)) {
    throw new Error('Not an image file');
  }
  const buf = await readFile(filePath);
  const ext = filePath.slice(filePath.lastIndexOf('.')).toLowerCase();
  return `data:${MIME[ext] || 'application/octet-stream'};base64,${buf.toString('base64')}`;
});

ipcMain.handle('pick-image', async () => {
  const res = await dialog.showOpenDialog(win, {
    title: 'Pick a photo to walk into',
    properties: ['openFile'],
    filters: [{ name: 'Images', extensions: ['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp', 'avif'] }],
  });
  if (res.canceled || !res.filePaths[0]) return null;
  return res.filePaths[0];
});

/** Persist a generated WorldSpec next to the app so a world can be reopened. */
ipcMain.handle('save-world', async (_evt, name, spec) => {
  const dir = join(app.getPath('userData'), 'worlds');
  await mkdir(dir, { recursive: true });
  const safe = String(name).replace(/[^a-z0-9_-]+/gi, '_').slice(0, 60) || 'world';
  const file = join(dir, `${safe}.json`);
  await writeFile(file, JSON.stringify(spec), 'utf8');
  return file;
});
