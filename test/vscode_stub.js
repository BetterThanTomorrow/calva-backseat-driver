/** Minimal vscode stub for node unit tests. Named exports, so that `vscode/window` and the like resolve. */
export const cursor = {
  mcp: {
    registerServer: async () => undefined,
    unregisterServer: async () => undefined
  }
};

export const extensions = {
  getExtension: (id) => (id === 'betterthantomorrow.calva' ? { exports: { v1: {} } } : null)
};

export const Uri = {
  file: (path) => ({ fsPath: path }),
  joinPath: (base, ...rest) => {
    if (!base || typeof base.fsPath !== 'string') {
      throw new Error('Uri.joinPath: base must be a vscode.Uri');
    }
    return { fsPath: [base.fsPath, ...rest.filter(Boolean)].join('/') };
  }
};

export class Position {
  constructor(line, character) {
    this.line = line;
    this.character = character;
  }
}

export const TextEdit = {
  insert: (position, newText) => ({ range: { start: position, end: position }, newText })
};

export class WorkspaceEdit {
  constructor() {
    this.entries = [];
  }
  set(uri, edits) {
    this.entries.push([uri, edits]);
  }
}

export const workspace = {
  getConfiguration: () => ({ get: () => undefined }),
  workspaceFolders: undefined,
  fs: {
    createDirectory: async () => undefined,
    writeFile: async () => undefined,
    delete: async () => undefined
  },
  openTextDocument: async () => undefined,
  applyEdit: async () => true
};

export const window = {
  showInformationMessage: async () => undefined,
  showErrorMessage: async () => undefined,
  showInputBox: async () => undefined,
  showTextDocument: async () => undefined
};

export const env = {
  clipboard: { writeText: async () => undefined }
};

export const commands = {
  executeCommand: async () => undefined
};
