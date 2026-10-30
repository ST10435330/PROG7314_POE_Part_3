import express from "express";
import { installSyncRoutes } from "./sync.js";
import {
  ApiError,
  subjectInput,
  taskInput,
  validId
} from "./validation.js";

function record(kind, id, data) {
  const { _nameKey, ...fields } = data;

  return {
    ...fields,
    [kind === "subjects" ? "subjectId" : "taskId"]: id
  };
}

export function createApp({ auth, db }) {
  const app = express();

  app.disable("x-powered-by");
  app.use(express.json({ limit: "16kb" }));

  app.use((req, res, next) => {
    res.set("Cache-Control", "no-store");
    next();
  });

  app.get("/api/health", (req, res) => {
    res.json({ status: "ok" });
  });

  app.use("/api", async (req, res, next) => {
    const match = /^Bearer (\S+)$/i.exec(
      req.get("Authorization") || ""
    );

    if (!match) {
      throw new ApiError(
        401,
        "UNAUTHENTICATED",
        "Sign in to continue."
      );
    }

    try {
      const decoded = await auth.verifyIdToken(match[1], true);
      req.uid = decoded.uid;
    } catch (error) {
      const invalidTokens = [
        "auth/id-token-expired",
        "auth/id-token-revoked",
        "auth/invalid-id-token",
        "auth/argument-error",
        "auth/user-disabled",
        "auth/user-not-found"
      ];

      if (invalidTokens.includes(error.code)) {
        throw new ApiError(
          401,
          "UNAUTHENTICATED",
          "Your session is invalid. Sign in again."
        );
      }

      throw new ApiError(
        503,
        "AUTH_UNAVAILABLE",
        "Authentication is temporarily unavailable."
      );
    }

    req.userRef = db.collection("users").doc(req.uid);
    next();
  });

  // Every write reads and updates the same user's revision document.
  // This protects duplicate-name and linked-task checks during concurrent writes.
  async function write(req, kind, operation) {
    const userRef = req.userRef;
    const collection = userRef.collection(kind);

    const ref = operation === "create"
      ? collection.doc()
      : collection.doc(validId(req.params.id));

    return db.runTransaction(async transaction => {
      const user = await transaction.get(userRef);
      let current;

      if (operation !== "create") {
        const snapshot = await transaction.get(ref);

        if (!snapshot.exists) {
          throw new ApiError(
            404,
            "NOT_FOUND",
            "Record not found."
          );
        }

        current = snapshot.data();
      }

      let data;

      if (operation === "delete") {
        if (kind === "subjects") {
          const linked = await transaction.get(
            userRef.collection("tasks")
              .where("subjectId", "==", ref.id)
              .limit(1)
          );

          if (!linked.empty) {
            throw new ApiError(
              409,
              "SUBJECT_HAS_TASKS",
              "Delete or move this subject's tasks first."
            );
          }
        }
      } else if (kind === "subjects") {
        data = subjectInput(req.body, current);
        data._nameKey = data.name.toLowerCase();

        const duplicates = await transaction.get(
          collection.where("_nameKey", "==", data._nameKey)
        );

        if (duplicates.docs.some(doc => doc.id !== ref.id)) {
          throw new ApiError(
            409,
            "DUPLICATE_SUBJECT",
            "That subject already exists."
          );
        }
      } else {
        data = taskInput(req.body, current);

        const subject = await transaction.get(
          userRef.collection("subjects").doc(data.subjectId)
        );

        if (!subject.exists) {
          throw new ApiError(
            400,
            "VALIDATION_ERROR",
            "Choose one of your existing subjects."
          );
        }
      }

      // Finish every read before starting transaction writes.
      if (operation === "delete") {
        transaction.delete(ref);
      } else {
        transaction.set(ref, data);
      }

      transaction.set(
        userRef,
        {
          writeRevision: (user.data()?.writeRevision ?? 0) + 1
        },
        { merge: true }
      );

      return operation === "delete"
        ? null
        : record(kind, ref.id, data);
    });
  }

  installSyncRoutes(app, db);

  for (const kind of ["subjects", "tasks"]) {
    app.get(`/api/${kind}`, async (req, res) => {
      const snapshot = await req.userRef.collection(kind).get();

      res.json(
        snapshot.docs.map(doc =>
          record(kind, doc.id, doc.data())
        )
      );
    });

    app.post(`/api/${kind}`, async (req, res) => {
      res.status(201).json(
        await write(req, kind, "create")
      );
    });

    app.patch(`/api/${kind}/:id`, async (req, res) => {
      res.json(
        await write(req, kind, "update")
      );
    });

    app.delete(`/api/${kind}/:id`, async (req, res) => {
      await write(req, kind, "delete");
      res.status(204).end();
    });
  }

  app.use((req, res) => {
    res.status(404).json({
      error: {
        code: "NOT_FOUND",
        message: "Endpoint not found."
      }
    });
  });

  app.use((error, req, res, next) => {
    if (error.type === "entity.parse.failed") {
      error = new ApiError(
        400,
        "INVALID_JSON",
        "Send valid JSON."
      );
    } else if (error.type === "entity.too.large") {
      error = new ApiError(
        413,
        "REQUEST_TOO_LARGE",
        "The request is too large."
      );
    }

    const known = error instanceof ApiError;

    if (!known) {
      console.error("API failure:", error.code || error.name);
    }

    res.status(known ? error.status : 500).json({
      error: {
        code: known ? error.code : "SERVER_ERROR",
        message: known
          ? error.message
          : "Something went wrong. Please try again."
      }
    });
  });

  return app;
}