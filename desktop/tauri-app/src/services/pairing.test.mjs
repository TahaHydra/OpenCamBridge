import assert from 'node:assert/strict';
import test from 'node:test';
import { PairingSession, formatPairingCode, pairingSecondsRemaining } from './pairing.ts';

const invitation = {
  id: 'invitation', name: 'My PC', code: '00123456', qrSvg: '<svg/>',
  expiresAt: 120000, hosts: ['192.168.1.4'], port: 40000,
};
const phone = { phoneId: 'phone', name: 'Pixel', lastHost: '192.168.1.5', port: 8080, pairedAt: 1000 };
const deferred = () => {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
};
function setup(overrides = {}) {
  const cancelled = [];
  const states = [];
  const api = {
    start: async () => invitation,
    status: async () => ({ state: 'waiting' }),
    cancel: async id => { cancelled.push(id); },
    ...overrides,
  };
  const session = new PairingSession(api, state => states.push(state), () => 0);
  return { session, cancelled, states };
}

test('display preserves leading zeros and expires exactly at the invitation deadline', () => {
  assert.equal(formatPairingCode('00123456'), '0012 3456');
  assert.equal(pairingSecondsRemaining(invitation, 118001), 2);
  assert.equal(pairingSecondsRemaining(invitation, 120000), 0);
  assert.equal(pairingSecondsRemaining(invitation, 130000), 0);
});

test('closing while start is pending cancels its late invitation without redisplaying it', async () => {
  const pending = deferred();
  const { session, states, cancelled } = setup({ start: () => pending.promise });
  const starting = session.start();
  session.close();
  const count = states.length;
  pending.resolve(invitation);
  await starting;
  assert.deepEqual(cancelled, ['invitation']);
  assert.equal(states.length, count);
});

test('a late paired response cannot replace cancellation or a new invitation', async () => {
  const pending = deferred();
  let starts = 0;
  const { session, states } = setup({
    start: async () => ({ ...invitation, id: `invite-${++starts}` }),
    status: () => pending.promise,
  });
  await session.start();
  const poll = session.poll();
  await session.cancel();
  await session.start();
  pending.resolve({ state: 'paired', phone });
  await poll;
  assert.equal(states.at(-1).state, 'waiting');
  assert.equal(states.at(-1).invitation.id, 'invite-2');
  assert.equal(states.some(state => state.state === 'paired'), false);
});

test('expiry clears QR and code, cancels the listener and never automatically renews', async () => {
  let now = 0;
  let starts = 0;
  const cancelled = [];
  const states = [];
  const session = new PairingSession({
    start: async () => { starts++; return invitation; },
    status: async () => ({ state: 'waiting' }),
    cancel: async id => { cancelled.push(id); },
  }, state => states.push(state), () => now);
  await session.start();
  now = 120000;
  await session.poll();
  await session.poll();
  assert.deepEqual(states.at(-1), { state: 'expired' });
  assert.deepEqual(cancelled, ['invitation']);
  assert.equal(starts, 1);
});

test('only one status request can run, and pairing stops polling and removes invitation secrets', async () => {
  const pending = deferred();
  let polls = 0;
  const { session, states, cancelled } = setup({ status: () => { polls++; return pending.promise; } });
  await session.start();
  const poll = session.poll();
  await session.poll();
  assert.equal(polls, 1);
  pending.resolve({ state: 'paired', phone });
  await poll;
  await session.poll();
  assert.deepEqual(states.at(-1), { state: 'paired', phone });
  assert.equal(polls, 1);
  // Keep the listener available for a bounded duplicate /finish ACK retry.
  assert.deepEqual(cancelled, []);
  session.close();
  assert.deepEqual(cancelled, ['invitation']);
});

test('a polling error closes the temporary listener and leaves a retryable error', async () => {
  const { session, states, cancelled } = setup({ status: async () => { throw new Error('offline'); } });
  await session.start();
  await session.poll();
  assert.equal(states.at(-1).state, 'error');
  assert.equal(states.at(-1).invitation, undefined);
  assert.deepEqual(cancelled, ['invitation']);
});
