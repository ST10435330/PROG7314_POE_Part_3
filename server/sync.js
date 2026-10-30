import { createHash } from "node:crypto";
import {
  ApiError,
  subjectInput,
  taskInput,
  validId
} from "./validation.js";

/**
 * A saved operation receipt makes retries return the original result.
 * The receipt and record change commit together in one transaction.
 * Reference: https://firebase.google.com/docs/firestore/manage-data/transactions
 */
export function installSyncRoutes(app, db) {
  async function writeSync(req, kind, deleting) {
    const operationId = req.get("X-Operation-Id");

    if (!operationId) {
      throw new ApiError(
        400,
        "OPERATION_ID_REQUIRED",
        "Provide an X-Operation-Id header."
      );
    }

    const id = validId(req.params.id);
    const userRef = req.userRef;
    const collection = userRef.collection(kind);
    const ref = collection.doc(id);

    const receiptRef = userRef
      .collection("syncOperations")
      .doc(validId(operationId));

    // PUT receives a complete record through the existing validators.
    const data = deleting
      ? null
      : kind === "subjects"
        ? subjectInput(req.body)
        : taskInput(req.body);

    const fingerprint = createHash("sha256")
      .update(JSON.stringify([req.method, kind, id, data]))
      .digest("hex");

    return db.runTransaction(async transaction => {
      const receipt = await transaction.get(receiptRef);

      if (receipt.exists) {
        if (receipt.data().fingerprint !== fingerprint) {
          throw new ApiError(
            409,
            "OPERATION_ID_REUSED",
            "This operation ID was already used for a different change."
          );
        }

        return receipt.data().result;
      }

      // Share the revision used by existing writes.
      const user = await transaction.get(userRef);

      let result = null;
      let savedData = data;

      if (deleting && kind === "subjects") {
        const linked = await transaction.get(
          userRef.collection("tasks")
            .where("subjectId", "==", id)
            .limit(1)
        );

        if (!linked.empty) {
          throw new ApiError(
            409,
            "SUBJECT_HAS_TASKS",
            "Delete or move this subject's tasks first."
          );
        }
      } else if (!deleting && kind === "subjects") {
        savedData = {
          ...data,
          _nameKey: data.name.toLowerCase()
        };

        const duplicates = await transaction.get(
          collection.where("_nameKey", "==", savedData._nameKey)
        );

        if (duplicates.docs.some(doc => doc.id !== id)) {
          throw new ApiError(
            409,
            "DUPLICATE_SUBJECT",
            "That subject already exists."
          );
        }
      } else if (!deleting) {
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

      if (!deleting) {
        result = {
          ...data,
          [kind === "subjects" ? "subjectId" : "taskId"]: id
        };
      }

      // Complete every read before starting transaction writes.
      if (deleting) {
        // Deleting an absent record is also a successful operation.
        transaction.delete(ref);
      } else {
        transaction.set(ref, savedData);
      }

      transaction.set(
        userRef,
        {
          writeRevision: (user.data()?.writeRevision ?? 0) + 1
        },
        { merge: true }
      );

      transaction.set(receiptRef, {
        fingerprint,
        result,
        createdAt: new Date()
      });

      return result;
    });
  }

  for (const kind of ["subjects", "tasks"]) {
    app.put(`/api/${kind}/:id`, async (req, res) => {
      res.json(await writeSync(req, kind, false));
    });

    app.delete(`/api/${kind}/:id`, async (req, res, next) => {
      if (req.get("X-Operation-Id") === undefined) {
        // Preserve the existing behaviour for the Part 2 client.
        return next();
      }

      await writeSync(req, kind, true);
      res.status(204).end();
    });
  }
}