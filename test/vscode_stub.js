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
    joinPath: (base, ...rest) => {
      if (!base || typeof base.fsPath !== 'string') {
        throw new Error('Uri.joinPath: base must be a vscode.Uri');
      }
      return { fsPath: [base.fsPath, ...rest.filter(Boolean)].join('/') };
    }
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
