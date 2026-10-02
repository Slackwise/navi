import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const desktop = join(root, 'frontend', 'desktop');

export function assetName(platform = process.platform, arch = process.arch) {
  if (!['win32', 'linux', 'darwin'].includes(platform) || !['x64', 'arm64'].includes(arch)) {
    throw new Error(`Unsupported SEA target: ${platform}-${arch}`);
  }
  return platform === 'win32' ? 'navi.exe' : `navi-desktop-${platform}-${arch}`;
}

function addTree(assets, directory, prefix) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const file = join(directory, entry.name);
    const key = `${prefix}/${entry.name}`;
    if (entry.isDirectory()) addTree(assets, file, key);
    else if (entry.isFile()) assets[key] = file;
    else throw new Error(`Cannot package non-regular file: ${file}`);
  }
}

export function buildSea({
  appDir = join(desktop, 'out'),
  outputDir = join(desktop, 'dist'),
  buildDir = join(desktop, 'build', 'sea'),
} = {}) {
  const [major, minor, patch] = process.versions.node.split('.').map(Number);
  if (major < 26 || (major === 26 && (minor < 11 || (minor === 11 && patch < 1)))) {
    throw new Error('SEA packaging requires Node.js 26.11.1 or newer. See .node-version.');
  }
  mkdirSync(outputDir, { recursive: true });
  mkdirSync(buildDir, { recursive: true });
  const assets = {};
  addTree(assets, appDir, 'app');
  if (!assets['app/main.js']) throw new Error('Compile the desktop release before packaging: npm run desktop:build');

  const appPackage = join(buildDir, 'app-package.json');
  writeFileSync(appPackage, JSON.stringify({ type: 'module' }));
  assets['app/package.json'] = appPackage;
  const koffiRoot = dirname(require.resolve('koffi'));
  for (const name of ['package.json', 'index.js', 'index.cjs', 'src/koffi/index.js',
    'src/koffi/index.cjs', 'src/koffi/src/static.js', 'src/koffi/src/static.cjs', 'LICENSE.txt']) {
    assets[`node_modules/koffi/${name}`] = join(koffiRoot, ...name.split('/'));
  }

  const nativePackage = `@koromix/koffi-${process.platform}-${process.arch}`;
  const nativeRoot = dirname(require.resolve(nativePackage));
  assets['native/koffi.node'] = join(nativeRoot, `${process.platform}_${process.arch}`, 'koffi.node');
  // Native addons must live on the real filesystem. Keep Koffi's own JS wrapper,
  // but route its platform package through the addon extracted by the SEA entry.
  const nativeLoader = join(buildDir, 'native-loader.cjs');
  writeFileSync(nativeLoader, `const native = { exports: {} };
process.dlopen(native, globalThis[Symbol.for('navi.koffi.path')]);
module.exports = native.exports;
`);
  const nativeManifest = join(buildDir, 'native-package.json');
  writeFileSync(nativeManifest, JSON.stringify({ name: nativePackage, main: 'index.cjs' }));
  assets[`node_modules/${nativePackage}/index.cjs`] = nativeLoader;
  assets[`node_modules/${nativePackage}/package.json`] = nativeManifest;

  const output = resolve(outputDir, assetName());
  const config = {
    main: join(root, 'scripts', 'desktop-sea-entry.mjs'),
    mainFormat: 'module',
    output,
    useVfs: true,
    useSnapshot: false,
    useCodeCache: false,
    disableExperimentalSEAWarning: true,
    execArgvExtension: 'none',
    assets,
  };
  // Fail here, rather than produce an EXE with a missing native addon.
  for (const file of Object.values(assets)) readFileSync(file);
  const configPath = join(buildDir, 'sea-config.json');
  writeFileSync(configPath, JSON.stringify(config, null, 2));
  execFileSync(process.execPath, ['--build-sea', configPath], { stdio: 'inherit' });
  if (process.platform === 'darwin') {
    execFileSync('codesign', ['--force', '--sign', '-', output], { stdio: 'inherit' });
  }
  console.log(`Packaged ${relative(root, output).split(sep).join('/')}`);
  return output;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  buildSea();
}
