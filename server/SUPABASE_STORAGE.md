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

**This stage does not switch the Android app away from Firebase Storage.** The
next stage will add authenticated attachment endpoints and adapt the Android
provider and cleanup worker. Firebase Authentication, Firestore study data and
FCM remain in use. Keep this document explicit about the migration stage until
the phone upload/open/delete checks have passed.

## Verification

From `server`, run `npm ci` followed by `npm test`. The new tests inject a client
double and synthetic credentials; they never contact a live cloud project.
The existing Backend Tests workflow runs the same suite on every push.

## References

- Supabase, API keys: https://supabase.com/docs/guides/api/api-keys
- Supabase, retrieve a bucket: https://supabase.com/docs/reference/javascript/storage-getbucket
- Supabase, bucket access models: https://supabase.com/docs/guides/storage/buckets/fundamentals
