/** Minimal vscode stub for node unit tests. */
module.exports = {
  cursor: {
    mcp: {
      registerServer: async () => undefined,
      unregisterServer: async () => undefined
    }
  },
  extensions: {
    getExtension: () => null
  },
  Uri: {
    joinPath: (...parts) => ({ fsPath: parts.filter(Boolean).join("/") })
  },
  workspace: {
    getConfiguration: () => ({ get: () => undefined }),
    workspaceFolders: undefined,
    fs: {
      createDirectory: async () => undefined,
      writeFile: async () => undefined,
      delete: async () => undefined
    },
    openTextDocument: async () => undefined
  },
  window: {
    showInformationMessage: async () => undefined,
    showErrorMessage: async () => undefined,
    showInputBox: async () => undefined,
    showTextDocument: async () => undefined
  },
  env: {
    clipboard: { writeText: async () => undefined }
  },
  commands: {
    executeCommand: async () => undefined
  }
};
