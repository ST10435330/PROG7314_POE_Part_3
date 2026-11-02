import { createHash } from "node:crypto";

/** https://firebase.google.com/docs/cloud-messaging/send/admin-sdk
 * https://firebase.google.com/docs/cloud-messaging/manage-tokens
 */
export async function notifyTaskChange(db, messaging, userId, operationId) {
  if (!messaging) return;
  try {
    const snapshot = await db.collection("pushDevices")
      .where("userId", "==", userId).limit(500).get();
    const devices = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }))
      .filter(device => device.userId === userId && typeof device.token === "string");
    if (!devices.length) return;

    // Replaying an upload uses the same event ID, which Android deduplicates.
    const eventId = createHash("sha256")
      .update(JSON.stringify([userId, operationId])).digest("hex");
    const result = await messaging.sendEachForMulticast({
      tokens: devices.map(device => device.token),
      data: { type: "task_update", userId, eventId },
      android: { priority: "high", ttl: 60_000, collapseKey: "study_updates" }
    });

    let accepted = 0;
    for (const [index, response] of result.responses.entries()) {
      if (response.success) { accepted++; continue; }
      const device = devices[index];
      if (["messaging/registration-token-not-registered",
           "messaging/invalid-registration-token"].includes(response.error?.code)) {
        const ref = db.collection("pushDevices").doc(device.id);
        await db.runTransaction(async transaction => {
          const current = (await transaction.get(ref)).data();
          // A device might have refreshed or changed accounts during delivery.
          if (current?.userId === userId && current.generation === device.generation) {
            transaction.delete(ref);
          }
        });
      } else {
        console.warn("StudySync task alert rejected:", response.error?.code || "unknown");
      }
    }
    console.info("StudySync task alerts accepted by FCM:", accepted);
  } catch (error) {
    // The task has already committed. Never expose tokens or provider responses.
    console.warn("StudySync task alert unavailable:", error.code || error.name);
  }
}
