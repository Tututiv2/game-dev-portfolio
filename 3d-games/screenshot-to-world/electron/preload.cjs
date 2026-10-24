const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('sagm', {
  isElectron: true,

  /**
   * Electron 32+ removed File.path. webUtils.getPathForFile is the supported
   * way to turn a dropped File back into an absolute path.
   */
  pathForFile: (file) => {
    try {
      return webUtils.getPathForFile(file);
    } catch {
      return null;
    }
  },

  readImage: (filePath) => ipcRenderer.invoke('read-image', filePath),
  pickImage: () => ipcRenderer.invoke('pick-image'),
  saveWorld: (name, spec) => ipcRenderer.invoke('save-world', name, spec),
});
