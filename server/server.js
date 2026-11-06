import {
  applicationDefault,
  initializeApp
} from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { createApp } from "./app.js";
import { getMessaging } from "firebase-admin/messaging";
import { connectAttachmentStorage } from "./attachmentStorage.js";

initializeApp({
  credential: applicationDefault()
});

const db = getFirestore();

// Check credentials and database access before accepting requests.
// Reading a missing document is valid and does not create it.
await db.doc("_health/startup").get();

// Migration stage 1: verify private storage without changing the Android provider.
// A storage configuration issue must not take task sync or notifications offline.
await connectAttachmentStorage();

const app = createApp({
  auth: getAuth(),
  db,
  messaging: getMessaging()
});

const port = Number(process.env.PORT || 3000);

app.listen(port, "0.0.0.0", () => {
  console.log(`StudySync API listening on port ${port}`);
});
