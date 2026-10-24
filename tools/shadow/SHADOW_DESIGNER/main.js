const { app, BrowserWindow, dialog, ipcMain } = require("electron");
const { promises: fs } = require("node:fs");
const path = require("node:path");
const AdmZip = require("adm-zip");

const SETTINGS_FILE = "shadow-designer.settings.json";
const APP_ICON_PATH = path.join(__dirname, "assets", "icon.png");

let mainWindow = null;
let splashWindow = null;
let pendingOpenHtmlPath = null;

function getSettingsPath() {
  return path.join(app.getPath("userData"), SETTINGS_FILE);
}

async function loadSettings() {
  try {
    const raw = await fs.readFile(getSettingsPath(), "utf-8");
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed.selectedHtmlPath === "string") {
      return { selectedHtmlPath: parsed.selectedHtmlPath };
    }
    return { selectedHtmlPath: null };
  } catch {
    return { selectedHtmlPath: null };
  }
}

async function saveSettings(nextSettings) {
  await fs.writeFile(getSettingsPath(), JSON.stringify(nextSettings, null, 2), "utf-8");
}

async function normalizeHtmlPath(candidatePath) {
  const resolved = path.resolve(candidatePath);
  const stat = await fs.stat(resolved);
  if (!stat.isFile() || path.extname(resolved).toLowerCase() !== ".html") {
    throw new Error("Please choose a valid .html file.");
  }
  return resolved;
}

async function saveSelectedHtmlPath(candidatePath) {
  const normalizedPath = await normalizeHtmlPath(candidatePath);
  await saveSettings({ selectedHtmlPath: normalizedPath });
  return {
    selectedHtmlPath: normalizedPath,
    selectedHtmlName: path.basename(normalizedPath),
  };
}

function decodeDataUrl(dataUrl) {
  if (typeof dataUrl !== "string") {
    throw new Error("Invalid data payload.");
  }
  const match = dataUrl.match(/^data:([^;]+);base64,(.+)$/);
  if (!match) {
    throw new Error("Data payload must be a base64 data URL.");
  }
  return {
    mimeType: match[1],
    buffer: Buffer.from(match[2], "base64"),
  };
}

async function promptSavePath(defaultPath, filters) {
  if (!mainWindow) {
    throw new Error("Window is not ready.");
  }
  const result = await dialog.showSaveDialog(mainWindow, {
    defaultPath,
    filters,
  });
  if (result.canceled || !result.filePath) {
    return null;
  }
  return result.filePath;
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1360,
    height: 880,
    minWidth: 1000,
    minHeight: 700,
    title: "Shadow Designer",
    autoHideMenuBar: true,
    icon: APP_ICON_PATH,
    show: false,
    backgroundColor: "#08162b",
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, "preload.js"),
    },
  });

  mainWindow.once("ready-to-show", () => {
    if (splashWindow && !splashWindow.isDestroyed()) {
      splashWindow.close();
    }
    splashWindow = null;
    mainWindow.show();
  });
  mainWindow.on("closed", () => {
    mainWindow = null;
  });

  mainWindow.loadFile(path.join(__dirname, "index.html")).catch(() => {});
}

function createSplashWindow() {
  splashWindow = new BrowserWindow({
    width: 620,
    height: 360,
    frame: false,
    resizable: false,
    movable: true,
    minimizable: false,
    maximizable: false,
    closable: true,
    show: true,
    alwaysOnTop: true,
    title: "Shadow Designer",
    icon: APP_ICON_PATH,
    backgroundColor: "#071226",
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  splashWindow.on("closed", () => {
    splashWindow = null;
  });
  splashWindow.loadFile(path.join(__dirname, "splash.html")).catch(() => {});
}

ipcMain.handle("designer:getLaunchState", async () => {
  const settings = await loadSettings();
  if (!settings.selectedHtmlPath) {
    return { selectedHtmlPath: null, selectedHtmlName: null };
  }
  try {
    const normalizedPath = await normalizeHtmlPath(settings.selectedHtmlPath);
    return {
      selectedHtmlPath: normalizedPath,
      selectedHtmlName: path.basename(normalizedPath),
    };
  } catch {
    await saveSettings({ selectedHtmlPath: null });
    return { selectedHtmlPath: null, selectedHtmlName: null };
  }
});

ipcMain.handle("designer:pickHtmlFile", async () => {
  if (!mainWindow) {
    throw new Error("Window is not ready.");
  }
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "Choose Sprite Animator HTML",
    properties: ["openFile"],
    filters: [{ name: "HTML Files", extensions: ["html", "htm"] }],
  });
  if (result.canceled || result.filePaths.length === 0) {
    return { selectedHtmlPath: null, selectedHtmlName: null };
  }
  return saveSelectedHtmlPath(result.filePaths[0]);
});

ipcMain.handle("designer:clearSelectedHtml", async () => {
  await saveSettings({ selectedHtmlPath: null });
  return { selectedHtmlPath: null, selectedHtmlName: null };
});

ipcMain.handle("designer:setSelectedHtmlPath", async (_, absolutePath) => {
  if (typeof absolutePath !== "string" || !absolutePath.trim()) {
    throw new Error("A valid HTML path is required.");
  }
  return saveSelectedHtmlPath(absolutePath.trim());
});

ipcMain.handle("designer:exportSpriteSheetPng", async (_event, payload) => {
  const defaultName =
    payload && typeof payload.defaultName === "string" && payload.defaultName.trim()
      ? payload.defaultName.trim()
      : "sprite-sheet.png";
  const dataUrl = payload?.dataUrl;
  const { mimeType, buffer } = decodeDataUrl(dataUrl);
  if (mimeType !== "image/png") {
    throw new Error("Sprite sheet export expects PNG data.");
  }
  const filePath = await promptSavePath(defaultName, [{ name: "PNG Image", extensions: ["png"] }]);
  if (!filePath) {
    return null;
  }
  await fs.writeFile(filePath, buffer);
  return filePath;
});

ipcMain.handle("designer:exportFrameZip", async (_event, payload) => {
  const defaultName =
    payload && typeof payload.defaultName === "string" && payload.defaultName.trim()
      ? payload.defaultName.trim()
      : "sprite-frames.zip";
  const frames = Array.isArray(payload?.frames) ? payload.frames : [];
  if (frames.length === 0) {
    throw new Error("No frames were provided for ZIP export.");
  }
  const zip = new AdmZip();
  for (const frame of frames) {
    const frameName =
      frame && typeof frame.name === "string" && frame.name.trim() ? frame.name.trim() : "frame.png";
    const { mimeType, buffer } = decodeDataUrl(frame?.dataUrl);
    if (mimeType !== "image/png") {
      throw new Error(`Frame ${frameName} is not PNG data.`);
    }
    zip.addFile(frameName, buffer);
  }
  const filePath = await promptSavePath(defaultName, [{ name: "ZIP Archive", extensions: ["zip"] }]);
  if (!filePath) {
    return null;
  }
  await fs.writeFile(filePath, zip.toBuffer());
  return filePath;
});

app.on("second-instance", (_event, commandLine) => {
  const candidate = commandLine.slice(1).find((item) => /\.(html?|HTML?)$/.test(item));
  if (candidate) {
    pendingOpenHtmlPath = candidate;
  }
  if (mainWindow) {
    if (mainWindow.isMinimized()) {
      mainWindow.restore();
    }
    mainWindow.focus();
    if (pendingOpenHtmlPath) {
      void saveSelectedHtmlPath(pendingOpenHtmlPath)
        .then(() => mainWindow.webContents.send("designer:refresh-state"))
        .catch(() => {});
      pendingOpenHtmlPath = null;
    }
  }
});

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
}

app.whenReady().then(() => {
  const initialArg = process.argv.slice(1).find((item) => /\.(html?|HTML?)$/.test(item));
  if (initialArg) {
    pendingOpenHtmlPath = initialArg;
  }
  createSplashWindow();
  createWindow();
  if (pendingOpenHtmlPath) {
    void saveSelectedHtmlPath(pendingOpenHtmlPath)
      .then(() => mainWindow && mainWindow.webContents.send("designer:refresh-state"))
      .catch(() => {});
    pendingOpenHtmlPath = null;
  }
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
