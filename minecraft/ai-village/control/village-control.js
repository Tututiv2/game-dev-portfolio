// AI Village in-game controller.
// Tails the Paper server log for `!sim ...` chat commands from human players
// and drives the simulation over RCON + process control.
//
// In-game commands (type in chat):
//   !sim help                      - show commands
//   !sim status                    - who's online, sim state
//   !sim restart                   - restart all bots (world + memories kept)
//   !sim reset                     - FULL reset: new world, wiped memories, fresh bots
//   !sim wipe <Bot|all>            - wipe a bot's memory (then restarts bots)
//   !sim spawn <x> <y> <z>         - set world spawn AND teleport all bots there
//   !sim tp <Bot|all> <x> <y> <z>  - teleport bot(s)
//   !sim tp <Bot> me               - teleport a bot to you
//   !sim goal <Bot> <text...>      - whisper a new goal/instruction to a bot
//   !sim day / !sim night          - set time
//   !sim stopbots / !sim startbots - stop/start the bot swarm
// Commands can also be dropped (one per line) into control/inject.txt for testing/automation.

import { Rcon } from 'rcon-client';
import { spawn, execSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const SERVER_DIR = path.join(ROOT, 'server');
const MINDCRAFT_DIR = path.join(ROOT, 'mindcraft');
const LOG_FILE = path.join(SERVER_DIR, 'logs', 'latest.log');
const INJECT_FILE = path.join(__dirname, 'inject.txt');

const RCON_OPTS = { host: '127.0.0.1', port: 25575, password: 'village-local-ctl' };
const BOT_NAMES = ['Rook','Willow','Fang','Digby','Pip','Sage','Juno','Moss','Ember','Echo'];

let rcon = null;
let botsProc = null;
let busy = false;
let manualStop = false;   // true after !sim stopbots — watchdog stands down
let emptyChecks = 0;      // consecutive watchdog checks with no bots online

// ---------- RCON ----------
async function rconCmd(cmd) {
    try {
        if (!rcon) {
            rcon = await Rcon.connect(RCON_OPTS);
            rcon.on('end', () => { rcon = null; });
        }
        return await rcon.send(cmd);
    } catch (e) {
        rcon = null;
        console.log(`[rcon] failed: ${e.message}`);
        return null;
    }
}

async function announce(msg) {
    console.log(`[sim] ${msg}`);
    await rconCmd(`tellraw @a {"text":"[SIM] ${msg.replace(/"/g, '\\"')}","color":"gold"}`);
}

// ---------- bot process control ----------
function startBots() {
    if (botsProc) return;
    console.log('[sim] starting bots...');
    // pipe bot output to a log so agent behavior is debuggable (rotate if >20MB)
    const botsLog = path.join(__dirname, 'bots.log');
    try { if (fs.existsSync(botsLog) && fs.statSync(botsLog).size > 20_000_000) fs.renameSync(botsLog, botsLog + '.old'); } catch {}
    const logFd = fs.openSync(botsLog, 'a');
    botsProc = spawn('node', ['main.js'], { cwd: MINDCRAFT_DIR, stdio: ['ignore', logFd, logFd], shell: true });
    botsProc.on('exit', () => { botsProc = null; });
}

function stopBots() {
    console.log('[sim] stopping bots...');
    if (botsProc) { try { botsProc.kill(); } catch {} botsProc = null; }
    // also kill any mindcraft main.js not started by us
    try {
        execSync(`powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \\"Name = 'node.exe'\\" | Where-Object { $_.CommandLine -like '*main.js*' -and $_.ProcessId -ne ${process.pid} } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"`);
    } catch {}
}

// ---------- command handlers ----------
async function handle(user, args) {
    const cmd = (args[0] || 'help').toLowerCase();

    if (cmd === 'help') {
        await announce('Commands: status, op, restart, reset, wipe <Bot|all>, spawn <x y z>, tp <Bot|all> <x y z>, tp <Bot> me, goal <Bot> <text>, day, night, stopbots, startbots');
        return;
    }

    if (cmd === 'status') {
        const list = await rconCmd('list');
        await announce(list ? list.replace(/§./g, '') : 'server unreachable');
        await announce(`bots process: ${botsProc ? 'running (started by sim)' : 'external or stopped'}`);
        return;
    }

    if (cmd === 'day')   { await rconCmd('time set day');   await announce('Time set to day.'); return; }
    if (cmd === 'night') { await rconCmd('time set night'); await announce('Time set to night.'); return; }

    if (cmd === 'op') {
        if (user === 'Console') return announce('Usage: type !sim op in game chat to op yourself.');
        await rconCmd(`op ${user}`);
        await announce(`${user} is now op. /give, /tp, /gamemode etc. are yours.`);
        return;
    }

    if (cmd === 'cmd') {
        const raw = args.slice(1).join(' ');
        if (!raw) return announce('Usage: !sim cmd <any server command>');
        const out = await rconCmd(raw);
        await announce(out ? out.replace(/§./g, '').slice(0, 200) : `Ran: ${raw}`);
        return;
    }

    if (cmd === 'stopbots')  { manualStop = true;  stopBots();  await announce('Bots stopped (watchdog paused until !sim startbots).'); return; }
    if (cmd === 'startbots') { manualStop = false; startBots(); await announce('Bots starting...'); return; }

    if (cmd === 'restart') {
        if (busy) return announce('Already working on it...');
        busy = true;
        await announce('Restarting all bots (world and memories kept)...');
        stopBots();
        setTimeout(() => { startBots(); busy = false; announce('Bots relaunching now.'); }, 3000);
        return;
    }

    if (cmd === 'wipe') {
        const who = args[1];
        if (!who) return announce('Usage: !sim wipe <Bot|all>');
        const targets = who.toLowerCase() === 'all' ? BOT_NAMES
            : BOT_NAMES.filter(b => b.toLowerCase() === who.toLowerCase());
        if (!targets.length) return announce(`Unknown bot '${who}'. Bots: ${BOT_NAMES.join(', ')}`);
        stopBots();
        for (const b of targets) {
            fs.rmSync(path.join(MINDCRAFT_DIR, 'bots', b), { recursive: true, force: true });
        }
        await announce(`Wiped memory of: ${targets.join(', ')}. Restarting bots...`);
        setTimeout(startBots, 3000);
        return;
    }

    if (cmd === 'spawn') {
        const [x, y, z] = args.slice(1);
        if ([x, y, z].some(v => v === undefined || isNaN(Number(v)))) return announce('Usage: !sim spawn <x> <y> <z>');
        await rconCmd(`setworldspawn ${x} ${y} ${z}`);
        for (const b of BOT_NAMES) await rconCmd(`tp ${b} ${x} ${y} ${z}`);
        await announce(`World spawn set and all bots teleported to ${x} ${y} ${z}.`);
        return;
    }

    if (cmd === 'tp') {
        const who = args[1];
        if (!who) return announce('Usage: !sim tp <Bot|all> <x y z | me>');
        const targets = who.toLowerCase() === 'all' ? BOT_NAMES
            : BOT_NAMES.filter(b => b.toLowerCase() === who.toLowerCase());
        if (!targets.length) return announce(`Unknown bot '${who}'.`);
        if ((args[2] || '').toLowerCase() === 'me') {
            for (const b of targets) await rconCmd(`tp ${b} ${user}`);
            await announce(`Teleported ${targets.join(', ')} to ${user}.`);
        } else {
            const [x, y, z] = args.slice(2);
            if ([x, y, z].some(v => v === undefined || isNaN(Number(v)))) return announce('Usage: !sim tp <Bot|all> <x> <y> <z>');
            for (const b of targets) await rconCmd(`tp ${b} ${x} ${y} ${z}`);
            await announce(`Teleported ${targets.join(', ')} to ${x} ${y} ${z}.`);
        }
        return;
    }

    if (cmd === 'goal') {
        const who = args[1];
        const text = args.slice(2).join(' ');
        if (!who || !text) return announce('Usage: !sim goal <Bot> <text>');
        const bot = BOT_NAMES.find(b => b.toLowerCase() === who.toLowerCase());
        if (!bot) return announce(`Unknown bot '${who}'.`);
        await rconCmd(`tell ${bot} ${user} says: ${text}`);
        await announce(`Sent to ${bot}.`);
        return;
    }

    if (cmd === 'reset') {
        if (busy) return announce('Already working on it...');
        busy = true;
        await announce('FULL RESET: stopping bots, regenerating world, wiping memories. ~1-2 min...');
        stopBots();
        // wipe only bot data dirs — bots/ also holds repo template files (execTemplate.js etc.)
        for (const b of BOT_NAMES) {
            fs.rmSync(path.join(MINDCRAFT_DIR, 'bots', b), { recursive: true, force: true });
        }
        await rconCmd('kick @a Simulation resetting - rejoin in ~2 minutes!');
        await rconCmd('stop');
        if (rcon) { try { rcon.end(); } catch {} rcon = null; }
        // wait for the java process to exit, delete worlds, restart
        setTimeout(() => {
            for (const w of ['world', 'world_nether', 'world_the_end']) {
                try { fs.rmSync(path.join(SERVER_DIR, w), { recursive: true, force: true }); } catch {}
            }
            spawn('java', ['-Dvillage.experiment=true', '-Xms2G', '-Xmx4G', '-jar', 'paper.jar', 'nogui'],
                { cwd: SERVER_DIR, stdio: 'ignore', shell: true, detached: true }).unref();
            // poll for server to come back, then start bots
            const poll = setInterval(async () => {
                const r = await rconCmd('list');
                if (r !== null) {
                    clearInterval(poll);
                    startBots();
                    busy = false;
                    await announce('Fresh world ready, bots joining with blank memories. Enjoy!');
                }
            }, 5000);
        }, 10000);
        return;
    }

    await announce(`Unknown command '${cmd}'. Try !sim help`);
}

// ---------- log tailing ----------
// Matches: "[03:32:02] [Async Chat Thread - #3/INFO]: [Not Secure] <staff1> !sim status"
// and whispers to nobody are not in log; public chat only.
const CHAT_RE = /\]:\s*(?:\[Not Secure\]\s*)?<([^>]+)>\s*!sim\b\s*(.*)$/;

let logPos = 0;
let logIno = -1;

function pollLog() {
    let st;
    try { st = fs.statSync(LOG_FILE); } catch { return; }
    if (st.ino !== logIno || st.size < logPos) { logIno = st.ino; logPos = 0; } // rotated/truncated
    if (st.size === logPos) return;
    const stream = fs.createReadStream(LOG_FILE, { start: logPos, end: st.size - 1, encoding: 'utf8' });
    let buf = '';
    stream.on('data', d => buf += d);
    stream.on('end', () => {
        logPos = st.size;
        for (const line of buf.split('\n')) {
            const m = line.match(CHAT_RE);
            if (!m) continue;
            const [, user, rest] = m;
            if (BOT_NAMES.includes(user)) continue; // bots can't control the sim
            console.log(`[sim] command from ${user}: !sim ${rest}`);
            handle(user, rest.trim().split(/\s+/).filter(Boolean)).catch(e => console.log('[sim] handler error:', e));
        }
    });
}

function pollInject() {
    let txt;
    try { txt = fs.readFileSync(INJECT_FILE, 'utf8'); } catch { return; }
    if (!txt.trim()) return;
    fs.writeFileSync(INJECT_FILE, '');
    for (const line of txt.split('\n')) {
        const t = line.trim();
        if (!t) continue;
        console.log(`[sim] injected command: ${t}`);
        handle('Console', t.replace(/^!sim\s*/i, '').split(/\s+/).filter(Boolean)).catch(e => console.log('[sim] handler error:', e));
    }
}

// ---------- watchdog ----------
// The bot process has died overnight before (Ollama outage -> mindserver crash).
// Every 60s: if bots should be running but aren't, resurrect them.
async function watchdog() {
    if (manualStop || busy) return;
    if (!botsProc) {
        console.log('[sim] watchdog: bot process is down — resurrecting...');
        await announce('Watchdog: bots were down, restarting them.');
        startBots();
        emptyChecks = 0;
        return;
    }
    // process alive but nobody home? (agents can all wedge while main.js survives)
    const list = await rconCmd('list');
    if (list === null) return; // server unreachable; not the bots' fault
    const online = BOT_NAMES.filter(b => list.includes(b)).length;
    if (online === 0) {
        emptyChecks++;
        if (emptyChecks >= 3) {
            console.log('[sim] watchdog: process alive but 0 bots online for 3 checks — hard restart');
            await announce('Watchdog: bots wedged, hard-restarting them.');
            stopBots();
            setTimeout(startBots, 5000);
            emptyChecks = 0;
        }
    } else {
        emptyChecks = 0;
    }
}

console.log('[sim] Village controller running. Watching for !sim commands in game chat.');
console.log(`[sim] log: ${LOG_FILE}`);
setInterval(pollLog, 1000);
setInterval(pollInject, 2000);
setInterval(watchdog, 60000);
pollLog();
