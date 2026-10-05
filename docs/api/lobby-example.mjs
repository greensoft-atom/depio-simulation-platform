#!/usr/bin/env node
// The lobby WebSocket, walked through with two players, every frame printed as it goes and
// every answer checked: authentication, a ping, the errors, a party, a seat in the public arena,
// and a duel from the queue to the arena's ticket (which needs players connected to the lobby,
// so it cannot be shown over HTTP alone). Optionally an operator's notice, with the admin API.
// Node 22 or later (its WebSocket and fetch are built in); nothing to install.
//
//   node lobby-example.mjs [platformUrl] [lobbyUrl] [adminUrl adminToken]
//   node lobby-example.mjs http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby
//   node lobby-example.mjs https://a.example.com wss://a.example.com/lobby
//
// It registers two new players (bob_<time>, cy_<time>). Exits 1 if anything answered otherwise.

const [platform = 'http://127.0.0.1:8080', lobbyUrl = 'ws://127.0.0.1:8081/lobby', adminUrl, adminToken] = process.argv.slice(2);
let failed = 0;

function log(who, arrow, what) {
  console.log(`${who} ${arrow} ${typeof what === 'string' ? what : JSON.stringify(what)}`);
}

function check(ok, what) {
  if (!ok) {
    failed++;
    console.log(`   FAIL ${what}`);
  }
}

async function http(method, path, body, token) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(path.startsWith('http') ? path : platform + path,
    { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

/** One lobby connection: what it receives is kept until a wait() takes it. */
class Lobby {
  constructor(name) {
    this.name = name;
    this.received = [];
    this.waiting = [];
    this.nextId = 1;
  }

  open() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(lobbyUrl);
      this.ws.onopen = () => { log(this.name, '==', `connected to ${lobbyUrl}`); resolve(); };
      this.ws.onerror = (e) => reject(new Error(`${this.name}: ${e.message || 'WebSocket error'}`));
      this.ws.onmessage = (e) => this.take(JSON.parse(e.data));
      this.ws.onclose = (e) => {
        this.closedWith = e;
        log(this.name, '==', `closed: ${e.code} ${e.reason}`);
      };
    });
  }

  take(message) {
    log(this.name, '<-', message);
    const w = this.waiting.find((x) => x.match(message));
    if (w) {
      this.waiting.splice(this.waiting.indexOf(w), 1);
      clearTimeout(w.timer);
      w.resolve(message);
    } else {
      this.received.push(message);
    }
  }

  /** The first message received, or to come within ms, that match() accepts. */
  wait(match, ms = 15000) {
    const i = this.received.findIndex(match);
    if (i >= 0) return Promise.resolve(this.received.splice(i, 1)[0]);
    return new Promise((resolve) => {
      const w = { match, resolve };
      w.timer = setTimeout(() => {
        this.waiting.splice(this.waiting.indexOf(w), 1);
        resolve(null);
      }, ms);
      this.waiting.push(w);
    });
  }

  /** Sends {t, id, d} and waits for the answer carrying that id. */
  call(t, d) {
    const message = { t, id: this.nextId++ };
    if (d !== undefined) message.d = d;
    log(this.name, '->', message);
    this.ws.send(JSON.stringify(message));
    return this.wait((m) => m.id === message.id);
  }

  sendText(text) {
    log(this.name, '->', text);
    this.ws.send(text);
  }

  close() {
    this.ws.close(1000, 'done');
  }
}

const pushed = (t) => (m) => m.t === t;

async function player(name, run) {
  const username = `${name}_${run}`;
  const display = name[0].toUpperCase() + name.slice(1);
  const registered = await http('POST', '/v1/accounts', { username, password: 'example-password-1', displayName: display });
  check(registered.status === 201, `register ${username}: ${registered.status}`);
  const session = await http('POST', '/v1/sessions', { username, password: 'example-password-1' });
  check(session.status === 200, `log in ${username}: ${session.status}`);
  return { name: display, playerId: session.body.playerId, token: session.body.token };
}

async function main() {
  const run = Date.now().toString(36);
  console.log(`--- two players, over ${platform}`);
  const b = await player('bob', run);
  const c = await player('cy', run);
  console.log(`    Bob is player ${b.playerId}, Cy is player ${c.playerId}`);

  console.log('--- connect, and authenticate with the first message');
  const lb = new Lobby('Bob');
  const lc = new Lobby('Cy');
  await lb.open();
  await lc.open();
  let r = await lb.call('queue.join', { mode: 'duel' });
  check(r?.t === 'error' && r.d.code === 'not_authenticated', 'anything before auth: not_authenticated');
  r = await lb.call('auth', { token: b.token });
  check(r?.t === 'auth.ok' && r.d.playerId === b.playerId, 'auth.ok with the player id');
  r = await lc.call('auth', { token: c.token });
  check(r?.t === 'auth.ok', 'auth.ok for Cy');

  console.log('--- a ping, and what the gateway refuses');
  r = await lb.call('ping');
  check(r?.t === 'ping.ok', 'ping.ok');
  r = await lb.call('auth', { token: b.token });
  check(r?.t === 'error' && r.d.code === 'already_authenticated', 'auth twice: already_authenticated');
  r = await lb.call('dance');
  check(r?.t === 'error' && r.d.code === 'unknown_type', 'an unknown type: unknown_type');
  lb.sendText('{not json');
  r = await lb.wait((m) => m.t === 'error' && m.d.code === 'bad_json');
  check(r, 'a frame that is not JSON: bad_json');
  r = await lb.call('party.say', { phraseId: 99 });
  check(r?.t === 'error' && r.d.code === 'unknown_phrase', 'a refusal from the platform keeps its code: unknown_phrase');

  console.log('--- a party: Bob invites Cy, Cy accepts, Bob says a phrase, Bob leaves');
  r = await lb.call('party.invite', { playerId: c.playerId });
  check(r?.t === 'party.invite.ok' && r.d.leader === b.playerId, 'party.invite.ok, Bob leading');
  const invite = await lc.wait(pushed('evt.party.invite'));
  check(invite?.d.from === b.playerId, 'Cy is pushed evt.party.invite');
  r = await lc.call('party.accept', { partyId: invite.d.partyId });
  check(r?.t === 'party.accept.ok' && r.d.members.length === 2, 'party.accept.ok, two members');
  check(await lb.wait(pushed('evt.party.update')), 'Bob is pushed evt.party.update');
  check(await lc.wait(pushed('evt.party.update')), 'Cy is pushed evt.party.update');
  r = await lb.call('party.say', { phraseId: 3 });
  check(r?.t === 'party.say.ok', 'party.say.ok');
  check(await lc.wait(pushed('evt.party.said')), 'Cy is pushed evt.party.said');
  check(await lb.wait(pushed('evt.party.said')), 'Bob hears himself too');
  r = await lb.call('party.leave');
  check(r?.t === 'party.leave.ok' && r.d.partyId === null && r.d.was === invite.d.partyId, 'party.leave.ok, the ended form');
  check(await lc.wait(pushed('evt.party.update')), 'Cy is pushed the ended party');

  console.log('--- a seat in the public arena');
  r = await lb.call('match.request');
  check(r?.t === 'match.request.ok' && r.d.ticketId, 'match.request.ok with a ticket');

  console.log('--- a duel: both queue, the matcher asks, both accept, each gets a ticket');
  r = await lb.call('queue.join', { mode: 'duel' });
  check(r?.t === 'queue.join.ok' && r.d.state === 'queued', 'queue.join.ok, queued');
  r = await lc.call('queue.join', { mode: 'duel' });
  check(r?.t === 'queue.join.ok', 'queue.join.ok for Cy');
  const readyB = await lb.wait(pushed('evt.match.ready'));
  const readyC = await lc.wait(pushed('evt.match.ready'));
  check(readyB && readyC && readyB.d.matchUid === readyC.d.matchUid, 'both pushed evt.match.ready for one match');
  r = await lb.call('match.accept', { matchUid: readyB.d.matchUid });
  check(r?.t === 'match.accept.ok', 'match.accept.ok');
  r = await lc.call('match.accept', { matchUid: readyC.d.matchUid });
  check(r?.t === 'match.accept.ok', 'match.accept.ok for Cy');
  const foundB = await lb.wait(pushed('evt.match.found'));
  const foundC = await lc.wait(pushed('evt.match.found'));
  check(foundB?.d.mode === 'duel' && foundC?.d.ticketId && foundB.d.ticketId !== foundC.d.ticketId,
    'both pushed evt.match.found, a ticket each');
  console.log(`    Bob now opens TCP to ${foundB?.d.arenaHost}:${foundB?.d.arenaPort}${foundB?.d.tls ? ' with TLS' : ''}`
    + ' and sends Join with his ticket, within 60 s (docs/detailed-design/02-networking.md §3)');
  r = await lb.call('queue.leave');
  check(r?.t === 'queue.leave.ok' && r.d.state === 'none', 'queue.leave.ok drops the grant');
  await lc.call('queue.leave');

  if (adminUrl && adminToken) {
    console.log('--- an operator\'s notice reaches every connection');
    const sent = await http('POST', `${adminUrl}/admin/notice`, { text: 'Maintenance at 02:00 UTC, about ten minutes.', reason: 'planned maintenance' }, adminToken);
    check(sent.status === 202, `the admin API sends it: ${sent.status}`);
    check(await lb.wait(pushed('evt.notice')), 'Bob is pushed evt.notice');
    check(await lc.wait(pushed('evt.notice')), 'Cy is pushed evt.notice');
  }

  console.log('--- the same player connecting again replaces the first connection');
  const again = new Lobby('Cy2');
  await again.open();
  r = await again.call('auth', { token: c.token });
  check(r?.t === 'auth.ok', 'auth.ok on the new connection');
  check(await lc.wait(pushed('evt.session.replaced')), 'the old one is pushed evt.session.replaced, then closed');

  console.log('--- a token that is no session closes the connection');
  const stranger = new Lobby('Dee');
  await stranger.open();
  r = await stranger.call('auth', { token: 'A'.repeat(43) });
  check(r?.t === 'error' && r.d.code === 'invalid_session', 'invalid_session');

  await new Promise((resolve) => setTimeout(resolve, 500));
  lb.close();
  again.close();
  await new Promise((resolve) => setTimeout(resolve, 500));
  console.log(failed === 0 ? '--- every answer as expected' : `--- ${failed} answers not as expected`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
