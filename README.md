# ProtonMOD‑Next for Android

Based on [ProtonVPN Android](https://github.com/ProtonVPN/android-app)  
© 2025 SMH01 — Community modification under GPLv3

---

## Overview

**ProtonMOD‑Next** is an **actively developed experimental branch** of the ProtonVPN Android client.  
It is designed for regions with heavy network restrictions and introduces:

- Transparent **VLESS proxy integration** (via Xray)
- **Suppression of GuestHole** (pre‑login VPN tunnel)
- **Suppression of Auto‑connect on process restore**
- Retained **certificate pinning** for full end‑to‑end security

New features are being added **gradually** as development continues.

---

## Screenshots

<p align="center">
  <img src="https://raw.githubusercontent.com/ProtonVPN/android-app/master/metadata/en-US/images/phoneScreenshots/2.jpg" width="250" alt="Connection screen"/>
  <img src="https://raw.githubusercontent.com/ProtonVPN/android-app/master/metadata/en-US/images/phoneScreenshots/3.jpg" width="250" alt="Country Screen"/>
  <img src="https://raw.githubusercontent.com/ProtonVPN/android-app/master/metadata/en-US/images/phoneScreenshots/4.jpg" width="250" alt="Connection"/>
</p>

*(Screenshots from the ProtonVPN Android app — ProtonMOD‑Next UI remains identical, but with modified internals.)*

---

## Key Modifications

- **VLESS Proxy Integration**  
  Proton API traffic (login, IP retrieval, account checks) is routed through a local VLESS proxy (Xray).  
  Implemented via a custom `ProxySelector` that applies only to Proton API hosts.

- **GuestHole Disabled**  
  The built‑in GuestHole mechanism (pre‑login VPN tunnel) has been suppressed.  
  This avoids unnecessary VPN attempts in blocked regions.

- **Auto‑Connect Suppression**  
  Automatic VPN connection on process restore (`ConnectTrigger.Auto`) has been disabled.  
  This prevents unwanted VPN sessions before login.

- **Security Preserved**  
  TLS certificate pinning remains intact. Proton servers are still verified against their original pinned certificates.

---

## Build Instructions

Clone the repository and build with Gradle:

```bash
./gradlew assembleProductionVanillaOpenSourceDebug
```
### Android Studio

1. Open **Android Studio** (latest stable version recommended).  
2. Select **File → Open…** and choose the root folder of this repository.  
3. Let Gradle sync the project (first sync may take a few minutes).  
4. In the toolbar, select the build variant:  
   - `productionVanillaOpenSourceDebug` for development builds  
   - `productionVanillaOpenSourceRelease` for release builds  
5. Press **Run ▶** to install on a connected device or emulator.  

You can also use **Build → Build Bundle(s) / APK(s)** to generate APKs directly from the IDE.

---

## Development Status

🚧 **Active Development**  
This project is under continuous development.  
New features and improvements are added step by step, with a focus on:

- Proxy integration refinements  
- Build system optimizations  
- Documentation and reproducibility  

---

## Roadmap

- [x] Integrate VLESS proxy into Proton API requests  
- [x] Suppress GuestHole (pre‑login VPN tunnel)
- [x] UI integration during login  
- [x] Disable proxy when not required  

*(More items will be added as development progresses.)*

