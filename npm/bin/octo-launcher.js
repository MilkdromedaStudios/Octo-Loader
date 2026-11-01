#!/usr/bin/env node
'use strict';

// Octo Launcher, installed with npm.
//
// The app itself is a Java program, released on GitHub with every build. This
// keeps the newest one in ~/.octo-launcher/npm, checked against the release's
// checksums.txt before it is used, and runs it with Java 17 or newer: the one
// you have, or, when there is none, Eclipse Temurin 21 downloaded for you
// (checked against Adoptium's own checksum). Nothing is installed outside
// that folder.
//
//   octo-launcher                 open the app
//   octo-launcher <command>       the app's command line (try "help")
//   octo-launcher --update        look for a newer Octo Launcher now
//   octo-launcher --where         where everything is kept
//   octo-launcher --version

const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const https = require('https');
const tls = require('tls');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');

const REPOSITORY = 'MilkdromedaStudios/Octo-Loader';
const HOME = path.join(os.homedir(), '.octo-launcher', 'npm');
const JAR = path.join(HOME, 'octo-launcher.jar');
const TAG = path.join(HOME, 'tag.txt');
const CHECKED = path.join(HOME, 'checked.txt');
const RUNTIME = path.join(HOME, 'runtime');
const VERSION = require('../package.json').version;
const AGENT = `octo-launcher-npm/${VERSION} (+https://github.com/${REPOSITORY})`;
const DAY = 24 * 60 * 60 * 1000;

function say(message) {
  process.stderr.write(`octo-launcher: ${message}\n`);
}

// ------------------------------------------------------------------ network

/**
 * A connection through HTTPS_PROXY when one is set (and the host is not in
 * NO_PROXY): Node does not use a proxy by itself, and a school or office
 * network may allow nothing else.
 */
function proxyFor(host) {
  const proxy = process.env.HTTPS_PROXY || process.env.https_proxy;

  if (!proxy) {
    return null;
  }

  const skip = (process.env.NO_PROXY || process.env.no_proxy || '').split(',').map((entry) => entry.trim())
      .filter(Boolean);

  if (skip.some((entry) => entry === '*' || host === entry.replace(/^\./, '')
      || host.endsWith(entry.startsWith('.') ? entry : `.${entry}`))) {
    return null;
  }

  return new URL(proxy);
}

function tunnel(target) {
  const proxy = proxyFor(target.hostname);

  if (!proxy) {
    return Promise.resolve(null);
  }

  return new Promise((resolve, reject) => {
    const headers = { Host: `${target.hostname}:443` };

    if (proxy.username) {
      headers['Proxy-Authorization'] = 'Basic ' + Buffer.from(
          `${decodeURIComponent(proxy.username)}:${decodeURIComponent(proxy.password)}`).toString('base64');
    }

    const request = http.request({ host: proxy.hostname, port: proxy.port || 80, method: 'CONNECT',
      path: `${target.hostname}:443`, headers });
    request.on('connect', (response, socket) => {
      if (response.statusCode !== 200) {
        socket.destroy();
        reject(new Error(`the proxy refused ${target.hostname} (${response.statusCode})`));
        return;
      }

      resolve(tls.connect({ socket, servername: target.hostname }));
    });
    request.on('error', reject);
    request.end();
  });
}

/** A response, following redirects unless asked not to. */
async function open(url, { follow = true, redirects = 8 } = {}) {
  const target = new URL(url);
  const socket = await tunnel(target);

  const response = await new Promise((resolve, reject) => {
    const options = { headers: { 'User-Agent': AGENT, Accept: '*/*' } };

    if (socket) {
      options.createConnection = () => socket;
      options.agent = false;
    }

    const request = https.get(target, options, resolve);
    request.setTimeout(30000, () => request.destroy(new Error(`${url} timed out`)));
    request.on('error', reject);
  });

  if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
    response.resume();

    if (!follow) {
      return response;
    }

    if (redirects <= 0) {
      throw new Error(`${url} redirects too often`);
    }

    return open(new URL(response.headers.location, url).toString(), { follow, redirects: redirects - 1 });
  }

  if (response.statusCode !== 200) {
    response.resume();
    throw new Error(`${url} answered ${response.statusCode}`);
  }

  return response;
}

function get(url) {
  return open(url);
}

async function text(url) {
  const response = await get(url);
  let body = '';
  response.setEncoding('utf8');

  for await (const chunk of response) {
    body += chunk;
  }

  return body;
}

/** Downloads to a file beside the target and moves it into place only once its SHA-256 matches. */
async function download(url, target, sha256, label) {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const part = `${target}.part`;
  const response = await get(url);
  const total = Number(response.headers['content-length'] || 0);
  const hash = crypto.createHash('sha256');
  const out = fs.createWriteStream(part);
  let received = 0;
  let shown = -1;

  for await (const chunk of response) {
    hash.update(chunk);
    received += chunk.length;

    if (!out.write(chunk)) {
      await new Promise((resolve) => out.once('drain', resolve));
    }

    const percent = total ? Math.floor((received / total) * 100) : -1;

    if (process.stderr.isTTY && percent !== shown && percent % 5 === 0) {
      shown = percent;
      process.stderr.write(`\rocto-launcher: downloading ${label} ${percent}%   `);
    }
  }

  await new Promise((resolve, reject) => out.end((error) => (error ? reject(error) : resolve())));

  if (process.stderr.isTTY && total) {
    process.stderr.write('\n');
  }

  const actual = hash.digest('hex');

  if (actual.toLowerCase() !== sha256.toLowerCase()) {
    fs.rmSync(part, { force: true });
    throw new Error(`${label} did not match its checksum (expected ${sha256}, got ${actual}); it was discarded`);
  }

  fs.renameSync(part, target);
}

// ---------------------------------------------------------------- the app

function read(file) {
  try {
    return fs.readFileSync(file, 'utf8').trim();
  } catch {
    return null;
  }
}

/** The newest Octo Launcher, if this one is older; checked once a day unless asked. */
async function ensureLauncher(force) {
  const have = fs.existsSync(JAR);
  const lastChecked = Number(read(CHECKED) || 0);

  if (have && !force && Date.now() - lastChecked < DAY) {
    return;
  }

  // The newest release, by where GitHub's "latest" page sends you: no API,
  // so no API rate limit, which a shared network can run out of.
  let tag;

  try {
    const latest = await open(`https://github.com/${REPOSITORY}/releases/latest`, { follow: false });
    const location = latest.headers.location || '';
    tag = decodeURIComponent(location.substring(location.lastIndexOf('/') + 1));

    if (!tag || !location.includes('/releases/tag/')) {
      throw new Error('GitHub did not name its latest release');
    }
  } catch (error) {
    if (have) {
      return; // Offline: the one there is.
    }

    throw new Error(`could not reach GitHub to download Octo Launcher (${error.message})`);
  }

  fs.mkdirSync(HOME, { recursive: true });
  fs.writeFileSync(CHECKED, String(Date.now()));

  if (have && read(TAG) === tag) {
    return;
  }

  const base = `https://github.com/${REPOSITORY}/releases/download/${encodeURIComponent(tag)}`;
  let sumsText;

  try {
    sumsText = await text(`${base}/checksums.txt`);
  } catch (error) {
    if (have) {
      return;
    }

    throw new Error(`the release ${tag} has no checksums.txt (${error.message})`);
  }

  const expected = parseChecksums(sumsText)['octo-launcher.jar'];

  if (!expected) {
    if (have) {
      return;
    }

    throw new Error(`the release ${tag} has no octo-launcher.jar yet`);
  }

  say(`${have ? 'updating to' : 'installing'} Octo Launcher (${tag})`);
  await download(`${base}/octo-launcher.jar`, JAR, expected, 'Octo Launcher');
  fs.writeFileSync(TAG, tag);
}

function parseChecksums(body) {
  const out = {};

  for (const line of body.split(/\r?\n/)) {
    const match = /^([0-9a-fA-F]{64})\s+\*?(.+)$/.exec(line.trim());

    if (match) {
      out[match[2].trim()] = match[1].toLowerCase();
    }
  }

  return out;
}

// --------------------------------------------------------------------- java

/** The feature version of a java (17, 21, ...), or 0 if it does not run. */
function javaVersion(java) {
  try {
    const result = spawnSync(java, ['-version'], { encoding: 'utf8', timeout: 20000 });
    const output = `${result.stderr || ''}${result.stdout || ''}`;
    const match = /version "(\d+)(?:\.(\d+))?/.exec(output);

    if (!match) {
      return 0;
    }

    const major = Number(match[1]);
    return major === 1 ? Number(match[2] || 0) : major;
  } catch {
    return 0;
  }
}

function javaIn(home) {
  const name = process.platform === 'win32' ? 'java.exe' : 'java';
  const candidates = [path.join(home, 'bin', name), path.join(home, 'Contents', 'Home', 'bin', name)];
  return candidates.find((candidate) => fs.existsSync(candidate)) || null;
}

/** The runtime downloaded before, wherever in the folder it unpacked. */
function downloadedJava() {
  if (!fs.existsSync(RUNTIME)) {
    return null;
  }

  for (const entry of fs.readdirSync(RUNTIME)) {
    const java = javaIn(path.join(RUNTIME, entry));

    if (java) {
      return java;
    }
  }

  return null;
}

async function ensureJava(forceDownload) {
  if (!forceDownload) {
    const candidates = [];

    if (process.env.OCTO_JAVA) {
      candidates.push(process.env.OCTO_JAVA);
    }

    if (process.env.JAVA_HOME) {
      const java = javaIn(process.env.JAVA_HOME);

      if (java) {
        candidates.push(java);
      }
    }

    candidates.push(process.platform === 'win32' ? 'java.exe' : 'java');

    for (const candidate of candidates) {
      if (javaVersion(candidate) >= 17) {
        return candidate;
      }
    }
  }

  const known = downloadedJava();

  if (known && javaVersion(known) >= 17) {
    return known;
  }

  return downloadJava();
}

async function downloadJava() {
  const system = { win32: 'windows', darwin: 'mac', linux: 'linux' }[process.platform];
  const arch = { x64: 'x64', arm64: 'aarch64' }[process.arch];

  if (!system || !arch) {
    throw new Error(`no Java 17+ was found, and none can be downloaded for ${process.platform}/${process.arch}; `
        + 'install Java 17 or newer and run octo-launcher again');
  }

  say('no Java 17 or newer was found; downloading Eclipse Temurin 21 (once, into ' + RUNTIME + ')');
  const assets = JSON.parse(await text('https://api.adoptium.net/v3/assets/latest/21/hotspot'
      + `?os=${system}&architecture=${arch}&image_type=jre&vendor=eclipse`));
  const pkg = assets && assets[0] && assets[0].binary && assets[0].binary.package;

  if (!pkg) {
    throw new Error('Adoptium has no Java 21 runtime for this system');
  }

  const archive = path.join(HOME, pkg.name);
  await download(pkg.link, archive, pkg.checksum, 'Java 21');
  fs.rmSync(RUNTIME, { recursive: true, force: true });
  fs.mkdirSync(RUNTIME, { recursive: true });
  // tar reads .tar.gz everywhere, and .zip too on Windows 10 and later.
  const unpacked = spawnSync('tar', ['-xf', archive, '-C', RUNTIME], { stdio: 'inherit' });
  fs.rmSync(archive, { force: true });

  if (unpacked.status !== 0) {
    throw new Error('the Java download could not be unpacked (tar failed)');
  }

  const java = downloadedJava();

  if (!java) {
    throw new Error('the Java download did not contain a java program');
  }

  return java;
}

// --------------------------------------------------------------------- main

async function main() {
  const args = process.argv.slice(2);

  if (args[0] === '--version') {
    console.log(`octo-launcher (npm) ${VERSION}; Octo Launcher ${read(TAG) || 'not downloaded yet'}`);
    return 0;
  }

  if (args[0] === '--where') {
    console.log(`Octo Launcher: ${JAR}\nJava (if downloaded): ${RUNTIME}\nSettings: ${path.join(os.homedir(),
        '.octo-launcher')}`);
    return 0;
  }

  if (args[0] === '--self-test') {
    const sums = parseChecksums(`${'a'.repeat(64)}  octo-launcher.jar\n${'B'.repeat(64)} *installer.jar\n`);
    const ok = sums['octo-launcher.jar'] === 'a'.repeat(64) && sums['installer.jar'] === 'b'.repeat(64);
    console.log(ok ? 'self-test passed' : 'self-test FAILED');
    return ok ? 0 : 1;
  }

  const forceUpdate = args[0] === '--update';
  const forceJava = args[0] === '--download-java';
  const passed = forceUpdate || forceJava ? args.slice(1) : args;

  await ensureLauncher(forceUpdate);
  const java = await ensureJava(forceJava);

  if (forceUpdate || forceJava) {
    say(`ready: Octo Launcher ${read(TAG) || ''} with ${java}`);

    if (passed.length === 0 && !process.stdout.isTTY) {
      return 0;
    }
  }

  // No arguments: the window, left running on its own. Arguments: the
  // command line, here in this terminal.
  if (passed.length === 0) {
    const child = spawn(java, ['-jar', JAR], { detached: true, stdio: 'ignore', windowsHide: true });
    child.unref();
    return 0;
  }

  const child = spawn(java, ['-jar', JAR, ...passed], { stdio: 'inherit' });
  return new Promise((resolve) => child.on('exit', (code) => resolve(code === null ? 1 : code)));
}

main().then((code) => process.exit(code), (error) => {
  say(error.message);
  process.exit(1);
});
