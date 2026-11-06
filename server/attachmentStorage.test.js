import test from "node:test";
import assert from "node:assert/strict";
import { connectAttachmentStorage } from "./attachmentStorage.js";

// Synthetic settings only; these tests never contact a Supabase/Firebase project.
const settings = {
  SUPABASE_URL: "https://example.supabase.co",
  SUPABASE_SECRET_KEY: "sb_secret_synthetic-test-only"
};

function fixture(env = settings, result = { data: { id: "task-attachments", public: false } }) {
  const logs = [];
  const calls = [];
  const client = { storage: { getBucket: async bucket => {
    calls.push(bucket);
    if (result instanceof Error) throw result;
    return result;
  } } };
  return {
    logs, calls, client,
    options: {
      env,
      logger: { info: value => logs.push(value), warn: value => logs.push(value) },
      makeClient: (url, key, options) => {
        calls.push({ url, key, options });
        return client;
      }
    }
  };
}

test("unconfigured storage makes no remote requests", async () => {
  const f = fixture({});
  assert.equal(await connectAttachmentStorage(f.options), null);
  assert.deepEqual(f.calls, []);
  assert.match(f.logs[0], /not configured/);
});

test("a verified private bucket returns a server client without storing sessions", async () => {
  const f = fixture();
  assert.deepEqual(await connectAttachmentStorage(f.options), {
    client: f.client, bucket: "task-attachments"
  });
  assert.equal(f.calls[0].url, settings.SUPABASE_URL);
  assert.equal(f.calls[0].key, settings.SUPABASE_SECRET_KEY);
  assert.deepEqual(f.calls[0].options.auth, {
    persistSession: false, autoRefreshToken: false, detectSessionInUrl: false
  });
  assert.equal(f.calls[1], "task-attachments");
  assert.match(f.logs[0], /ready \(private\)/);
  assert.ok(!f.logs.join().includes(settings.SUPABASE_SECRET_KEY));
});

test("legacy service_role keys are accepted for existing projects", async () => {
  const payload = Buffer.from(JSON.stringify({ role: "service_role" })).toString("base64url");
  const f = fixture({ ...settings, SUPABASE_SECRET_KEY: `header.${payload}.signature` });
  assert.ok(await connectAttachmentStorage(f.options));
});

test("the real SDK uses the secret header for a bucket-only request", async t => {
  const requests = [];
  t.mock.method(globalThis, "fetch", async (url, init) => {
    requests.push({ url: String(url), headers: new Headers(init.headers), signal: init.signal });
    return new Response(JSON.stringify({ id: "task-attachments", public: false }), {
      status: 200, headers: { "Content-Type": "application/json" }
    });
  });
  const f = fixture();
  const result = await connectAttachmentStorage({ env: settings, logger: f.options.logger });
  assert.equal(result.bucket, "task-attachments");
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, settings.SUPABASE_URL + "/storage/v1/bucket/task-attachments");
  assert.equal(requests[0].headers.get("apikey"), settings.SUPABASE_SECRET_KEY);
  assert.ok(requests[0].signal instanceof AbortSignal);
});

test("partial settings and public keys are rejected before creating a client", async () => {
  for (const env of [
    { SUPABASE_URL: settings.SUPABASE_URL },
    { SUPABASE_SECRET_KEY: settings.SUPABASE_SECRET_KEY },
    { ...settings, SUPABASE_SECRET_KEY: "sb_publishable_test" },
    { ...settings, SUPABASE_SECRET_KEY: "header." +
      Buffer.from('{"role":"anon"}').toString("base64url") + ".signature" }
  ]) {
    const f = fixture(env);
    assert.equal(await connectAttachmentStorage(f.options), null);
    assert.deepEqual(f.calls, []);
    assert.match(f.logs[0], /check failed/);
  }
});

test("only a project HTTPS root URL and valid bucket name are accepted", async () => {
  for (const url of ["not-a-url", "http://example.supabase.co", "https://other.example",
    "https://user:password@example.supabase.co", "https://example.supabase.co/storage/v1",
    "https://example.supabase.co?key=secret", "https://example.supabase.co#secret"]) {
    const f = fixture({ ...settings, SUPABASE_URL: url });
    assert.equal(await connectAttachmentStorage(f.options), null);
    assert.deepEqual(f.calls, []);
  }
  const f = fixture({ ...settings, SUPABASE_ATTACHMENT_BUCKET: "../another-bucket" });
  assert.equal(await connectAttachmentStorage(f.options), null);
  assert.deepEqual(f.calls, []);
});

test("a public or missing bucket never reports readiness", async () => {
  for (const result of [
    { data: { id: "task-attachments", public: true } },
    { data: { id: "wrong-bucket", public: false } },
    { data: null, error: { message: "not found" } },
    { data: { id: "task-attachments", public: false }, error: { message: "denied" } }
  ]) {
    const f = fixture(settings, result);
    assert.equal(await connectAttachmentStorage(f.options), null);
    assert.ok(!f.logs.join().includes("ready (private)"));
  }
});

test("remote failures do not leak credentials or prevent the API starting", async () => {
  const f = fixture(settings, new Error(`Request failed with ${settings.SUPABASE_SECRET_KEY}`));
  assert.equal(await connectAttachmentStorage(f.options), null);
  assert.match(f.logs[0], /check failed/);
  assert.ok(!f.logs.join().includes(settings.SUPABASE_SECRET_KEY));
});
