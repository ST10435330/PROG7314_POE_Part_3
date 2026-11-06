import test from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { createApp } from "./app.js";

// In-memory dependencies and loopback HTTP only. No real project or credentials.
function memoryDb() {
  const records = new Map();
  let queue = Promise.resolve();
  const doc = path => ({ path, id: path.split("/").at(-1),
    collection: name => collection(`${path}/${name}`),
    get: async () => read(doc(path)),
    set: async data => records.set(path, structuredClone(data)) });
  const collection = path => ({ path, doc: id => doc(`${path}/${id}`),
    get: async () => ({ docs: [...records.keys()].filter(key =>
      key.startsWith(path + "/") && !key.slice(path.length + 1).includes("/")
    ).map(key => read(doc(key))) }) });
  const read = ref => ({ exists: records.has(ref.path), id: ref.id,
    data: () => structuredClone(records.get(ref.path)) });
  const db = { records, collection, failReadyOnce: false,
    runTransaction(fn) {
      const job = queue.then(async () => {
        const writes = [];
        const result = await fn({ get: async ref => {
          assert.equal(writes.length, 0, "Transaction reads precede writes");
          return read(ref);
        }, set: (ref, data) => writes.push([ref.path, structuredClone(data)]) });
        if (db.failReadyOnce && writes.some(([, data]) => data.state === "ready")) {
          db.failReadyOnce = false;
          throw new Error("Synthetic database outage after blob upload");
        }
        writes.forEach(([path, data]) => records.set(path, data));
        return result;
      });
      queue = job.catch(() => {});
      return job;
    }
  };
  return db;
}

function memoryStorage() {
  const files = new Map();
  const cloud = { files, calls: [], failUpload: null, failRemove: false, afterUpload: null,
    bucket: "task-attachments", client: { storage: { from: bucket => {
      assert.equal(bucket, "task-attachments");
      return {
        async upload(path, bytes, options) {
          cloud.calls.push(["upload", path]);
          assert.equal(options.upsert, false);
          if (cloud.failUpload) return { error: cloud.failUpload };
          if (files.has(path)) return { error: { statusCode: "409", code: "Duplicate" } };
          files.set(path, Buffer.from(bytes));
          if (cloud.afterUpload) await cloud.afterUpload();
          return { data: { path }, error: null };
        },
        async download(path) {
          cloud.calls.push(["download", path]);
          return files.has(path) ? { data: new Blob([files.get(path)]), error: null }
            : { data: null, error: { code: "NoSuchKey" } };
        },
        async remove(paths) {
          cloud.calls.push(["remove", ...paths]);
          if (cloud.failRemove) return { error: { code: "SyntheticOutage", message: "SECRET-provider-details" } };
          paths.forEach(path => files.delete(path));
          return { data: [], error: null };
        }
      };
    } } }
  };
  return cloud;
}

const id = "11111111-1111-4111-8111-111111111111";
const content = Buffer.from("%PDF-1.7\nStudySync test notes");
const path = `/api/tasks/task-1/attachments/${id}`;

async function start(t, enabled = true) {
  const db = memoryDb();
  for (const uid of ["user-a", "user-b"]) db.records.set(`users/${uid}/tasks/task-1`, { title: "Study" });
  const storage = memoryStorage();
  const auth = { verifyIdToken: async token => {
    if (!["user-a", "user-b"].includes(token)) throw Object.assign(new Error("Invalid test token"),
      { code: "auth/invalid-id-token" });
    return { uid: token };
  } };
  const server = createApp({ auth, db, attachmentStorage: enabled ? storage : null }).listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  const send = (method, target = path, options = {}) => fetch(base + target, {
    method,
    headers: { Authorization: `Bearer ${options.user || "user-a"}`,
      ...(method === "PUT" ? { "Content-Type": options.type || "application/pdf",
        "X-Attachment-Name": options.name ?? encodeURIComponent("Notes.pdf") } : {}),
      ...options.headers },
    body: method === "PUT" ? options.bytes ?? content : undefined
  });
  return { db, storage, send, base };
}

test("attachment routes reject unauthenticated requests before storage access", async t => {
  const f = await start(t);
  for (const method of ["GET", "PUT", "DELETE"]) {
    const response = await f.send(method, path, { user: "invalid" });
    assert.equal(response.status, 401);
    await response.json();
  }
  assert.equal(f.storage.calls.length, 0);
});

test("disabled attachment storage leaves health available", async t => {
  const f = await start(t, false);
  assert.equal((await f.send("GET")).status, 503);
  assert.equal((await fetch(f.base + "/api/health")).status, 200);
});

test("upload list and verified download retain Unicode names without exposing internal fields", async t => {
  const f = await start(t);
  const response = await f.send("PUT", path, { name: encodeURIComponent("Amanothi - μ.pdf") });
  assert.equal(response.status, 200);
  const entry = await response.json();
  assert.deepEqual(entry, { id, taskId: "task-1", name: "Amanothi - μ.pdf",
    contentType: "application/pdf", size: content.length });
  assert.deepEqual(await (await f.send("GET", "/api/tasks/task-1/attachments")).json(), [entry]);
  const download = await f.send("GET");
  assert.equal(download.status, 200);
  assert.equal(download.headers.get("content-type"), "application/pdf");
  assert.equal(download.headers.get("cache-control"), "no-store");
  assert.equal(download.headers.get("x-content-type-options"), "nosniff");
  assert.deepEqual(Buffer.from(await download.arrayBuffer()), content);
  assert.equal(f.storage.files.size, 1);
});

test("downloads reject stored bytes that no longer match the manifest", async t => {
  const f = await start(t);
  await f.send("PUT");
  const objectPath = [...f.storage.files.keys()][0];
  f.storage.files.set(objectPath, Buffer.alloc(content.length));
  assert.equal((await f.send("GET")).status, 502);
});

test("identical retries and concurrent requests store one immutable object", async t => {
  const f = await start(t);
  const responses = await Promise.all([f.send("PUT"), f.send("PUT")]);
  for (const response of responses) assert.equal(response.status, 200);
  assert.equal((await f.send("PUT")).status, 200);
  assert.equal(f.storage.files.size, 1);
  assert.equal((await (await f.send("GET", "/api/tasks/task-1/attachments")).json()).length, 1);
  assert.equal((await f.send("PUT", path, { bytes: Buffer.from("%PDF-different") })).status, 409);
  assert.equal((await f.send("PUT", path, { name: "Another.pdf" })).status, 409);
  assert.deepEqual([...f.storage.files.values()][0], content);
});

test("retry recovers a blob whose metadata commit failed", async t => {
  const f = await start(t);
  f.db.failReadyOnce = true;
  assert.equal((await f.send("PUT")).status, 500);
  assert.equal(f.storage.files.size, 1);
  assert.deepEqual(await (await f.send("GET", "/api/tasks/task-1/attachments")).json(), []);
  assert.equal((await f.send("PUT")).status, 200);
  assert.equal((await f.send("GET")).status, 200);
  assert.equal(f.storage.files.size, 1);
});

test("verified users cannot list download or delete another user's files", async t => {
  const f = await start(t);
  assert.equal((await f.send("PUT")).status, 200);
  assert.deepEqual(await (await f.send("GET", "/api/tasks/task-1/attachments?uid=user-a", { user: "user-b" })).json(), []);
  assert.equal((await f.send("GET", path + "?uid=user-a", { user: "user-b" })).status, 404);
  assert.equal((await f.send("DELETE", path, { user: "user-b" })).status, 204);
  assert.equal(f.storage.files.size, 1);
  const second = "/api/tasks/task-1/attachments/22222222-2222-4222-8222-222222222222";
  assert.equal((await f.send("PUT", second, { user: "user-b" })).status, 200);
  assert.equal(f.storage.files.size, 2);
});

test("missing cloud tasks cannot accept attachments", async t => {
  const f = await start(t);
  f.db.records.delete("users/user-a/tasks/task-1");
  const response = await f.send("PUT");
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error.code, "ATTACHMENT_TASK_MISSING");
  assert.equal(f.storage.calls.length, 0);
});

test("invalid names IDs types empty files and compressed uploads are rejected", async t => {
  const f = await start(t);
  for (const options of [ { name: "" }, { name: "%zz" }, { name: "../Notes.pdf" },
    { name: "a".repeat(151) }, { name: "%00.pdf" }, { type: "image/png" },
    { type: "text/plain" }, { bytes: Buffer.alloc(0) } ]) {
    assert.equal((await f.send("PUT", path, options)).status, 400);
  }
  assert.equal((await f.send("PUT", path.replace(id, "bad-id"))).status, 400);
  assert.equal((await f.send("PUT", path, { headers: { "Content-Encoding": "gzip" } })).status, 415);
  assert.equal(f.storage.calls.length, 0);
});

test("five MiB is accepted and larger files are rejected", async t => {
  const f = await start(t);
  const bytes = Buffer.alloc(5 * 1024 * 1024);
  content.copy(bytes);
  assert.equal((await f.send("PUT", path, { bytes })).status, 200);
  assert.equal((await f.send("PUT", path, { bytes: Buffer.alloc(bytes.length + 1) })).status, 413);
});

test("task deletion leaves an owner cleanup list and repeated deletion is safe", async t => {
  const f = await start(t);
  assert.equal((await f.send("PUT")).status, 200);
  f.db.records.delete("users/user-a/tasks/task-1");
  assert.equal((await f.send("GET")).status, 404);
  assert.equal((await (await f.send("GET", "/api/tasks/task-1/attachments")).json()).length, 1);
  assert.equal((await f.send("DELETE")).status, 204);
  assert.equal((await f.send("DELETE")).status, 204);
  assert.equal(f.storage.files.size, 0);
  assert.deepEqual(await (await f.send("GET", "/api/tasks/task-1/attachments")).json(), []);
});

test("a task removed during upload is not published and its blob is removed", async t => {
  const f = await start(t);
  f.storage.afterUpload = async () => f.db.records.delete("users/user-a/tasks/task-1");
  assert.equal((await f.send("PUT")).status, 409);
  assert.equal(f.storage.files.size, 0);
});

test("an individual deletion wins over an in-flight upload and blocks UUID reuse", async t => {
  const f = await start(t);
  f.storage.afterUpload = async () => assert.equal((await f.send("DELETE")).status, 204);
  assert.equal((await f.send("PUT")).status, 409);
  assert.equal(f.storage.files.size, 0);
  assert.equal((await f.send("PUT")).status, 409);
});

test("failed deletion stays visible for retry and hides provider messages", async t => {
  const f = await start(t);
  await f.send("PUT");
  f.storage.failRemove = true;
  const failed = await f.send("DELETE");
  assert.equal(failed.status, 503);
  assert.ok(!(await failed.text()).includes("SECRET"));
  assert.equal((await (await f.send("GET", "/api/tasks/task-1/attachments")).json()).length, 1);
  f.storage.failRemove = false;
  assert.equal((await f.send("DELETE")).status, 204);
  assert.equal(f.storage.files.size, 0);
});

test("missing bucket and missing object return distinct errors", async t => {
  const f = await start(t);
  f.storage.failUpload = { code: "NoSuchBucket", message: "SECRET-provider-details" };
  const failure = await f.send("PUT");
  assert.equal(failure.status, 503);
  assert.ok(!(await failure.text()).includes("SECRET"));
  f.storage.failUpload = null;
  assert.equal((await f.send("PUT")).status, 200);
  f.storage.files.clear();
  const missing = await f.send("GET");
  assert.equal(missing.status, 404);
  assert.equal((await missing.json()).error.code, "ATTACHMENT_MISSING");
});
