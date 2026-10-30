import test from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { createApp } from "./app.js";

// Test checks API behaviour and reads-before-writes.
function memoryDb() {
  const records = new Map();
  let nextId = 0;
  let queue = Promise.resolve();

  const doc = path => ({
    path,
    id: path.split("/").at(-1),
    collection: name => collection(`${path}/${name}`)
  });

  const collection = (path, filters = [], max = Infinity) => ({
    path,
    filters,
    max,
    doc: (id = `auto-${++nextId}`) => doc(`${path}/${id}`),

    where: (field, operator, value) => {
      assert.equal(operator, "==");
      return collection(path, [...filters, [field, value]], max);
    },

    limit: value => collection(path, filters, value),
    get: async () => read(collection(path, filters, max))
  });

  function read(ref) {
    if (!ref.filters) {
      return {
        id: ref.id,
        exists: records.has(ref.path),
        data: () => structuredClone(records.get(ref.path))
      };
    }

    const docs = [...records.keys()]
      .filter(path =>
        path.startsWith(`${ref.path}/`) &&
        !path.slice(ref.path.length + 1).includes("/")
      )
      .filter(path =>
        ref.filters.every(([key, value]) =>
          records.get(path)[key] === value
        )
      )
      .slice(0, ref.max)
      .map(path => read(doc(path)));

    return { docs, empty: docs.length === 0 };
  }

  return {
    records,
    collection,

    runTransaction(callback) {
      const job = queue.then(async () => {
        const writes = [];

        const result = await callback({
          get: async ref => {
            assert.equal(
              writes.length,
              0,
              "All reads must precede writes"
            );

            return read(ref);
          },

          set: (ref, data, options) => writes.push(() =>
            records.set(
              ref.path,
              structuredClone(
                options?.merge
                  ? { ...records.get(ref.path), ...data }
                  : data
              )
            )
          ),

          delete: ref => writes.push(() =>
            records.delete(ref.path)
          )
        });

        writes.forEach(write => write());
        return result;
      });

      queue = job.catch(() => {});
      return job;
    }
  };
}

async function start(t) {
  const db = memoryDb();

  // Test authentication only; production uses Firebase Admin.
  const auth = {
    verifyIdToken: async token => ({ uid: token })
  };

  const server = createApp({ auth, db }).listen(0, "127.0.0.1");
  await once(server, "listening");

  t.after(() =>
    new Promise(resolve => server.close(resolve))
  );

  const base = `http://127.0.0.1:${server.address().port}`;

  async function send(
    method,
    path,
    operationId,
    body,
    user = "user-a"
  ) {
    const headers = {
      Authorization: `Bearer ${user}`,
      "Content-Type": "application/json"
    };

    if (operationId !== undefined) {
      headers["X-Operation-Id"] = operationId;
    }

    const response = await fetch(`${base}/api/${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    });

    return {
      status: response.status,
      body: response.status === 204
        ? null
        : await response.json()
    };
  }

  return { db, send, base };
}

const subject = {
  name: "Programming",
  lecturerName: ""
};

const task = {
  subjectId: "subject-1",
  title: "Revise Kotlin",
  description: "",
  dueDate: "2026-11-30",
  priority: "MEDIUM",
  completed: false
};

test("PUT retries apply once and cannot overwrite a later edit", async t => {
  const { db, send } = await start(t);

  const first = await send(
    "PUT", "subjects/subject-1", "op-1", subject
  );

  const replay = await send(
    "PUT", "subjects/subject-1", "op-1",
    { lecturerName: "", name: "Programming" }
  );

  assert.equal(first.status, 200);
  assert.deepEqual(replay, first);
  assert.equal(db.records.get("users/user-a").writeRevision, 1);
  assert.equal("_nameKey" in first.body, false);

  const updated = { ...subject, name: "Programming 3" };

  assert.equal(
    (await send("PUT", "subjects/subject-1", "op-2", updated)).status,
    200
  );

  assert.deepEqual(
    await send("PUT", "subjects/subject-1", "op-1", subject),
    first
  );

  assert.equal(
    db.records.get("users/user-a/subjects/subject-1").name,
    "Programming 3"
  );

  assert.equal(db.records.get("users/user-a").writeRevision, 2);
});

test("operation IDs cannot be reused for a different body, target or method", async t => {
  const { db, send } = await start(t);

  await send("PUT", "subjects/subject-1", "op-1", subject);

  for (const [method, path, body] of [
    ["PUT", "subjects/subject-1", { ...subject, name: "Different" }],
    ["PUT", "subjects/subject-2", subject],
    ["DELETE", "subjects/subject-1", undefined]
  ]) {
    const response = await send(method, path, "op-1", body);

    assert.equal(response.status, 409);
    assert.equal(response.body.error.code, "OPERATION_ID_REUSED");
  }

  assert.equal(db.records.get("users/user-a").writeRevision, 1);
});

test("sync records and receipts are scoped to the authenticated account", async t => {
  const { db, send } = await start(t);

  await send("PUT", "subjects/subject-1", "op-1", subject);

  const denied = await send(
    "PUT", "tasks/task-1", "op-2", task, "user-b"
  );

  assert.equal(denied.status, 400);
  assert.equal(
    db.records.has("users/user-b/syncOperations/op-2"),
    false
  );

  const own = await send(
    "PUT",
    "subjects/subject-1",
    "op-1",
    { ...subject, name: "Other account" },
    "user-b"
  );

  assert.equal(own.status, 200);

  assert.equal(
    db.records.get("users/user-a/subjects/subject-1").name,
    "Programming"
  );

  assert.equal(
    db.records.get("users/user-b/subjects/subject-1").name,
    "Other account"
  );
});

test("task retries and delete receipts preserve a later recreation", async t => {
  const { db, send } = await start(t);

  await send("PUT", "subjects/subject-1", "subject-op", subject);

  const created = await send(
    "PUT", "tasks/task-1", "task-op", task
  );

  assert.equal(created.status, 200);

  assert.deepEqual(
    await send("PUT", "tasks/task-1", "task-op", task),
    created
  );

  const blocked = await send(
    "DELETE", "subjects/subject-1", "blocked-delete"
  );

  assert.equal(blocked.status, 409);

  assert.equal(
    (await send("DELETE", "tasks/task-1", "delete-op")).status,
    204
  );

  assert.equal(
    (await send("PUT", "tasks/task-1", "recreate-op", task)).status,
    200
  );

  assert.equal(
    (await send("DELETE", "tasks/task-1", "delete-op")).status,
    204
  );

  assert.equal(
    db.records.has("users/user-a/tasks/task-1"),
    true
  );

  assert.equal(
    (await send("DELETE", "tasks/absent", "absent-op")).status,
    204
  );
});

test("invalid sync requests are rejected before any database write", async t => {
  const { db, send, base } = await start(t);

  const unauthenticated = await fetch(
    `${base}/api/subjects/subject-1`,
    { method: "PUT" }
  );

  assert.equal(unauthenticated.status, 401);
  await unauthenticated.json();

  assert.equal(
    (await send("PUT", "subjects/subject-1", undefined, subject)).status,
    400
  );

  assert.equal(
    (await send("PUT", "subjects/subject-1", "bad/id", subject)).status,
    400
  );

  assert.equal(
    (await send("PUT", "subjects/subject-1", "op-1", { name: "" })).status,
    400
  );

  assert.equal(
    (await send("PUT", "tasks/task-1", "op-2", {
      ...task,
      dueDate: "2026-02-30"
    })).status,
    400
  );

  assert.equal(db.records.size, 0);
});

test("duplicate subject checks and legacy routes remain functional", async t => {
  const { send } = await start(t);

  await send("PUT", "subjects/subject-1", "op-1", subject);

  const duplicate = await send(
    "PUT",
    "subjects/subject-2",
    "op-2",
    { ...subject, name: "PROGRAMMING" }
  );

  assert.equal(duplicate.status, 409);
  assert.equal(duplicate.body.error.code, "DUPLICATE_SUBJECT");

  const created = await send(
    "POST", "subjects", undefined, { name: "Networks" }
  );

  assert.equal(created.status, 201);

  const path = `subjects/${created.body.subjectId}`;

  assert.equal(
    (await send("PATCH", path, undefined, { name: "Networks 3" })).status,
    200
  );

  assert.equal((await send("DELETE", path)).status, 204);
  assert.equal((await send("DELETE", path)).status, 404);
});