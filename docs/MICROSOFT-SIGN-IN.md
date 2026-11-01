# Setting up Microsoft sign-in for Octo Launcher

Octo Launcher signs players in with their Microsoft account and starts
Minecraft itself. Microsoft and Mojang only allow that for an **app they
know about**, so Octo needs an app registration of its own. You do this
**once**, with your own Microsoft account. It costs nothing.

Until it's done (and approved), players can still play: in Octo Launcher,
**Settings → Play through the Minecraft Launcher** hands over to the official
launcher, which signs them in as usual.

---

## 1. Register the app in Azure

1. Open **<https://portal.azure.com>** and sign in with any Microsoft account.
   (If it asks you to create a subscription or tenant, a free account is enough;
   app registrations don't need a paid subscription.)
2. In the search bar at the top, type **App registrations** and open it.
   (It lives under **Microsoft Entra ID**.)
3. Click **+ New registration**.
4. Fill it in:
   - **Name:** `Octo Launcher`
   - **Supported account types:** choose
     **"Personal Microsoft accounts only"**.
   - **Redirect URI:** leave it empty. Octo uses a sign-in code, not a redirect.
5. Click **Register**.

## 2. Allow public client flows

Octo signs in with a code the player types at microsoft.com/link (the
"device code flow"). That needs one switch:

1. In your new app, open **Authentication** (left menu).
2. Scroll to **Advanced settings**.
3. Set **Allow public client flows** to **Yes**.
4. Click **Save**.

## 3. Copy the client ID

1. Open **Overview** (left menu).
2. Copy the **Application (client) ID**. It looks like
   `0a1b2c3d-1234-5678-9abc-def012345678`.

This ID is **not a secret**: it's built into the launcher and anyone can see
it. There is no client secret to create.

## 4. Ask Mojang to approve the app for Minecraft

Microsoft sign-in and Xbox Live work for any app, but the last step, signing
in to **Minecraft**, only works for apps Mojang has approved.

1. Open **<https://aka.ms/mce-reviewappid>**.
2. Fill in the form with your **client ID** from step 3, the app's name
   (Octo Launcher), and a short description, for example:
   *"Octo Launcher is an open-source Minecraft launcher and mod manager
   (https://github.com/MilkdromedaStudios/Octo-Loader). It signs players in
   with their Microsoft account to launch Minecraft: Java Edition."*
3. Submit it and wait. Approval can take **days or weeks**. Mojang replies by
   email.

**Before approval**, sign-in gets through Microsoft and Xbox, then stops at the
last step, and Octo shows: *"Minecraft has not approved this build of Octo
Launcher for sign-in yet."* That's expected.

## 5. Give the ID to the builds

So every build of Octo Launcher has the ID built in:

1. Open the repository on GitHub:
   **<https://github.com/MilkdromedaStudios/Octo-Loader>**.
2. Go to **Settings → Secrets and variables → Actions**.
3. Open the **Variables** tab. It's a *variable*, **not** a secret, because
   the ID is public.
4. Click **New repository variable**:
   - **Name:** `OCTO_MSA_CLIENT_ID`
   - **Value:** the client ID from step 3
5. Click **Add variable**.

The next build (any push to `main`, or a release) includes it. Builds pick it
up through the `OCTO_MSA_CLIENT_ID` environment variable in
`.github/workflows/build.yml` and `release.yml`.

### Trying it before a new build

In Octo Launcher open **Settings → Microsoft app ID**, paste the client ID,
then press **Sign in** in the title bar.

For a local build: `./gradlew :launcher:launcherJar -PmsaClientId=<your client ID>`.

---

## Checking it works

1. Open Octo Launcher and press **Sign in** (top right).
2. Click **Copy code and open Microsoft**, paste the code on Microsoft's page,
   and sign in with an account that owns Minecraft: Java Edition (or has Xbox
   Game Pass).
3. The launcher shows **"Signed in as <your name>"** with your skin's face.

| What you see | What it means |
|---|---|
| *"Microsoft does not know this build's app registration (unauthorized_client)"* | The client ID is wrong, or **Allow public client flows** (step 2) is off. |
| *"Minecraft has not approved this build of Octo Launcher for sign-in yet"* | Steps 1–3 are right; Mojang hasn't approved the app yet (step 4). |
| *"This Microsoft account has no Xbox profile yet"* | Sign in once at xbox.com with that account, then try again. |
| *"This Microsoft account does not own Minecraft: Java Edition"* | Use the account that bought the game. |
