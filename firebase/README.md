# Firebase

The app signs in with Google through Firebase Authentication and keeps each user's saved data
in Cloud Firestore. tt-server checks the sign-in (it never talks to Firestore).

[`firestore.rules`](firestore.rules) are the security rules: a signed-in user can only read and
write their own `users/{uid}/prefs/{key}` documents. Paste them into Firebase console >
Firestore Database > Rules > Publish, or deploy them with the Firebase CLI
(`firebase deploy --only firestore:rules`).

Setting up the project is in [androidApp/README.md](../androidApp/README.md), "Sign-in and
saved data".
