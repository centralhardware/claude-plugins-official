const tg = window.Telegram && Telegram.WebApp;
if (tg) {
  tg.ready(); tg.expand();
  // Open straight to full screen (Bot API 8.0+); older clients just stay expanded.
  try { if (tg.requestFullscreen && tg.isVersionAtLeast('8.0')) tg.requestFullscreen(); } catch (e) {}
  // Otherwise a vertical swipe in the terminal drags/closes the Mini App instead of scrolling.
  try { if (tg.disableVerticalSwipes) tg.disableVerticalSwipes(); } catch (e) {}
  if (tg.onEvent) ['fullscreenChanged', 'safeAreaChanged', 'contentSafeAreaChanged']
    .forEach(ev => tg.onEvent(ev, () => setTimeout(() => build(false), 50)));
}
const box = document.getElementById('term');
const statusEl = document.getElementById('status');
const dot = document.getElementById('dot');
// A readable font, lines wrapped to the phone's width — the pane's own width
// (often 200+ columns) would otherwise shrink the text to nothing.
let fontSize = 12;
try { fontSize = parseInt(localStorage.getItem('fontSize')) || 12; } catch (e) {}
let term = null, lastMsg = null, shown = null, pending = false;

let shownCols = 0;
function geom() {
  return { cols: Math.max(20, Math.floor(box.clientWidth / (fontSize * 0.6))),
           rows: Math.max(5, Math.floor(box.clientHeight / (fontSize * 1.2))) };
}
function atBottom() { return box.scrollTop + box.clientHeight >= box.scrollHeight - 8; }

function build(force) {
  const g = geom();
  // Telegram fires resize on every viewport twitch — rebuild only when the width really changes.
  if (term && !force && g.cols === shownCols) return;
  if (term) term.dispose();
  shownCols = g.cols;
  term = new Terminal({ cols: g.cols, rows: g.rows, fontSize, disableStdin: true, cursorBlink: false,
    scrollback: 20000, convertEol: true, theme: { background: '#0d1117' } });
  term.open(box);
  shown = null;
  if (lastMsg) { const m = lastMsg; lastMsg = null; render(m); }
}

function zoom(d) {
  fontSize = Math.max(8, Math.min(20, fontSize + d));
  try { localStorage.setItem('fontSize', String(fontSize)); } catch (e) {}
  build(true);
}
document.getElementById('minus').onclick = () => zoom(-1);
document.getElementById('plus').onclick = () => zoom(1);
let resizeTimer = null;
window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(() => build(false), 150); });
// Back at the bottom → apply the snapshot that arrived while reading.
box.addEventListener('scroll', () => { if (pending && atBottom()) { const m = lastMsg; lastMsg = null; render(m); } }, { passive: true });

// Display rows the snapshot needs at this width (ANSI codes stripped).
function rowsFor(text, cols) {
  let n = 0;
  for (const line of text.split('\n')) {
    const len = line.replace(/\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07/g, '').length;
    n += Math.max(1, Math.ceil(len / cols));
  }
  return n;
}

function render(msg) {
  lastMsg = msg;
  if (!pending) statusEl.textContent = 'live · ' + new Date(msg.ts).toLocaleTimeString();
  if (shown === msg.data) return; // unchanged snapshot — don't touch the screen
  // Scrolled up to read: don't redraw under the finger. The newest snapshot is
  // applied once you scroll back to the bottom.
  if (shown && !atBottom()) { pending = true; statusEl.textContent = 'paused · scroll down for live'; return; }
  pending = false; shown = msg.data;
  statusEl.textContent = 'live · ' + new Date(msg.ts).toLocaleTimeString();
  const data = msg.data.replace(/\s+$/, '');
  const g = geom();
  // Grow the terminal to the whole snapshot so the page, not xterm, does the scrolling.
  term.resize(g.cols, Math.max(g.rows, rowsFor(data, g.cols)));
  // Clear + redraw in ONE write so xterm paints a single frame (no flicker).
  term.write('\x1b[H\x1b[2J\x1b[3J' + data, () => {
    const b = term.buffer.active;           // fix up any miscount (wide chars, etc.)
    const used = b.baseY + b.cursorY + 1;
    if (used > term.rows) term.resize(g.cols, used);
    requestAnimationFrame(() => { box.scrollTop = box.scrollHeight; });
  });
}

let ws = null, retry = null;
function connect() {
  if (!tg || !tg.initData) { statusEl.textContent = 'open this from the bot in Telegram'; return; }
  clearTimeout(retry); retry = null;
  if (ws && ws.readyState <= 1) return; // already open or opening
  const sock = ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
  sock.onopen = () => sock.send(tg.initData);
  sock.onmessage = e => { dot.className = 'live'; render(JSON.parse(e.data)); };
  sock.onclose = e => {
    if (sock !== ws) return;
    dot.className = '';
    if (e.code === 4401) { statusEl.textContent = 'not authorized'; return; }
    // Keep the last screen; while hidden don't retry at all — resume() reconnects on return.
    if (document.hidden) return;
    statusEl.textContent = 'reconnecting…';
    retry = setTimeout(connect, 2000);
  };
}
// Minimising suspends the webview and the OS drops the socket: reconnect at once
// when it comes back instead of waiting out the retry timer, keeping the screen as is.
function resume() { if (!document.hidden) connect(); }
document.addEventListener('visibilitychange', resume);
window.addEventListener('focus', resume);
if (tg && tg.onEvent) tg.onEvent('activated', resume);
build();
connect();
