# StudySync

StudySync is a native Kotlin Android study planner for **PROG7314 POE Part 2**. Students sign in with Google, organise subjects, manage study tasks and track completion. A custom REST API hosted on Render stores each user's subjects and tasks in Cloud Firestore.

## Project links

GitHub Repository 		           ->	https://github.com/ST10435330/PROG7314_POE_Part_2

GitHub build and test workflows    ->	https://github.com/ST10435330/PROG7314_POE_Part_2/actions

Hosted API Health check	           ->	https://prog7314-poe-part-2.onrender.com/api/health

YouTube demonstration 	           ->	https://youtu.be/d-Y73tCCgLY

API Video Demonstration	           ->	https://youtu.be/jVKDiaOgvVY



## Group contributions

| Member | Contribution |
| --- | --- |
| Antonio Bechev — ST10435330 | Android application, interface, app features and integration with Firebase Authentication and the hosted API. |
| Sive Sikutshwa — ST10061605 | Created the custom REST API and Cloud Firestore database. |

## Features and rubric coverage

| Part 2 criterion | StudySync implementation or required evidence |
| --- | --- |
| Runs on a mobile device | Native Android app; demonstrate the latest build running on a physical phone. |
| SSO sign-in | Google sign-in through Firebase Authentication; first sign-in creates the account, with returning login and sign-out. |
| Settings menu | Default task priority and sorting preference, saved on the device and used by the task screen. |
| REST API creation | Express API with Firebase token verification, validation and Firestore persistence. |
| REST API integration | Retrofit and OkHttp send authenticated requests to create, load, update and delete subjects and tasks. |
| User-defined feature 1 | Subject and task management, including editing, deletion and assigning tasks to subjects. |
| User-defined feature 2 | Due dates, priorities, overdue labels, filtering by subject/status and sorting by date, priority or title. |
| User-defined feature 3 | Complete or reopen tasks, with overall and per-subject completion progress. |
| User interface | Compose Material 3 screens, consistent navigation, input validation, loading indicators and error/retry feedback. |
| GitHub, README and automated testing | Source history, this README, Android JUnit tests and Node tests run through GitHub Actions. |
| Demonstration video | Narrated physical-phone demonstration linked above; include authentication and cloud data verification. |


## Part 3: Commit 8 language support

Choose **English** or **isiZulu** in **Settings → Language**. The choice saves on this device and remains after closing the app or signing out. English is the default. **Restore defaults** resets task priority and sorting only; it keeps the selected language.

English text is in `app/src/main/res/values/strings.xml`, with isiZulu translations in `values-zu/strings.xml`. User-entered subjects, titles and descriptions remain as entered. API values and stored study data keep their existing format. Android and Google control their own system screens.

Language tests cover preferences saved to disk, existing settings, invalid language fallback, task-default restoration, translation coverage, format arguments, AppCompat switching on API 28 and 33, background resources and previously saved sync messages. Run with JDK 17, Android SDK 35 and your real `app/google-services.json`:

```sh
sh ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

For the demonstration:

1. Sign in, open Settings and choose isiZulu. Show translated navigation, tasks, subjects and settings.
2. Open task filters and an add/edit form. Submit an empty title to show translated validation, then open and cancel a delete confirmation.
3. Close and reopen StudySync to show that isiZulu remains selected. With biometric protection enabled, verify that the translated lock screen still requires authentication.
4. Change task priority or sorting, then restore defaults. Confirm that isiZulu remains selected.
5. Switch back to English. Check offline edits and sync, and send a test notification if notifications are enabled.

## Architecture and design

```mermaid
flowchart TD
    A["Kotlin Android app"] -->|Google sign-in| B["Firebase Authentication"]
    B -->|Firebase ID token| A
    A -->|HTTPS and bearer token| C["Express REST API on Render"]
    C -->|Verify ID token| B
    C -->|Firebase Admin SDK| D["Cloud Firestore"]
    A -->|Save device preferences| E["Local settings"]
```

Compose screens handle interaction. `StudyRepository` defines data operations, and `ApiStudyRepository` implements them through Retrofit. The API verifies the user's Firebase ID token, derives the user ID from that token, validates inputs and reads or writes only that user's records. Subjects and tasks are not written directly to Firestore by Android.

The API checks duplicate subject names and prevents deleting a subject that still has tasks. Transactions coordinate these checks with database updates. Android handles filtering, sorting and progress calculations. Settings are device preferences and are not synchronised through the API. Android logging records lifecycle and operation events without deliberately logging authentication tokens.

## API and data

Base URL: `https://prog7314-poe-part-2.onrender.com/api/`

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `health` | Public service health response. |
| GET / POST | `subjects` | List or create the signed-in user's subjects. |
| PATCH / DELETE | `subjects/{id}` | Update or delete a subject. |
| GET / POST | `tasks` | List or create the signed-in user's tasks. |
| PATCH / DELETE | `tasks/{id}` | Update or delete a task. |

Every data endpoint requires `Authorization: Bearer <Firebase ID token>`. The root URL is not a webpage; use `/api/health` for the public health check.

- Subjects: `subjectId`, `name`, `lecturerName` (strings).
- Tasks: `taskId`, `subjectId`, `title`, `description`, `dueDate`, `priority` (strings), and `completed` (boolean).
- Dates use `YYYY-MM-DD`; priorities are `HIGH`, `MEDIUM` or `LOW`.
- Record IDs are generated by the backend and included in responses; do not include record IDs in create request bodies.
- Firestore paths: `users/{uid}/subjects/{subjectId}` and `users/{uid}/tasks/{taskId}`. The API also maintains an internal subject name key and a user write revision.

## Run the Android app

Requirements: Android Studio, JDK 17, Android SDK 35 and an Android device running Android 8.0/API 26 or later with Google sign-in available.

```powershell
git clone https://github.com/ST10435330/PROG7314_POE_Part_2.git
cd PROG7314_POE_Part_2
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\bootstrap-gradle.ps1
```

1. Open the project folder in Android Studio and allow Gradle to sync.
2. Register `com.studysync.app` in the Firebase project and enable Google sign-in.
3. Run `.\gradlew.bat signingReport` and register the debug signing fingerprints in Firebase.
4. Download the updated Firebase configuration to `app/google-services.json`.
5. Confirm the base URL in `StudyApi.kt`, connect the phone with USB debugging enabled and press **Run**.

Keep Firebase service-account credentials out of the repository and Android app.

## Run the backend locally

Use Node.js 24. From the project root:

```powershell
cd server
npm ci
Copy-Item .env.example .env
```

Configure `GOOGLE_APPLICATION_CREDENTIALS` in `.env` to point to an authorised Firebase service-account JSON file, and set `PORT=3000`. Enable Cloud Firestore in the same Firebase project as Authentication. Run `npm start` and open `http://localhost:3000/api/health`.

The deployed Render service uses root directory `server`, build command `npm ci` and start command `npm start`. Supply backend credentials through its secret configuration. Direct Firestore client access should be denied; the API accesses Firestore through Firebase Admin credentials.

## Tests and GitHub Actions

From the project root:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
Push-Location server
npm ci
npm test
Pop-Location
```

Android tests cover validation, task logic, offline storage and sync, notifications, and language preferences and resources. Backend tests cover validation, authentication requirements and user-scoped record paths. These tests do not replace live Firebase and physical-device testing.

- `android.yml` builds the debug app, runs JUnit tests and uploads test reports. Add the Firebase Android configuration as the repository secret `GOOGLE_SERVICES_JSON`.
- `backend.yml` installs locked dependencies and runs the Node test suite.
- Both workflows run on pushes and pull requests, and support manual runs. Check the latest commit's actual results through the Actions link above.


## Technical references

- [Firebase Google sign-in for Android](https://firebase.google.com/docs/auth/android/google-signin)
- [Firebase Admin ID token verification](https://firebase.google.com/docs/auth/admin/verify-id-tokens)
- [GitHub Actions documentation](https://docs.github.com/en/actions/get-started/understand-github-actions)
- [Deploying Express on Render](https://render.com/docs/deploy-node-express-app)
