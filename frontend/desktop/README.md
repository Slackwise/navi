# Navi Desktop

Release builds are standalone Node.js single-executable applications (SEA).
Users do not need Node.js, npm, or Java installed.

## Run from source

Use Node.js **26.11.1 or newer** (the latest stable release when SEA packaging was
introduced), plus JDK 21 or newer for Shadow-CLJS. The release workflow pins its
Node version in the repository's `.node-version`.

From the repository root:

```sh
npm install
npm run desktop:build
npm start --workspace navi-desktop
```

For live development, run `npm run desktop:dev` and then
`npm start --workspace navi-desktop` in another terminal.

## Build a standalone release

```sh
npm run desktop:build
npm run desktop:package
```

Packaging uses Node's built-in `--build-sea` and virtual filesystem: no separate
bundler, executable injector, or alternative runtime is required. Build on the
target OS and architecture so the embedded Node runtime and Koffi addon match.
The executable is written to `dist/` in this directory.

Run `npm run test:sea` to build and execute an isolated fixture that verifies
embedded ESM modules, Koffi callbacks, and CLI arguments without Node on PATH.

## Install a release

Download the executable for your platform from GitHub Releases:

| Platform | Asset |
| --- | --- |
| Windows x64 | `navi.exe` |
| Linux x64 | `navi-desktop-linux-x64` |
| macOS Apple Silicon | `navi-desktop-darwin-arm64` |
| macOS Intel | `navi-desktop-darwin-x64` |

Put it in a permanent, user-writable directory so autostart and self-updates work.
On Linux/macOS, make it executable with `chmod +x <downloaded-file>`.
Windows releases are not code-signed; macOS releases use ad-hoc signing, not
Developer ID notarization. OS security checks may require explicit approval.

On Windows, launch the EXE to run the tray application, or use CLI commands:

```powershell
.\navi.exe version
.\navi.exe toggle-theme
```

Linux/macOS remain headless; bind OS shortcuts to the executable's CLI commands.

Koffi's native addon is embedded in the executable and extracted on first use
to a SHA-256-verified, content-addressed cache under the user's local application
data/cache directory. Distribution is a single file, but native code must be
loaded from a real file on disk. Old cache versions can be removed when Navi is
not running.

Self-updates download the matching executable asset. On Windows a temporary
`<executable>.updater.exe` waits for Navi to exit, replaces the EXE with rollback
on installation failure, and relaunches it. The next launch cleans up the old
EXE and helper. Helper failures are recorded in `<executable>.update.log`.
