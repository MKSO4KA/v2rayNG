# v2rayNG (Constructor & Gist-Sync Fork)

An enhanced version of v2rayNG supporting dynamic outbound grouping, remote configuration management, and centralized blocklists. This fork targets users who need advanced control over their subscription outbounds using regex and automated tools.

[![API](https://img.shields.io/badge/API-24%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.3.0-blue.svg)](https://kotlinlang.org)

## 🚀 Fork Features

### 🛠 Auto-Outbound Builder (Constructor)
No more hardcoded groups. Define "Rules" that scan your subscription's proxy list and automatically create `POLICYGROUP` outbounds. If a rule's regex matches at least one proxy, a group is created. If no proxies match, the group is automatically cleaned up.

### ☁️ Remote Gist Synchronization
Host your grouping rules and domain blocklists on GitHub Gist. The app will sync them every time you update your subscription. 
- **Offline Resilience**: Rules are cached locally. If the Gist is blocked or the network is down, the last known configuration is used.
- **Dynamic Updates**: Use the Raw URL format `https://gist.githubusercontent.com/USER/GIST_ID/raw/filename.json` (without the commit hash) to ensure the app always pulls the latest version.

### 🏁 Smart Flags & Metadata Matching
Regex in this fork doesn't just match the proxy name. It matches against a metadata string: `[PROTOCOL] Remarks`.
- `{flag}`: Matches any country flag emoji (composed of two Regional Indicator symbols).
- `{flag:RU}`: Matches the specific flag of a country by its ISO code (e.g., 🇷🇺, 🇺🇸, 🇪🇪).
- **Example**: `^\[VLESS\].*{flag:EE}.*$` will catch only VLESS proxies from Estonia.

---

## 📄 JSON Specifications

### 1. Auto-Group Rules (`rules.json`)
Provide this URL in the **Subscription Setting > Auto-Groups Gist URL**.

```json
[
  {
    "remarks": "Overseas Auto",
    "regex": "^((?!.*(?i)whitelist).*)$",
    "strategy": "Least Ping",
    "tolerance": 50.0
  }
]
```
- **strategy**: `Least Ping`, `Least Load`, `Random`, `Round Robin`.
- **tolerance**: Milliseconds (e.g., 50.0). Only switches servers if the ping difference exceeds this value.

### 2. Blocklist (`blocklist.json`)
Provide this URL in the **Subscription Setting > Blocklist Gist URL**.

```json
[
  {
    "pattern": "domain:rezvorck.github.io",
    "comment": "Analytics block"
  },
  {
    "pattern": "geosite:category-ads-all",
    "comment": "Adblock"
  }
]
```
- **pattern**: Supports core standards: `domain:`, `full:`, `geosite:`, or raw IP/CIDR.

---

## 🛠 Development & Build

### Local Properties
This project uses an explicit property loader. To sign your APKs, add the following to `V2rayNG/local.properties`:
```properties
RELEASE_STORE_FILE=../your_key.jks
RELEASE_STORE_PASSWORD=your_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_password
```

### Building Core
The project includes an optimized `Makefile` for MSYS2/UCRT64 environments. Run `make all` to compile the Go Core (`AndroidLibXrayLite`) and the C++ Tunnel (`hev-socks5-tunnel`) and deploy them directly to the Android project folder.
