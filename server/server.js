import {
  applicationDefault,
  initializeApp
} from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { createApp } from "./app.js";
import { getMessaging } from "firebase-admin/messaging";

initializeApp({
  credential: applicationDefault()
});

const db = getFirestore();

// Check credentials and database access before accepting requests.
// Reading a missing document is valid and does not create it.
await db.doc("_health/startup").get();

const app = createApp({
  auth: getAuth(),
  db,
  messaging: getMessaging()
});

const port = Number(process.env.PORT || 3000);

app.listen(port, "0.0.0.0", () => {
  console.log(`StudySync API listening on port ${port}`);
});