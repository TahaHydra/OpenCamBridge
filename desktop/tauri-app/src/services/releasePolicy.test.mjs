import test from 'node:test';
import assert from 'node:assert/strict';
import { stableVersion, newerRelease, companionWarning } from './releasePolicy.js';
test('release comparison uses numeric stable versions, rejects prereleases and malformed tags', () => {
  assert.equal(newerRelease('0.9.9', 'v0.10.0'), true);
  assert.equal(newerRelease('1.0.0', '0.10.0'), false);
  assert.equal(newerRelease('0.1.0', 'v0.1.0'), false);
  for (const value of ['v1.0.0-beta', '1.2', '1.2.3.4', '01.2.3', '9007199254740992.0.0', null]) assert.equal(stableVersion(value), null);
});
test('compatibility follows declared protocols and minimum versions, not historical fake version strings', () => {
  assert.equal(companionWarning({ version: '2.0.0' }), '');
  assert.equal(companionWarning({ controlProtocol: 1, streamProtocol: 2, minimumCompanionVersion: '0.1.0' }), '');
  assert.match(companionWarning({ controlProtocol: 2 }), /control protocol/);
  assert.match(companionWarning({ streamProtocol: 3 }), /video protocol/);
  assert.match(companionWarning({ minimumCompanionVersion: '0.2.0' }), /requires/);
});
