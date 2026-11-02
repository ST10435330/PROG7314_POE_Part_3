import test from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { createApp } from "./app.js";

const TOKEN = "test-device-token-1234567890";

// Transactional Firestore double: records commit only after a successful callback.
function memoryDb() {
  const records = new Map();
  let queue = Promise.resolve();
  return {
    records,
    collection: name => ({ doc: id => ({ path: `${name}/${id}` }) }),
    runTransaction(callback) {
      const job = queue.then(async () => {
        const writes = [];
        const result = await callback({
          get: async ref => {
            assert.equal(writes.length, 0, "Read before writing");
            return { data: () => structuredClone(records.get(ref.path)) };
          },
          set: (ref, value, options) => writes.push(() => records.set(
            ref.path, structuredClone(options?.merge
              ? { ...records.get(ref.path), ...value } : value)
          )),
          delete: ref => writes.push(() => records.delete(ref.path))
        });
        writes.forEach(write => write());
        return result;
      });
      queue = job.catch(() => {});
      return job;
    }
  };
}

async function start(t, messagingAvailable = true) {
  const db = memoryDb();
  const sent = [];
  const messaging = {
    send: async message => { sent.push(message); return "fake-message-id"; }
  };
  const auth = { verifyIdToken: async token => {
    if (token === "invalid") {
      throw Object.assign(new Error("Invalid"), { code: "auth/invalid-id-token" });
    }
    return { uid: token };
  } };
  const server = createApp({ auth, db,
    messaging: messagingAvailable ? messaging : undefined
  }).listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}/api/notifications`;
  async function send(action, user = "user-a", body = { token: TOKEN }) {
    const response = await fetch(`${base}/${action}`, {
      method: "POST",
      headers: { "Content-Type": "application/json",
        ...(user ? { Authorization: `Bearer ${user}` } : {}) },
      body: JSON.stringify(body)
    });
    return { status: response.status,
      body: response.status === 204 ? null : await response.json() };
  }
  return { db, sent, messaging, send };
}

test("notification routes require valid authentication", async t => {
  const { send, db } = await start(t);
  for (const action of ["register", "unregister", "test"]) {
    assert.equal((await send(action, null)).status, 401);
    assert.equal((await send(action, "invalid")).status, 401);
  }
  assert.equal(db.records.size, 0);
});

test("invalid tokens and client-supplied owners are rejected", async t => {
  const { send, db } = await start(t);
  for (const body of [{}, [], { token: "short" }, { token: "x".repeat(4097) },
    { token: `${TOKEN}\n` }, { token: TOKEN, userId: "user-b" }]) {
    for (const action of ["register", "unregister", "test"]) {
      assert.equal((await send(action, "user-a", body)).status, 400);
    }
  }
  assert.equal(db.records.size, 0);
});

test("registration is repeatable and has one authenticated owner", async t => {
  const { send, db } = await start(t);
  assert.equal((await send("register")).status, 200);
  assert.equal((await send("register")).status, 200);
  assert.equal(db.records.size, 1);
  assert.equal([...db.records.values()][0].userId, "user-a");
  const key = [...db.records.keys()][0];
  assert.match(key, /^pushDevices\/[a-f0-9]{64}$/);
  assert.equal(key.includes(TOKEN), false);
  await send("register", "user-b");
  assert.equal(db.records.size, 1);
  assert.equal([...db.records.values()][0].userId, "user-b");
});

test("old account cannot send to or unregister a reassigned device", async t => {
  const { send, db, sent } = await start(t);
  await send("register");
  await send("register", "user-b");
  assert.equal((await send("test")).status, 404);
  assert.equal((await send("unregister")).status, 204);
  assert.equal([...db.records.values()][0].userId, "user-b");
  assert.equal(sent.length, 0);
  assert.equal((await send("test", "user-b")).status, 202);
});

test("unregister is idempotent and prevents later sends", async t => {
  const { send, db, sent } = await start(t);
  await send("register");
  assert.equal((await send("unregister")).status, 204);
  assert.equal((await send("unregister")).status, 204);
  assert.equal(db.records.size, 0);
  assert.equal((await send("test")).status, 404);
  assert.equal(sent.length, 0);
});

test("only the owner's registered device receives a fixed data message", async t => {
  const { send, sent } = await start(t);
  assert.equal((await send("test")).status, 404);
  await send("register");
  const response = await send("test");
  assert.deepEqual(response, { status: 202, body: { accepted: true } });
  assert.equal(sent.length, 1);
  assert.equal(sent[0].token, TOKEN);
  assert.equal(sent[0].data.userId, "user-a");
  assert.equal(sent[0].data.type, "test");
  assert.equal(sent[0].data.body, "Push notifications are connected.");
  assert.ok(sent[0].data.eventId);
  assert.equal(sent[0].notification, undefined);
  assert.deepEqual(sent[0].android, { priority: "high", ttl: 60_000 });
});

test("simultaneous tests send once and registration cannot reset cooldown", async t => {
  const { send, sent } = await start(t);
  await send("register");
  const results = await Promise.all([send("test"), send("test")]);
  assert.deepEqual(results.map(result => result.status).sort(), [202, 429]);
  await send("register");
  assert.equal((await send("test")).status, 429);
  assert.equal(sent.length, 1);
});

test("FCM expired-device response removes its registration", async t => {
  const { send, db, messaging } = await start(t);
  await send("register");
  messaging.send = async () => {
    throw Object.assign(new Error("Expired"), { code: "messaging/registration-token-not-registered" });
  };
  const response = await send("test");
  assert.equal(response.status, 410);
  assert.equal(response.body.error.code, "DEVICE_EXPIRED");
  assert.equal(db.records.size, 0);
});

test("late FCM error does not delete a refreshed registration", async t => {
  const { send, db, messaging } = await start(t);
  await send("register");
  messaging.send = async () => {
    await send("register");
    throw Object.assign(new Error("Expired"), { code: "messaging/registration-token-not-registered" });
  };
  assert.equal((await send("test")).status, 410);
  assert.equal(db.records.size, 1);
});

test("provider failures retain registration and hide provider details", async t => {
  const { send, db, messaging } = await start(t);
  await send("register");
  messaging.send = async () => {
    throw Object.assign(new Error(`Private response ${TOKEN}`), { code: "messaging/server-unavailable" });
  };
  const response = await send("test");
  assert.equal(response.status, 503);
  assert.equal(JSON.stringify(response).includes(TOKEN), false);
  assert.equal(db.records.size, 1);
});

test("missing messaging service reports unavailable", async t => {
  const { send, db } = await start(t, false);
  await send("register");
  assert.equal((await send("test")).status, 503);
  assert.equal(db.records.size, 1);
});