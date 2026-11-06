import express from "express";
import { createHash } from "node:crypto";
import { ApiError, validId } from "./validation.js";

const MAX_SIZE = 5 * 1024 * 1024;
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const TYPES = ["application/pdf", "image/jpeg", "image/png", "image/webp"];
const hash = bytes => createHash("sha256").update(bytes).digest("hex");
const fail = (status, code, message) => { throw new ApiError(status, code, message); };

function signature(bytes) {
  if (bytes.subarray(0, 5).equals(Buffer.from("%PDF-"))) return TYPES[0];
  if (bytes.subarray(0, 3).equals(Buffer.from([255, 216, 255]))) return TYPES[1];
  if (bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) return TYPES[2];
  if (bytes.length >= 12 && bytes.subarray(0, 4).equals(Buffer.from("RIFF")) &&
      bytes.subarray(8, 12).equals(Buffer.from("WEBP"))) return TYPES[3];
  return null;
}

function uploadName(value) {
  let name;
  try { name = decodeURIComponent(value || "").trim(); }
  catch { fail(400, "ATTACHMENT_NAME", "Enter a valid attachment name."); }
  if (!name || name.length > 150 || /[\x00-\x1f\x7f-\x9f/\\]/.test(name)) {
    fail(400, "ATTACHMENT_NAME", "Enter an attachment name of up to 150 characters without path separators.");
  }
  return name;
}

function providerFailure(error, downloading = false) {
  const code = error?.code || error?.error;
  if (downloading && code === "NoSuchKey") {
    fail(404, "ATTACHMENT_MISSING", "This attachment no longer exists in cloud storage. Refresh the list.");
  }
  // Never return SDK messages, URLs or service credentials to the phone.
  fail(503, "ATTACHMENT_STORAGE_UNAVAILABLE", "Attachment storage is temporarily unavailable. Try again later.");
}

// Installed after Firebase token verification; owner IDs never come from the body.
// Supabase SDK references: /storage-from-upload, /storage-from-download,
// /storage-from-remove at https://supabase.com/docs/reference/javascript
export function installAttachmentRoutes(app, db, storage) {
  const base = "/api/tasks/:taskId/attachments";

  function refs(req) {
    if (!storage) fail(503, "ATTACHMENT_STORAGE_UNAVAILABLE", "Attachment storage is not configured.");
    const taskId = validId(req.params.taskId);
    const task = req.userRef.collection("tasks").doc(taskId);
    // Keep manifests outside the task document so cleanup works after task deletion.
    const files = req.userRef.collection("attachmentGroups").doc(taskId).collection("files");
    const prefix = `users/${hash(req.uid)}/tasks/${taskId}/attachments`;
    const objects = storage.client.storage.from(storage.bucket);
    if (!req.params.attachmentId) return { taskId, task, files, objects };
    const id = req.params.attachmentId;
    if (!UUID.test(id)) fail(400, "VALIDATION_ERROR", "Choose a valid attachment ID.");
    return { taskId, task, files, objects, id, ref: files.doc(id), path: `${prefix}/${id}` };
  }

  app.get(base, async (req, res) => {
    const r = refs(req);
    const [task, files] = await Promise.all([r.task.get(), r.files.get()]);
    const result = files.docs.map(doc => doc.data()).filter(record =>
      record.entry && record.state !== "deleted" &&
      (!task.exists || record.state === "ready" || record.state === "deleting")
    ).map(record => record.entry);
    // Missing-task lists include unfinished uploads for the existing cleanup worker.
    res.json(result.sort((a, b) => a.name.localeCompare(b.name)));
  });

  app.put(`${base}/:attachmentId`,
    (req, res, next) => { refs(req); next(); },
    express.raw({ type: () => true, limit: MAX_SIZE, inflate: false }),
    async (req, res) => {
      const r = refs(req);
      const bytes = req.body;
      const contentType = req.get("Content-Type")?.split(";")[0].trim().toLowerCase();
      if (!Buffer.isBuffer(bytes) || !bytes.length) {
        fail(400, "ATTACHMENT_EMPTY", "Choose a nonempty attachment.");
      }
      if (!TYPES.includes(contentType) || signature(bytes) !== contentType) {
        fail(400, "ATTACHMENT_BAD_TYPE", "Choose a PDF, JPEG, PNG or WebP file with a matching file type.");
      }
      const entry = { id: r.id, taskId: r.taskId, name: uploadName(req.get("X-Attachment-Name")),
        contentType, size: bytes.length };
      const digest = hash(bytes);

      const ready = await db.runTransaction(async tx => {
        const [task, current] = await Promise.all([tx.get(r.task), tx.get(r.ref)]);
        if (!task.exists) fail(404, "ATTACHMENT_TASK_MISSING", "Sync this task before attaching a file.");
        if (current.exists) {
          const saved = current.data();
          if (!["pending", "ready"].includes(saved.state) || saved.digest !== digest ||
              saved.entry.name !== entry.name || saved.entry.contentType !== contentType ||
              saved.entry.size !== entry.size) {
            fail(409, "ATTACHMENT_CONFLICT", "This attachment ID is already in use. Add the file again.");
          }
          return saved.state === "ready";
        }
        tx.set(r.ref, { entry, digest, state: "pending" });
        return false;
      });

      if (!ready) {
        // UUIDs and no-overwrite uploads make lost-response retries repeatable.
        const { error } = await r.objects.upload(r.path, bytes, {
          contentType, upsert: false, cacheControl: "0"
        });
        if (error) {
          const duplicate = Number(error.statusCode) === 409 ||
            ["Duplicate", "ResourceAlreadyExists", "KeyAlreadyExists"].includes(error.code);
          if (!duplicate) providerFailure(error);
          const existing = await r.objects.download(r.path);
          if (existing.error) providerFailure(existing.error);
          if (existing.data.size !== bytes.length ||
              hash(Buffer.from(await existing.data.arrayBuffer())) !== digest) {
            fail(409, "ATTACHMENT_CONFLICT", "The stored file differs. Add the attachment again.");
          }
        }

        // A task deletion or an individual file deletion must win over an upload.
        try {
          await db.runTransaction(async tx => {
            const [task, current] = await Promise.all([tx.get(r.task), tx.get(r.ref)]);
            if (!task.exists || !["pending", "ready"].includes(current.data()?.state)) {
              fail(409, "ATTACHMENT_INTERRUPTED", "The task or attachment was deleted during upload.");
            }
            tx.set(r.ref, { entry, digest, state: "ready" });
          });
        } catch (error) {
          if (error instanceof ApiError) {
            // Keep the manifest for retryable cleanup if the provider is unavailable.
            await r.objects.remove([r.path]).catch(() => {});
          }
          throw error;
        }
      }
      console.info("StudySync attachment upload acknowledged");
      res.json(entry);
    }
  );

  app.get(`${base}/:attachmentId`, async (req, res) => {
    const r = refs(req);
    const [task, saved] = await Promise.all([r.task.get(), r.ref.get()]);
    if (!task.exists || saved.data()?.state !== "ready") {
      fail(404, "ATTACHMENT_MISSING", "This attachment is unavailable. Refresh the list.");
    }
    const { entry, digest } = saved.data();
    const { data, error } = await r.objects.download(r.path);
    if (error) providerFailure(error, true);
    if (!data || data.size !== entry.size || data.size > MAX_SIZE) {
      fail(502, "ATTACHMENT_INVALID", "The attachment could not be verified.");
    }
    const bytes = Buffer.from(await data.arrayBuffer());
    if (hash(bytes) !== digest || signature(bytes) !== entry.contentType) {
      fail(502, "ATTACHMENT_INVALID", "The attachment could not be verified.");
    }
    const filename = encodeURIComponent(entry.name).replace(/['()*]/g,
      char => "%" + char.charCodeAt(0).toString(16).toUpperCase());
    res.set({ "Content-Type": entry.contentType, "X-Content-Type-Options": "nosniff",
      "Content-Disposition": "attachment; filename*=UTF-8''" + filename });
    res.send(bytes);
  });

  app.delete(`${base}/:attachmentId`, async (req, res) => {
    const r = refs(req);
    await db.runTransaction(async tx => {
      const saved = await tx.get(r.ref);
      if (saved.data()?.state !== "deleted") {
        tx.set(r.ref, { ...saved.data(), state: "deleting" });
      }
    });
    const { error } = await r.objects.remove([r.path]);
    if (error && error.code !== "NoSuchKey") providerFailure(error);
    // Retain a tombstone to prevent a delayed upload recreating the same UUID.
    await r.ref.set({ state: "deleted" });
    console.info("StudySync attachment deletion acknowledged");
    res.status(204).end();
  });
}
