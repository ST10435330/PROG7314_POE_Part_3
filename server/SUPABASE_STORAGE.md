# Supabase attachment storage migration

## Stage 1: verify the server connection

Create a separate Free Supabase project and a **private** `task-attachments` file
bucket. Set its file size limit to 5 MiB (5,242,880 bytes) and allow only
`application/pdf`, `image/jpeg`, `image/png` and `image/webp`.

In the existing Render API service, add these environment variables:

| Variable | Value |
| --- | --- |
| `SUPABASE_URL` | The project's base URL, `https://PROJECT_REF.supabase.co` |
| `SUPABASE_SECRET_KEY` | A server secret key from Supabase Project Settings / API Keys |
| `SUPABASE_ATTACHMENT_BUCKET` | `task-attachments` |

A current `sb_secret_...` key is preferred. An existing legacy `service_role`
key also works. A publishable or `anon` key cannot perform this server check.
Keep the real key in Render's secret environment settings or ignored `server/.env`.
Never put it in Kotlin, `google-services.json`, screenshots or commits.

After deployment, the server checks the bucket with Supabase's SDK. A successful
check logs `Supabase attachment bucket is ready (private).` It reads bucket
configuration only: no attachments are uploaded, listed, downloaded or deleted.
The success message confirms access and privacy at startup, not an end-to-end
phone upload. The check has a ten-second request timeout and logs no credentials.
Missing or invalid settings produce a diagnostic while existing API features
remain available. No environment settings are exposed by the health endpoint.

## Stage 2: authenticated attachment endpoints

The API now offers these Firebase-authenticated endpoints:

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/tasks/{taskId}/attachments` | Attachment metadata array |
| PUT | `/api/tasks/{taskId}/attachments/{uuid}` | Upload raw file bytes; return metadata |
| GET | `/api/tasks/{taskId}/attachments/{uuid}` | Download verified file bytes |
| DELETE | `/api/tasks/{taskId}/attachments/{uuid}` | Delete; return 204, including retries |

PUT requires the actual MIME type in `Content-Type` and a percent-encoded UTF-8
display name in `X-Attachment-Name`. All requests use the existing Firebase
`Authorization: Bearer ...` header. No client Supabase key or public file URL is
needed. The API enforces the 5 MiB limit, basic file signatures and safe names.
Signature checks are not antivirus scanning.

Blob bytes go into the private Supabase bucket at
`users/{sha256(uid)}/tasks/{taskId}/attachments/{uuid}`. Display names, MIME types,
sizes, content hashes and transfer states stay in Firestore under
`users/{uid}/attachmentGroups/{taskId}/files/{uuid}`. Existing study data remains
unchanged; no files or task data move into Supabase's Postgres tables.

Transactions reserve an immutable UUID before upload. Identical retries reuse
it; differing bytes or metadata are rejected. If blob upload succeeds but the
Firestore completion write fails, retry verifies the existing bytes and completes
the manifest. Pending uploads are hidden from normal lists. Deletion tombstones
prevent delayed requests from reusing a deleted UUID; they are retained in
Firestore and do not contain file bytes. Failed deletions remain listed for retry.

Uploads and downloads require an existing cloud task. Owner-only list/delete
remain available after task deletion so the Android cleanup worker can finish.
A task removed during upload is checked again before publishing the manifest.
Firestore and Supabase are separate systems: no distributed atomic transaction
or independent cleanup service is claimed. If both a deletion race and the
compensating storage removal fail, client cleanup/retry or owner intervention
may still be needed. Keep failed-upload/deletion evidence available for review.

**Android migration is next.** The phone still uses its existing provider until
that commit adapts the attachment repository and cleanup worker. Firebase Auth,
Firestore study data and FCM remain in use. Do not claim a completed migration
until real phone upload/open/export/delete and task cleanup have been verified.

## Verification

From `server`, run `npm ci` followed by `npm test`. The new tests inject a client
double and synthetic credentials; they never contact a live cloud project.
The existing Backend Tests workflow runs the same suite on every push.

Endpoint tests exercise authenticated loopback HTTP, separate users, invalid
files, the size boundary, concurrent retries, database failure after upload,
task/file deletion races, provider failure, integrity checking and cleanup after
task deletion. Their storage and Firestore dependencies are in-memory doubles;
passing these tests is not evidence of a live cloud transfer.

## References

- Supabase, API keys: https://supabase.com/docs/guides/api/api-keys
- Supabase, retrieve a bucket: https://supabase.com/docs/reference/javascript/storage-getbucket
- Supabase, bucket access models: https://supabase.com/docs/guides/storage/buckets/fundamentals
