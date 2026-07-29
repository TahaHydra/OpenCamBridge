import assert from 'node:assert/strict';
import test from 'node:test';

import {
  BrowserUnsupportedError,
  desktopInvoke,
  isTauriRuntime,
} from './desktopBridge.ts';

test('browser runtime is rejected before Tauri internals are dereferenced', async () => {
  const previousWindow = globalThis.window;
  globalThis.window = {};
  try {
    assert.equal(isTauriRuntime(), false);
    await assert.rejects(
      desktopInvoke('list_devices'),
      (error) => error instanceof BrowserUnsupportedError
        && error.message.includes('not running inside the desktop app'),
    );
  } finally {
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
  }
});

test('desktop runtime delegates through the injected Tauri bridge', async () => {
  const previousWindow = globalThis.window;
  let received;
  globalThis.window = {
    __TAURI_INTERNALS__: {
      invoke: async (command, args) => {
        received = { command, args };
        return ['phone'];
      },
    },
  };
  try {
    assert.equal(isTauriRuntime(), true);
    assert.deepEqual(await desktopInvoke('list_devices', { refresh: true }), ['phone']);
    assert.deepEqual(received, { command: 'list_devices', args: { refresh: true } });
  } finally {
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
  }
});
