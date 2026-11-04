import 'firebase/compat/storage';
import 'firebase/compat/firestore';
import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { ref, uploadBytes, getBytes, getMetadata, listAll, deleteObject, updateMetadata } from 'firebase/storage';

const projectId = 'demo-studysync';
const id = '12345678-1234-4234-8234-123456789abc';
const pdf = new TextEncoder().encode('%PDF-1.7\nStudySync test');
const metadata = { contentType: 'application/pdf', customMetadata: { displayName: 'Notes.pdf' } };
let environment;

function object(uid = 'alice', task = 'task-a', file = id, signedIn = 'alice') {
  const context = signedIn ? environment.authenticatedContext(signedIn) : environment.unauthenticatedContext();
  return ref(context.storage(`gs://${projectId}.appspot.com`), `users/${uid}/tasks/${task}/attachments/${file}`);
}

before(async () => {
  environment = await initializeTestEnvironment({
    projectId,
    firestore: { host: '127.0.0.1', port: 8085, rules: readFileSync('firestore.rules', 'utf8') },
    storage: { host: '127.0.0.1', port: 9199, rules: readFileSync('../../storage.rules', 'utf8') }
  });
});

after(async () => { await environment?.cleanup(); });

beforeEach(async () => {
  await environment.withSecurityRulesDisabled(async context => {
    async function clear(folder) {
      const page = await listAll(folder);
      for (const child of page.prefixes) await clear(child);
      for (const item of page.items) await deleteObject(item);
    }
    await clear(ref(context.storage(`gs://${projectId}.appspot.com`)));
  });
  await environment.clearFirestore();
  await environment.withSecurityRulesDisabled(async context => {
    await context.firestore().doc('users/alice/tasks/task-a').set({ title: 'Alice task' });
    await context.firestore().doc('users/bob/tasks/task-b').set({ title: 'Bob task' });
  });
});

test('owner can privately upload, list, download and delete', async () => {
  const file = object();
  await assertSucceeds(uploadBytes(file, pdf, metadata));
  const listed = await assertSucceeds(listAll(ref(file.storage, 'users/alice/tasks/task-a/attachments')));
  assert.equal(listed.items.length, 1);
  const downloaded = await assertSucceeds(getBytes(file));
  assert.deepEqual(new Uint8Array(downloaded), pdf);
  await assertSucceeds(deleteObject(file));
  await assert.rejects(getMetadata(file), { code: 'storage/object-not-found' });
});

for (const action of ['upload', 'list', 'download', 'delete']) {
  test(`another student cannot ${action} Alice attachments`, async () => {
    await uploadBytes(object(), pdf, metadata);
    const file = object('alice', 'task-a', id, 'bob');
    if (action === 'upload') {
      await assertFails(uploadBytes(object('alice', 'task-a', id.replace('12345678', '87654321'), 'bob'), pdf, metadata));
    } else if (action === 'list') {
      await assertFails(listAll(ref(file.storage, 'users/alice/tasks/task-a/attachments')));
    } else if (action === 'download') await assertFails(getBytes(file));
    else await assertFails(deleteObject(file));
  });
}

test('signed-out clients cannot upload, list, download or delete', async () => {
  await uploadBytes(object(), pdf, metadata);
  const file = object('alice', 'task-a', id, null);
  await assertFails(getBytes(file));
  await assertFails(deleteObject(file));
  await assertFails(listAll(ref(file.storage, 'users/alice/tasks/task-a/attachments')));
  await assertFails(uploadBytes(object('alice', 'task-a', id.replace('12345678', '87654321'), null), pdf, metadata));
});

test('Bob can use his own task without gaining access to Alice', async () => {
  await assertSucceeds(uploadBytes(object('bob', 'task-b', id, 'bob'), pdf, metadata));
  await assertFails(uploadBytes(object('alice', 'task-b'), pdf, metadata));
});

test('upload requires a task that exists under the same owner', async () => {
  await assertFails(uploadBytes(object('alice', 'missing'), pdf, metadata));
  await assertFails(uploadBytes(object('alice', 'task-b'), pdf, metadata));
});

test('only PDF and the three approved image MIME types can be uploaded', async () => {
  for (const contentType of ['image/svg+xml', 'text/html', 'application/octet-stream', 'application/x-msdownload']) {
    await assertFails(uploadBytes(object(), pdf, { ...metadata, contentType }));
  }
  for (const contentType of ['image/jpeg', 'image/png', 'image/webp']) {
    const file = object('alice', 'task-a', crypto.randomUUID());
    await assertSucceeds(uploadBytes(file, new Uint8Array([1, 2, 3]), { ...metadata, contentType }));
  }
});

test('5 MiB is allowed and any larger file is rejected', async () => {
  await assertSucceeds(uploadBytes(object(), new Uint8Array(5 * 1024 * 1024), metadata));
  await assertFails(uploadBytes(object('alice', 'task-a', crypto.randomUUID()), new Uint8Array(5 * 1024 * 1024 + 1), metadata));
});

test('empty files and unsafe object identifiers are rejected', async () => {
  await assertFails(uploadBytes(object(), new Uint8Array(), metadata));
  await assertFails(uploadBytes(object('alice', 'task-a', 'notes.pdf'), pdf, metadata));
});

test('existing objects cannot be overwritten or have their metadata changed', async () => {
  const file = object();
  await uploadBytes(file, pdf, metadata);
  await assertFails(uploadBytes(file, pdf, metadata));
  await assertFails(updateMetadata(file, { customMetadata: { displayName: 'Changed.pdf' } }));
});

test('custom metadata must contain only a bounded display name', async () => {
  await assertFails(uploadBytes(object(), pdf, {
    ...metadata, customMetadata: { displayName: 'Notes.pdf', role: 'admin' }
  }));
  await assertFails(uploadBytes(object(), pdf, { ...metadata, customMetadata: { displayName: '' } }));
  await assertFails(uploadBytes(object(), pdf, { ...metadata, customMetadata: { displayName: 'A'.repeat(151) } }));
});

test('the owner can clean up attachments after the task has been deleted', async () => {
  const file = object();
  await uploadBytes(file, pdf, metadata);
  await environment.withSecurityRulesDisabled(context => context.firestore().doc('users/alice/tasks/task-a').delete());
  await assertFails(uploadBytes(object('alice', 'task-a', crypto.randomUUID()), pdf, metadata));
  await assertSucceeds(deleteObject(file));
});

test('bucket root listing and paths outside task attachments are denied', async () => {
  const storage = object().storage;
  await assertFails(listAll(ref(storage)));
  await assertFails(listAll(ref(storage, 'users')));
  await assertFails(uploadBytes(ref(storage, 'public/notes.pdf'), pdf, metadata));
});
