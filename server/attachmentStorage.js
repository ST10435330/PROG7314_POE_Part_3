import { createClient } from "@supabase/supabase-js";

// Server-only connection. Secret keys bypass RLS and must never enter the APK.
// References: https://supabase.com/docs/guides/api/api-keys
// https://supabase.com/docs/reference/javascript/storage-getbucket
export async function connectAttachmentStorage({
  env = process.env,
  makeClient = createClient,
  logger = console
} = {}) {
  const url = env.SUPABASE_URL?.trim();
  const key = env.SUPABASE_SECRET_KEY?.trim();
  const bucket = env.SUPABASE_ATTACHMENT_BUCKET?.trim() || "task-attachments";

  if (!url && !key) {
    logger.info("Supabase attachment storage is not configured yet.");
    return null;
  }

  try {
    const endpoint = new URL(url);
    if (!key || endpoint.protocol !== "https:" || endpoint.username ||
        endpoint.password || endpoint.port || endpoint.search || endpoint.hash ||
        endpoint.pathname !== "/" || !endpoint.hostname.endsWith(".supabase.co") ||
        !/^[a-z0-9][a-z0-9-]{0,62}$/.test(bucket)) {
      throw new Error("Invalid server storage settings");
    }

    // Accept current secret keys and the older server-only service_role key.
    let privileged = key.startsWith("sb_secret_");
    if (!privileged) {
      try {
        privileged = JSON.parse(Buffer.from(key.split(".")[1], "base64url")
          .toString("utf8")).role === "service_role";
      } catch { /* A publishable/anonymous key is not a server credential. */ }
    }
    if (!privileged) throw new Error("A server secret key is required");

    const client = makeClient(endpoint.origin, key, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
      global: {
        // Bound startup checks and future storage requests, including slow hosts.
        fetch: (input, init = {}) => fetch(input, {
          ...init,
          signal: init.signal
            ? AbortSignal.any([init.signal, AbortSignal.timeout(10_000)])
            : AbortSignal.timeout(10_000)
        })
      }
    });
    const { data, error } = await client.storage.getBucket(bucket);
    if (error || data?.id !== bucket || data.public !== false) {
      throw new Error("The private bucket could not be verified");
    }

    logger.info("Supabase attachment bucket is ready (private).");
    return { client, bucket };
  } catch {
    // SDK errors can contain request details. Log no credentials or raw errors.
    logger.warn("Supabase attachment storage check failed. Verify SUPABASE_URL, " +
      "SUPABASE_SECRET_KEY and the private SUPABASE_ATTACHMENT_BUCKET.");
    return null;
  }
}
