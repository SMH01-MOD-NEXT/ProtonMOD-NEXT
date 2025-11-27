# ProtonMOD‑Next for Android
> ⚠️ **EDUCATIONAL USE ONLY** — This fork modifies client-side behavior and may unlock paid features. Not for commercial use or production.
Based on [ProtonVPN Android](https://github.com/ProtonVPN/android-app)  
© 2025 SMH01 — Community modification under GPLv3
***
## Overview
ProtonMOD‑Next is an **actively developed experimental fork** of the official ProtonVPN Android client.  
It is aimed at users in **heavily restricted networks** and focuses on:
- Transparent **VLESS proxy (Xray) integration**
- **Disabling GuestHole** (pre‑login VPN tunnel)
- **Disabling Auto‑connect on process restore**
- Keeping **TLS certificate pinning** fully intact
***
## Legal / Ethical Notice
This project is provided **for educational and research purposes only**.
ProtonMOD‑Next is an unofficial community fork of the Proton VPN Android client.  
It **modifies client‑side checks and may unlock paid or restricted features** that are normally available only with a valid Proton VPN subscription.
### Usage Terms
- Do **not** use this project for any kind of **commercial activity or profit**.
- Do **not** use this fork in **production environments**.
- Using this fork with your own Proton account is **entirely at your own risk**.
- You are solely responsible for ensuring that your usage complies with Proton VPN's Terms of Service and your local laws.
- The author is **not affiliated** with Proton AG and provides **no warranties or support**.
### If You Like Proton VPN
**Support the original project and purchase a legitimate plan.**  
Proton AG provides excellent privacy-focused services, and they deserve your support.
***
## Screenshots
<p align="center">
  <img src="https://raw.githubusercontent.com/ProtonVPN/android-app/master/metadata/en-US/images/phoneScreenshots/2.jpg" width="250" alt="Connection screen"/>
  <img src="https://raw.githubusercontent.com/ProtonVPN/android-app/master/metadata/en-US/images/phoneScreenshots/3.jpg" width="250" alt="Country Screen"/>
  <img src="https://raw.githubusercontent.com/ProtonVPN/android-app/master/metadata/en-US/images/phoneScreenshots/4.jpg" width="250" alt="Connection"/>
</p>

*(Screenshots from the official ProtonVPN Android app. ProtonMOD‑Next currently shares the same UI, with modified internals.)*
***
## Features / Modifications
- **VLESS proxy integration (Xray)**  
  Proton API traffic (login, IP checks, account metadata) can be routed through a local VLESS proxy.  
  Implemented via a custom `ProxySelector` applied only to Proton API hosts.
- **GuestHole disabled**  
  The pre‑login GuestHole tunnel is suppressed to avoid failed or suspicious VPN attempts  
  in environments where Proton endpoints are blocked.
- **Auto‑connect suppression**  
  Automatic VPN connection on process restore (`ConnectTrigger.Auto`) is disabled to prevent  
  unwanted sessions before the user explicitly connects.
- **Security preserved**  
  The original TLS certificate pinning is kept. Connections to Proton servers are still validated  
  against their official pinned certificates.
***
## Build Instructions
Clone the repository and build with Gradle:
```bash
./gradlew assembleProductionVanillaOpenSourceDebug
```
### Android Studio
1. Open **Android Studio** (latest stable recommended).
2. Select **File → Open…** and choose the root folder of this repository.
3. Wait for Gradle sync to finish (first sync may take several minutes).
4. In the toolbar, select the build variant:
    - `productionVanillaOpenSourceDebug` — development / testing
    - `productionVanillaOpenSourceRelease` — release build
5. Press **Run ▶** to install on a connected device or emulator.
You can also use **Build → Build Bundle(s) / APK(s)** to generate APKs directly from the IDE.
***
## Roadmap
- [x] Integrate VLESS proxy into Proton API requests
- [x] Suppress GuestHole (pre‑login VPN tunnel)
- [x] Disable proxy when not required
- [x] Suppress auto‑connect on process restore
- [ ] Add AMOLED‑optimized dark theme (true black + contrast tweaks)

*(Roadmap is intentionally small and focused; more items will be added as the project stabilizes.)*
***
## Contributions
Pull requests and issues to this fork's repository are **allowed and very welcome**.
Bug fixes, refactoring, documentation improvements, and clean feature implementations are especially appreciated.
***
## Development Status
🚧 **Active, experimental**
APIs and behavior may change between builds.  
If you depend on a specific behavior, **pin to a tag** and follow release notes / changelog.
***
## License
This project is a community modification of ProtonVPN for Android and is distributed under the **GPLv3**.  
See `LICENSE` for details.

