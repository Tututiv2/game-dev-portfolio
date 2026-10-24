const { contextBridge, ipcRenderer } = require("electron");
const { pathToFileURL } = require("node:url");

contextBridge.exposeInMainWorld("shadowDesigner", {
  getLaunchState: () => ipcRenderer.invoke("designer:getLaunchState"),
  pickHtmlFile: () => ipcRenderer.invoke("designer:pickHtmlFile"),
  clearSelectedHtml: () => ipcRenderer.invoke("designer:clearSelectedHtml"),
  setSelectedHtmlPath: (absolutePath) => ipcRenderer.invoke("designer:setSelectedHtmlPath", absolutePath),
  exportSpriteSheetPng: (payload) => ipcRenderer.invoke("designer:exportSpriteSheetPng", payload),
  exportFrameZip: (payload) => ipcRenderer.invoke("designer:exportFrameZip", payload),
  onRefreshState: (callback) => {
    const handler = () => callback();
    ipcRenderer.on("designer:refresh-state", handler);
    return () => ipcRenderer.removeListener("designer:refresh-state", handler);
  },
  toFileUrl: (absolutePath) => pathToFileURL(absolutePath).href,
});
