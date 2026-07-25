'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Parser } = require(path.resolve(__dirname, '../../android/app/src/main/assets/ocb2-parser.js'));
const corpus = JSON.parse(fs.readFileSync(path.resolve(__dirname, 'ocb2-corpus.json'), 'utf8'));

const bytes = value => Uint8Array.from(Buffer.from(value, 'hex'));

for (const fixture of corpus.cases) {
  const parser = new Parser();
  const records = [];
  let caught = null;
  for (const action of fixture.actions) {
    if (action.reset) {
      parser.reset();
      continue;
    }
    const input = bytes(action.hex || '');
    const sizes = action.fragmentSizes || [input.length];
    let offset = 0;
    const feed = part => {
      parser.push(part);
      try {
        for (let record; (record = parser.next()) !== null;) records.push(record);
      } catch (error) {
        caught = error;
      }
    };
    for (const size of sizes) {
      const end = Math.min(offset + size, input.length);
      feed(input.slice(offset, end));
      offset = end;
    }
    if (offset < input.length) feed(input.slice(offset));
  }
  if (fixture.expectedError) {
    assert.equal(caught && caught.code, fixture.expectedError, fixture.id);
    continue;
  }
  assert.equal(caught, null, fixture.id);
  assert.equal(records.length, fixture.expectedRecords.length, fixture.id);
  records.forEach((actual, index) => {
    const expected = fixture.expectedRecords[index];
    assert.equal(actual.type, expected.type, fixture.id);
    assert.equal(actual.flags, expected.flags, fixture.id);
    assert.equal(actual.sequence, expected.sequence, fixture.id);
    // Defaults to 0 for the cases predating the field, whose headers carry the old
    // reserved zero — so this asserts backward compatibility rather than skipping it.
    assert.equal(actual.sendDeltaUs, expected.sendDeltaUs ?? 0, fixture.id + ' send delta');
    assert.equal(Buffer.from(actual.payload).toString('hex'), expected.payloadHex, fixture.id);
  });
  let waitingForKeyframe = true;
  const accepted = [];
  for (const record of records) {
    if (record.type === 1 || (record.flags & 4)) waitingForKeyframe = true;
    if (record.type === 3) {
      if (record.flags & 2) waitingForKeyframe = false;
      if (!waitingForKeyframe) accepted.push(record.sequence);
    }
  }
  assert.deepEqual(accepted, fixture.acceptedVideoSequences, fixture.id);
}

console.log('OCB2_BROWSER_CONFORMANCE=PASSED cases=' + corpus.cases.length);
