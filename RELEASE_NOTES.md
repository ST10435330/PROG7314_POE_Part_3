# StudySync release notes

## 2.0.0 - Part 3 Final POE

Changes since the Part 2 study-planner prototype:

### Added

- Native biometric session protection using BiometricPrompt and Android Keystore.
- Per-account SQLite caching and a persistent outbox for offline subject/task actions, with automatic WorkManager synchronisation when connected.
- Idempotent API operations, sync receipts, stale-response protection and visible rejected-change handling.
- FCM device registration, notification preferences, test pushes and committed task-change alerts.
- English and isiZulu interfaces with saved language preferences and translated validation/errors.
- Focus sessions with start, pause, resume, reset and cancel controls, plus account-separated local completion history.
- Study Resources with offline guides for retrieval practice, spacing, interleaving and self-explanation, research references, an external credited YouTube video and a Focus shortcut.
- Private task attachments with upload, open, export, deletion, validation, caching and retryable task-deletion cleanup.

### Improved and corrected

- Replaced Firebase Storage attachment bytes with private Supabase Storage through the authenticated Render API, avoiding the unresolved Firebase billing activation requirement.
- Added server ownership checks, attachment manifests, stable upload IDs, retry reconciliation and cancellation when accounts change.
- Added specific attachment setup/missing-file/network errors and safer private FileProvider access.
- Prevented unnecessary biometric prompts when returning promptly from app-launched document pickers, while preserving normal background and screen-lock protection.
- Added consistent branding, bottom navigation, compact sync details, readable task dates, priority/status badges and task action menus.
- Updated Android compile/target SDK to API 36 and expanded Android/backend automated verification.
- Prepared version code 2 and version name 2.0.0, a signed production APK and physical-device promotional screenshots.

### Retained from the prototype

Google SSO, subject/task management, due dates, priorities, filters, sorting, completion progress, saved task defaults and the custom Firebase-authenticated REST API with Firestore persistence.

### Verification and publication status

We have reported successful signed-release physical-phone testing on 6 November 2026. The APK signature was verified with apksigner. Automated results remain available through GitHub Actions; the final commit must have passing checks before submission.

The final narrated video has been uploaded and provided via a YouTube link in the ReadMe file.
