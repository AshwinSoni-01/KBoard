# KBoard

**A personalized fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) with custom themes, AI writing tools, and Klipy GIF & animated sticker support.**

KBoard takes the privacy-focused foundation of HeliBoard and adds modern, high-performance features for users who want more from their keyboard without sacrificing their data.

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="80">](https://github.com/AshwinSoni-01/KBoard/releases/tag/v2.0.0)

---

## 📋 Table of Contents
- [New Features](#-new-features)
- [Original Features](#original-features)
- [Contributing & Support](#contributing--support)
- [License & Legal](#license--legal)
- [Credits](#credits)

---

## ✨ New Features

### 1. The All-New Klipy Media Panel (GIFs & Animated Stickers)
The Emoji and Media panel has been completely rebuilt to integrate the Klipy API, bringing an endless library of media right to your keyboard.
* **Beautiful Layouts:** Browse GIFs in a gorgeous, staggered "river view" and find stickers in a clean, easy-to-tap 4-column grid.
* **Flawless WhatsApp Integration:** Say goodbye to the share sheet and flattened static images! KBoard uses a custom-built `libwebp` processing engine to format and drop animated stickers *directly* into your WhatsApp chat bubbles with full animation intact.
* **Bring Your Own Key:** Just like our AI tools, you are in control. Simply input your own personal Klipy API key in the settings to unlock unlimited GIF and sticker searches.

### 2. Frosted Glass Design (Material You Evolution) (ALPHA)
Experience a modern, high-fidelity UI with our brand-new **Frosted Glass** engine.
* **Dynamic Blur:** Real-time background blur for both Light and Dark modes.
* **Fully Customizable:** Adjust the opacity, saturation, and "frost" intensity to match your wallpaper and device aesthetic.

### 3. Access Point Menu (Enhanced Toolbar)
We have retired the old static toolbar in favor of the **Access Point Menu**.
* **Modernized Layout:** A cleaner, more intuitive way to access settings, clipboard, and one-handed mode.
* **Modular Design:** Faster navigation with refined iconography.

### 4. Selective Internet Capabilities
While the core of the keyboard remains offline-first, we have added **optional** internet capabilities. (Yes! you can turn it off! 😉)
* **Privacy Toggle:** Internet access is disabled by default. You decide when the keyboard connects to the web.
* **Safe Connectivity:** Built specifically to power AI and Klipy Media features while keeping your keystrokes private.

### 5. Gemini AI Integration
KBoard brings modern AI writing tools directly into your text field.
* **AI Writing Assistant:** Proofread, rewrite, or change the tone of your text instantly.
* **Bring Your Own Key:** Powered by Google Gemini. Simply input your own Gemini API key in settings to unlock local AI power without subscription fees.

---

## Original Features
* **Privacy-First:** Based on AOSP / OpenBoard.
* **Custom Dictionaries:** Add your own for suggestions and spell check.
* **Multilingual Typing:** Support for over 70+ languages.
* **Glide Typing:** Support for library extraction (swypelibs).
* **Backup & Restore:** Easily move your learned words and settings to a new device.

---

## Contributing & Support ❤
KBoard is a personal project, but the heavy lifting was done by the HeliBoard team.

### Support HeliBoard
**Please support the upstream HeliBoard project!** This fork would not be possible without their incredible work on open-source privacy. You can support them via their official channels:
* **GitHub:** [HeliBorg/HeliBoard](https://github.com/HeliBorg/HeliBoard)
* **Wiki & FAQ:** [HeliBoard Wiki](https://github.com/HeliBorg/HeliBoard/wiki)
* **Issues:** [HeliBoard Issue Tracker](https://github.com/HeliBorg/HeliBoard/issues)

---

## License & Legal

**KBoard** is a fork of **HeliBoard** (which is based on **OpenBoard** and **AOSP LatinIME**). 

As a derivative work, KBoard is licensed under the **GNU General Public License v3.0**. 

* **Copyleft Requirement:** In accordance with the GPL v3.0, the complete source code for KBoard is made available in this repository. Any further modifications or forks of KBoard must also be released under the same GPL v3.0 license.
* **Preservation:** All original copyright and license notices from the HeliBoard, OpenBoard, and AOSP projects have been preserved in the source headers.
* **Apache 2.0:** Since the app is based on the Apache 2.0 licensed AOSP Keyboard, those original terms also apply.
* **Brand Assets:** The **KBoard** icon (by Orion) is licensed under [Creative Commons BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

**Disclaimer:** *Google Gemini is a trademark of Google LLC. Klipy is a trademark of Klipy. KBoard is not affiliated with or endorsed by Google or Klipy. Use of Gemini and Klipy features requires personal API keys and is subject to their respective Terms of Service.*

---

## Credits
- **Ashwin Soni (Orion):** Fork maintainer; creator of the Frosted Glass UI, Klipy media pipeline, and AI implementation.
- **HeliBoard Team:** For the industry-leading open-source foundation.
- **NGI Mobifree Fund:** Funding provided to the original HeliBoard project through [NLnet](https://nlnet.nl).
- **AOSP / OpenBoard:** The ancestors of this project.
