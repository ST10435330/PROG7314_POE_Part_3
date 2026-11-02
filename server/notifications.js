import { createHash, randomUUID } from "node:crypto";
import { ApiError } from "./validation.js";

function tokenInput(body) {
  if (!body || Array.isArray(body) || typeof body !== "object" ||
      Object.keys(body).length !== 1 || typeof body.token !== "string" ||
      body.token.length < 20 || body.token.length > 4096 ||
      /[\s\u0000-\u001f\u007f]/u.test(body.token)) {
    throw new ApiError(400, "VALIDATION_ERROR", "Provide a valid device token only.");
  }
  return body.token;
}

/** Private device ownership and FCM delivery through the authenticated API.
 * References:
 * https://firebase.google.com/docs/cloud-messaging/send/admin-sdk
 * https://firebase.google.com/docs/cloud-messaging/manage-tokens
 */
export function installNotificationRoutes(app, db, messaging) {
  const deviceRef = token => db.collection("pushDevices").doc(
    createHash("sha256").update(token).digest("hex")
  );

  app.post("/api/notifications/register", async (req, res) => {
    const token = tokenInput(req.body);
    const ref = deviceRef(token);
    await db.runTransaction(async transaction => {
      const current = await transaction.get(ref);
      // One owner per token, including after a shared phone changes accounts.
      transaction.set(ref, {
        token,
        userId: req.uid,
        generation: randomUUID(),
        updatedAt: new Date(),
        lastTestAt: current.data()?.lastTestAt ?? 0
      });
    });
    res.json({ registered: true });
  });

  app.post("/api/notifications/unregister", async (req, res) => {
    const ref = deviceRef(tokenInput(req.body));
    await db.runTransaction(async transaction => {
      const current = await transaction.get(ref);
      // A delayed sign-out must not remove another account's registration.
      if (current.data()?.userId === req.uid) transaction.delete(ref);
    });
    res.status(204).end();
  });

  app.post("/api/notifications/test", async (req, res) => {
    const token = tokenInput(req.body);
    if (!messaging) {
      throw new ApiError(503, "PUSH_UNAVAILABLE", "Notification service is unavailable.");
    }
    const ref = deviceRef(token);
    const generation = await db.runTransaction(async transaction => {
      const current = (await transaction.get(ref)).data();
      if (!current || current.userId !== req.uid) {
        throw new ApiError(404, "DEVICE_NOT_REGISTERED", "Register this device before sending a test.");
      }
      const now = Date.now();
      if (now - current.lastTestAt < 30_000) {
        throw new ApiError(429, "PUSH_RATE_LIMIT", "Wait 30 seconds before another test notification.");
      }
      // Reserve the cooldown atomically, including simultaneous requests.
      transaction.set(ref, { lastTestAt: now }, { merge: true });
      return current.generation;
    });

    try {
      await messaging.send({
        token,
        // Data-only delivery lets Android check account and opt-in before display.
        data: {
          type: "test",
          userId: req.uid,
          eventId: randomUUID(),
          title: "StudySync",
          body: "Push notifications are connected."
        },
        android: { priority: "high", ttl: 60_000 }
      });
    } catch (error) {
      if (["messaging/registration-token-not-registered",
           "messaging/invalid-registration-token"].includes(error.code)) {
        await db.runTransaction(async transaction => {
          const current = (await transaction.get(ref)).data();
          // Do not delete a registration refreshed while FCM was responding.
          if (current?.userId === req.uid && current.generation === generation) {
            transaction.delete(ref);
          }
        });
        throw new ApiError(410, "DEVICE_EXPIRED", "Enable notifications again to refresh this device.");
      }
      // Never log registration tokens, credentials, or provider response bodies.
      console.warn("StudySync push send failed:", error.code || error.name);
      throw new ApiError(503, "PUSH_UNAVAILABLE", "Could not send a notification. Wait 30 seconds and retry.");
    }
    console.info("StudySync test push accepted by FCM");
    // FCM acceptance does not guarantee that the phone displayed the message.
    res.status(202).json({ accepted: true });
  });
}