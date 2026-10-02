import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { once } from 'node:events';
import { copyFileSync, existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { assetName, buildSea } from './build-desktop-sea.mjs';

test('release asset names match the updater targets', () => {
  assert.equal(assetName('win32', 'x64'), 'navi.exe');
  assert.equal(assetName('linux', 'x64'), 'navi-desktop-linux-x64');
  assert.equal(assetName('darwin', 'arm64'), 'navi-desktop-darwin-arm64');
  assert.equal(assetName('darwin', 'x64'), 'navi-desktop-darwin-x64');
  assert.throws(() => assetName('freebsd', 'x64'), /Unsupported SEA target/);
});

test('SEA embeds ESM, native callbacks, CLI arguments, and Windows executable updates', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'navi-sea-test-'));
  try {
    const appDir = join(directory, 'app');
    const cache = join(directory, 'cache');
    const isolated = join(directory, 'isolated');
    mkdirSync(appDir);
    mkdirSync(isolated);
    writeFileSync(join(appDir, 'main.js'), `
import koffi from 'koffi';
import { isSea } from 'node:sea';
import { answer } from './other.js';
import { writeFileSync } from 'node:fs';
const callbackType = process.platform === 'win32'
  ? koffi.proto('__stdcall', 'NaviWindowProc', 'intptr_t', ['void *', 'uint32_t', 'uintptr_t', 'intptr_t'])
  : koffi.proto('int TestCallback(void)');
const callback = koffi.register(() => 1, koffi.pointer(callbackType));
koffi.unregister(callback);
if (process.platform === 'win32') {
  const buffer = new Uint16Array([78, 97, 118, 105, 0]);
  new DataView(new ArrayBuffer(8)).setBigUint64(0, BigInt(koffi.address(buffer)), true);
  const getPid = koffi.load('kernel32.dll').func('GetCurrentProcessId', 'uint32_t', []);
  const nativePid = await new Promise((resolve, reject) =>
    getPid.async((error, value) => error ? reject(error) : resolve(value)));
  if (nativePid !== process.pid) throw new Error('Native async call returned the wrong process ID');
}
if (process.argv.length === 2 && process.env.NAVI_SEA_UPDATE_MARKER) {
  writeFileSync(process.env.NAVI_SEA_UPDATE_MARKER, 'relaunched');
}
console.log(JSON.stringify({ sea: isSea(), version: koffi.version, answer, args: process.argv.slice(2) }));
`);
    writeFileSync(join(appDir, 'other.js'), 'export const answer = 42;\n');
    const executable = buildSea({
      appDir,
      outputDir: join(directory, 'dist'),
      buildDir: join(directory, 'build'),
    });
    const env = { ...process.env, PATH: '', LOCALAPPDATA: cache, XDG_CACHE_HOME: cache };
    const run = () => JSON.parse(execFileSync(executable, ['version', 'argument with spaces'], {
      cwd: isolated, env, encoding: 'utf8',
    }).trim());
    assert.deepEqual(run(), {
      sea: true, version: '3.3.2', answer: 42, args: ['version', 'argument with spaces'],
    });
    assert.deepEqual(run().args, ['version', 'argument with spaces']);
    const config = JSON.parse(readFileSync(join(directory, 'build', 'sea-config.json')));
    assert.equal(config.useVfs, true);
    assert.equal(config.execArgvExtension, 'none');
    if (process.platform === 'win32') {
      const marker = join(directory, 'relaunched.txt');
      const helper = `${executable}.updater.exe`;
      const updateEnv = { ...env, NAVI_SEA_UPDATE_MARKER: marker };
      const runUpdate = async (badUpdate) => {
        const parent = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
        await once(parent, 'spawn');
        let updater;
        try {
          copyFileSync(executable, helper);
          if (badUpdate) writeFileSync(`${executable}.new`, 'not an executable');
          else copyFileSync(executable, `${executable}.new`);
          updater = spawn(helper, ['--navi-apply-update', executable, String(parent.pid)], {
            cwd: isolated, env: updateEnv, stdio: ['ignore', 'ignore', 'ignore', 'ipc'],
          });
          const exited = once(updater, 'exit');
          const [message] = await once(updater, 'message', { signal: AbortSignal.timeout(15000) });
          assert.equal(message, 'ready');
          assert.equal(existsSync(`${executable}.new`), true);
          assert.equal(existsSync(`${executable}.old`), false);
          parent.kill();
          const [code] = await exited;
          assert.equal(code, badUpdate ? 1 : 0);
          const deadline = Date.now() + 10000;
          while (!existsSync(marker) && Date.now() < deadline) await delay(50);
          assert.equal(readFileSync(marker, 'utf8'), 'relaunched');
          await delay(200);
          assert.equal(run().sea, true);
          if (badUpdate) assert.match(readFileSync(`${executable}.update.log`, 'utf8'), /Error/);
        } finally {
          parent.kill();
          if (updater && updater.exitCode === null) updater.kill();
        }
        rmSync(marker);
        rmSync(`${executable}.old`, { force: true });
        rmSync(helper);
      };
      await runUpdate(false);
      await runUpdate(true);
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
