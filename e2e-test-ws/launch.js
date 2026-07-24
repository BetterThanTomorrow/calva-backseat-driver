const cp = require('child_process');
const path = require('path');
const process = require('process');
const os = require('os');
const fs = require('fs');
const {
  downloadAndUnzipVSCode,
  resolveCliArgsFromVSCodeExecutablePath,
  runTests,
} = require('@vscode/test-electron');

// Cursor sets ELECTRON_RUN_AS_NODE=1. @vscode/test-electron
// spawns the VS Code Electron binary directly; with that env var, the workspace path
// is treated as a Node entry script → "Cannot find module '<test-workspace>'".
delete process.env.ELECTRON_RUN_AS_NODE;

const minCalvaVersion = '2.0.592';

// @vscode/test-electron@3.0.0 still returns …/MacOS/Electron on darwin.
// VS Code 1.110+ renamed that binary (Code / Code - Insiders); the Electron
// compat symlink was removed ~2026-07-20. Upstream fix:
// https://github.com/microsoft/vscode-test/pull/350 — not on npm yet.
// Remove this helper once @vscode/test-electron publishes that fix.
function resolveDarwinVSCodeExecutable(vscodeExecutablePath) {
  if (process.platform !== 'darwin' || fs.existsSync(vscodeExecutablePath)) {
    return vscodeExecutablePath;
  }
  const macosDir = path.dirname(vscodeExecutablePath);
  const infoPlistPath = path.resolve(macosDir, '..', 'Info.plist');
  try {
    const plist = fs.readFileSync(infoPlistPath, 'utf8');
    const match = plist.match(
      /<key>CFBundleExecutable<\/key>\s*<string>([^<]+)<\/string>/
    );
    if (match) {
      const resolved = path.resolve(macosDir, match[1]);
      if (fs.existsSync(resolved)) {
        console.info(
          `Resolved VS Code executable via Info.plist: ${resolved}`
        );
        return resolved;
      }
    }
  } catch (err) {
    console.warn('Failed to resolve darwin VS Code executable from Info.plist:', err);
  }
  return vscodeExecutablePath;
}

function init() {
  return new Promise((resolve, reject) => {
    try {
      const USER_CONFIG_PATH_KEY = 'VSCODE_JOYRIDE_USER_CONFIG_PATH';
      if (!process.env[USER_CONFIG_PATH_KEY]) {
        const tmpConfigPath = path.join(os.tmpdir(), 'vscode-test-runner-calva', 'user-config');
        if (fs.existsSync(tmpConfigPath)) {
          fs.rmSync(tmpConfigPath, { recursive: true });
        }
        fs.mkdirSync(tmpConfigPath, { recursive: true });
        process.env[USER_CONFIG_PATH_KEY] = tmpConfigPath;
        console.info(`USER_CONFIG_PATH: ${process.env[USER_CONFIG_PATH_KEY]}`);
      }
      resolve();
    } catch (error) {
      reject(error);
    }
  });
}

async function main(vsixPathOrLabel, testWorkspace, calvaVsix) {
  try {
    const extensionTestsPath = path.resolve(__dirname, 'runTests');
    const vscodeExecutablePath = resolveDarwinVSCodeExecutable(
      await downloadAndUnzipVSCode('insiders')
    );
    const [cliPath, ...args] = resolveCliArgsFromVSCodeExecutablePath(vscodeExecutablePath);

    const calvaExtension = calvaVsix
      ? calvaVsix
      : `betterthantomorrow.calva@${minCalvaVersion}`;

    const launchArgs = [
      testWorkspace,
      ...args,
      //'--verbose',
      '--disable-workspace-trust',
      // When debugging tests, it can be good to use the development version of Joyride
      // If you do, comment out the install of the Joyride extension here
      // And set the `extensionDevelopmentPath` in the `runTests` call below
      // (And can't be used if you are testing the development version of Calva)
      '--install-extension',
      'betterthantomorrow.joyride',
      '--force',
      '--install-extension',
      calvaExtension,
      '--force',
    ];
    if (vsixPathOrLabel !== 'extension-development') {
      launchArgs.push('--install-extension', vsixPathOrLabel);
    }

    console.log('CLI Path:', cliPath);
    console.log('Launch Args:', launchArgs);
    const result = cp.spawnSync(cliPath, launchArgs, {
      encoding: 'utf-8',
      stdio: 'inherit',
      shell: process.platform === 'win32',
      windowsVerbatimArguments: true,
    });
    console.log('Spawn result:', result);

    const runOptions = {
      // When debugging tests, it can be good to use the development version Joyride
      // extensionDevelopmentPath: '/Users/pez/Projects/joyride',
      vscodeExecutablePath,
      extensionTestsPath,
      launchArgs: [testWorkspace],
    };
    if (vsixPathOrLabel === 'extension-development') {
      runOptions.extensionDevelopmentPath = path.resolve(__dirname, '..');
    }
    await runTests(runOptions)
      .then((_result) => {
        console.info('Tests finished');
      })
      .catch((err) => {
        console.error('Tests finished:', err);
        process.exit(1);
      });
  } catch (err) {
    console.error('Failed to run tests:', err);
    process.exit(1);
  }
}

const args = require('minimist')(process.argv.slice(2));
const vsix = args['vsix'] ? args['vsix'] : 'extension-development';
const calvaVsix = args['calva-vsix'] ? path.resolve(args['calva-vsix']) : undefined;
const testWorkspace = args['test-workspace']
  ? path.resolve(args['test-workspace'])
  : path.resolve(__dirname);
console.info(`Using:\n  ${vsix}\n  Calva: ${calvaVsix || `marketplace@${minCalvaVersion}`}\n  Test workspace: ${testWorkspace}`);

void init()
  .then(() => main(vsix, testWorkspace, calvaVsix))
  .catch((error) => {
    console.error('Failed to initialize test running environment:', error);
    process.exit(1);
  });
