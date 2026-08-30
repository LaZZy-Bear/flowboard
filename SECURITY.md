# Security & Privacy Policy

Flowboard is an Input Method Editor (IME) designed with privacy as a foundational requirement.

---

## 100% Offline (Zero Network Access)

* **No Network Permission:** Flowboard does not request `android.permission.INTERNET` in its manifest.
* **No Telemetry:** No analytics, crash reporters, or third-party tracking libraries are included.
* **Local Processing:** All prediction scoring, dictionary lookups, and personalization models run strictly on-device.

---

## Local Data Protection

* **Encrypted Storage:** User personalization profiles and learned vocabulary are stored locally using App-Scoped AES-128 GCM encryption and managed atomically via `android.util.AtomicFile` for corruption-proof, sub-millisecond on-device persistence.
* **Sensitive Field Protection:** Dictionary learning and word predictions are automatically disabled in password fields, PIN inputs, and Incognito/private browsing tabs (`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`).
* **Cloud Backup Exclusion:** User dictionaries and typing profiles are excluded from automated Android cloud backups.

---

## Reporting a Vulnerability

If you find a security issue in Flowboard, please report it responsibly:

1. Do not open a public GitHub issue.
2. Submit a private advisory via [GitHub Security Advisories](https://github.com/LaZZy-Bear/flowboard/security/advisories) or email the maintainers directly.
3. We will review and acknowledge reports within **48 hours**.
