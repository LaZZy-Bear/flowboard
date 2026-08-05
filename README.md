# 📱 Flowboard Android (Prototype 22 English Core)

**Flowboard** คือแอปพลิเคชันคีย์บอร์ดแบบ 9 ปุ่ม (3x3 Grid Keyboard) สำหรับระบบปฏิบัติการ Android ที่ประมวลผลด้วย **Offline Prediction Engine 100%** โดยถูกพอร์ตสถาปัตยกรรมระดับ 1:1 จาก **Prototype 22 (P22)** เพื่อแก้ปัญหาการพิมพ์ผิด (นิ้วเบียด) บนมือถือ ช่วยให้ผู้ใช้สามารถ **พิมพ์ด้วยมือเดียวได้อย่างสะดวก รวดเร็ว และแม่นยำ ด้วยอัตราการกดปุ่มตรงๆ (Tap Rate) สูงสุด**

---

## 🏆 ผลลัพธ์และสถิติประสิทธิภาพ (Benchmark Performance)

ผลการทดสอบผ่าน **BotTester (Pro Bot V22)** บนชุดประโยคมาตรฐาน 10 ประโยค (`DEFAULT_TEST_SENTENCES` - 304 ตัวอักษร) เปรียบเทียบระหว่าง **Web UI Prototype 22** และ **Flowboard Android App**:

| โหมดการทดสอบ (Evaluation Mode) | จำนวนตัวอักษร | อัตรากดตรง (Tap Rate) 🎯 | อัตราปัดนิ้ว (Swipe Rate) 👉 | อัตราพิมพ์ผิด (Miss Rate) ❌ | สถานะเทียบ Web UI |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **🔤 เฉพาะตัวอักษร (`Letters Only Mode`)** | **250 ตัวอักษร** | **89.2%** (223/250) | **10.8%** (27/250) | **0.0%** | ✅ **ตรงกัน 100.0%** |
| **⌨️ นับรวมทุกอย่าง (`Full Input Mode`)** | **304 ตัวอักษร** | **91.1%** (277/304) | **8.9%** (27/304) | **0.0%** | ✅ **ตรงกัน 100.0%** |

### 📊 สถิติเจาะลึกแยกตาม Engine State (State Breakdown)

```text
=========================================
   FLOWBOARD P22 REPORT (FULL INPUT)     
=========================================
Total Characters Tested: 304
Taps  : 277 (91.1%)
Swipes: 27 (8.9%)
Misses: 0 (0.0%)
-----------------------------------------
Engine Breakdown:
  State 1 (Start)                      -> Taps: 8/10   (80.0%)
  State 2 (Prefix len=1)               -> Taps: 62/62  (100.0%)
  State 3 (Prefix len=2)               -> Taps: 49/54  (90.7%)
  State 4 (Prefix len>=3)              -> Taps: 67/70  (95.7%)
  Spacebar (กดเว้นวรรค)                 -> Taps: 54/54  (100.0%)
  State 7 (Standard Spacebar)          -> Taps: 24/37  (64.9%)
  State 8 (Connector Spacebar)         -> Taps: 13/17  (76.5%)
=========================================
```

---

## 🧠 สถาปัตยกรรมระบบ (Engine Architecture)

### 1. 6-State Contextual Engine
ระบบคำนวณคะแนนตัวอักษรปรับเปลี่ยนน้ำหนักอัตโนมัติตามสถานะบริบทการพิมพ์ 6 สถานะ:
* **State 1 (Start)**: ตัวอักษรเริ่มต้นประโยค หรือคำแรก
* **State 2 (Prefix 1)**: พิมพ์ตัวอักษรแรกไปแล้ว 1 ตัว (เช่น พิมพ์ `h` กำลังจะพิมพ์ตัวถัดไป)
* **State 3 (Prefix 2)**: พิมพ์ตัวอักษรไปแล้ว 2 ตัว (เช่น `he`)
* **State 4 (Prefix 3+)**: พิมพ์ตัวอักษรไปแล้วตั้งแต่ 3 ตัวขึ้นไป (เช่น `hel`)
* **State 7 (Standard Spacebar)**: เคาะ Spacebar หลังคำศัพท์ทั่วไป (Content words)
* **State 8 (Connector Spacebar)**: เคาะ Spacebar หลังคำเชื่อม/กริยานุเคราะห์/คำสรรพนาม (Connector words เช่น `the`, `is`, `on`, `for`, `you`, `my`)

### 2. 7 Sub-Engines Layering
ผสมผสานคะแนนจาก 7 เลเยอร์ประมวลผล:
1. **U (Unigram / Start Unigram)**: ความถี่ตัวอักษรเดี่ยว (ใช้ `unigram_start` สำหรับ State 1, 7, 8)
2. **B (Character Bigram)**: ความถี่อักขระคู่
3. **T (Character Trigram)**: ความถี่อักขระ 3 ตัว
4. **D (Dictionary / Compressed Trie)**: ค้นหาในพจนานุกรมบีบอัดพร้อม OOV Fallback
5. **WB (Word Bigram)**: ทำนายตัวอักษรแรกของคำถัดไป จากคำก่อนหน้า 1 คำ
6. **WT (Word Trigram)**: ทำนายตัวอักษรแรกของคำถัดไป จากคำก่อนหน้า 2 คำ
7. **STC (Sentence Topic Clusters)**: ทำนายกลุ่มคำหัวข้อสัมพันธ์กันหลังคำเชื่อม (Connector words)

### 3. 3-Way Domino Layout Strategy
* **Dynamic Master Layout Placement**: จัดวางตัวอักษร 26 ตัวลงบนผัง 9 ปุ่มตาม Master Layout ที่ถูกปรับแต่งมาอย่างสมบูรณ์
* **Domino 3-Way Swap**: ตัวอักษรรองที่มีคะแนนสูงกว่าตัวหลักจะดันตัวหลักไปอยู่ช่องที่อ่อนแอที่สุด (Weakest Slot) เพื่อให้ตัวอักษรที่มีโอกาสใช้สูงสุดได้ตำแหน่ง `Tap` เสมอ
* **Lazy Tap Ratio (1.15x)**: หากตัวอันดับ 1 มีคะแนนสูงกว่าตัว Tap ดั้งเดิมไม่ถึง 15% จะให้ตัว Tap ดั้งเดิมครองตำแหน่งต่อเพื่อเสถียรภาพในการพิมพ์
* **Smart Sticky Keys**: ตรวจสอบคำย้อนหลังอัตโนมัติ ล็อกปุ่มเดิมเพื่อความสะดวกเมื่อพิมพ์ตัวอักษรซ้ำ (เช่น `ll`, `ee`, `ss`)

### 4. Personalization Machine Learning Layer & Profile System
* **Static JSON Profile Mode (`my_personal_profile.json`)**: เรียนรู้คู่คำและความถี่การพิมพ์ของผู้ใช้ รันเสริมแบบ Additive Layer โดยไม่ทำลาย Tap Rate พื้นฐาน
* **Real-Time Additive Safety**: คะแนนส่วนบุคคลบวกเพิ่ม *หลัง* กระบวนการ Normalization ของคะแนนพื้นฐาน การันตี 100% ว่าไม่ทำลายอัตรา Tap Rate หลักของ Engine

---

## ⚙️ ระบบ Profile & Personalization (Profile & Personalization System)

ระบบ Profile ใน Flowboard แบ่งออกเป็น 2 ส่วนหลัก:

### 1. System Rule Profile (`profile_chat.json`)
กำหนดกฎกติกาการคำนวณของระบบ (Behavior Rules) สำหรับโหมดการใช้งานต่างๆ (เช่น โหมดแชท):
* **`allow_echo`**: เปิด/ปิดระบบ Echo Booster (ดันคะแนนตัวอักษรเดิมเมื่อมีการพิมพ์เบิ้ล/พิมพ์ซ้ำ)
* **`echo_base_buff` / `echo_drag_buff`**: ค่าน้ำหนักคะแนนพิเศษเมื่อพิมพ์เบิ้ลตัวอักษร
* **`echo_immunity_ratio`**: อัตราส่วนความคุ้มกันการล็อกปุ่มเดิม
* **`bonus_dict`**: ตารางคะแนนโบนัสตัวอักษรพิเศษรายอักขระ

> [!IMPORTANT]
> ตัวแอปพลิเคชันและระบบการทดสอบทั้งหมดถูกกำหนดให้บังคับใช้ **`Profile.DEFAULT`** (`allow_echo = false`) เป็นโปรไฟล์หลักในทุกสถานการณ์ (`AssetLoader.kt`, `ProfileManager.kt`, `FlowboardRepository.kt`) เพื่อการันตีความแม่นยำและรักษาพฤติกรรมดั้งเดิมให้ตรงกับ Web UI Prototype 22 (`loader.js`) 100%

### 2. User Personalization Profile (`my_personal_profile.json`)
ไฟล์โปรไฟล์สกัดการเรียนรู้ส่วนบุคคลของผู้ใช้ สะสมผ่านพฤติกรรมการพิมพ์จริง ประกอบด้วย:
* **`wordFreq`**: คำศัพท์ติดปากของผู้ใช้ (ให้คะแนนบวกเพิ่ม `+0.2`, `+0.5`, `+1.2`, `+2.0` เมื่อ Engine หลักลังเล $\Delta_{gap} < 5.0$)
* **`bigram` & `trigram`**: วลีและลำดับคำ 2-3 คำที่ผู้ใช้ชอบพิมพ์บ่อย (ให้คะแนนโบนัส `+0.3`, `+0.8`, `+1.5`)
* **`trieDictOOV` (Auto-Learn OOV)**: คำเฉพาะหรือชื่อเฉพาะที่ไม่มีในพจนานุกรมหลัก เมื่อผู้ใช้พิมพ์ซ้ำครบ 3 ครั้ง ระบบจะเรียนรู้และบันทึกเข้าพจนานุกรมส่วนตัวให้อัตโนมัติ

### 3. การทำงานของ `ProfileManager.kt` และ `PersonalizationEngine.kt`
* **`ProfileManager`**: บังคับใช้ `Profile.DEFAULT` เป็นโปรไฟล์หลักของระบบในทุกกรณี จัดการสถานะเปิด/ปิด Personalization และบันทึก/อ่านไฟล์ `my_personal_profile.json` ลงดิสก์
* **`PersonalizationEngine`**: รับผิดชอบคำนวณโบนัสและปรับแต่งคะแนนตัวอักษรใน `ScoringEngine` หลังจากผ่านกระบวนการ Normalization ของ 7 Sub-engines เสร็จสิ้น

---

## 📁 โครงสร้างโฟลเดอร์และไฟล์สำคัญ (Project Structure)

โค้ดหลักของแอปพลิเคชันอยู่ที่ `app/src/main/java/com/flowboard/ime/`:

```text
app/src/main/
├── assets/en/                         # ข้อมูลพจนานุกรมและผังปุ่มภาษาอังกฤษ (Prototype 22)
│   ├── master_layout.json             # ผังปุ่มหลัก 3x3
│   ├── unigram.json                   # ความถี่ตัวอักษรเดี่ยว
│   ├── unigram_start.json             # ความถี่ตัวอักษรขึ้นต้นประโยค
│   ├── bigram.json                    # Character Bigram
│   ├── trigram.json                   # Character Trigram
│   ├── clustered_word_bigram.json     # Word Bigram แบบคลัสเตอร์
│   ├── clustered_word_trigram_en.json # Word Trigram แบบคลัสเตอร์
│   ├── sentence_topic_clusters.json   # บริบทกลุ่มคำหลังคำเชื่อม
│   ├── trie_dict_compressed.json      # Compressed Trie Dictionary หลัก
│   ├── trie_dict_oov.json             # Trie Dictionary สำรอง (OOV)
│   ├── word_list.json                 # ดรรชนีคำศัพท์
│   ├── profile_chat.json              # ตั้งค่ากฎโปรไฟล์
│   └── my_personal_profile.json       # ข้อมูลการเรียนรู้ส่วนบุคคลของผู้ใช้
│
└── java/com/flowboard/ime/
    ├── engine/                        # ประมวลผล N-Gram & ให้คะแนนคำศัพท์
    │   ├── ScoringEngine.kt           # 6-State N-Gram Engine (Ported 1:1 จาก scoring.js)
    │   ├── LayoutManager.kt           # 3-Way Domino Swap & Key Slots (Ported 1:1 จาก layout.js)
    │   ├── PersonalizationEngine.kt   # เลเยอร์เรียนรู้ส่วนบุคคล (Ported 1:1 จาก personalize.js)
    │   └── ProfileManager.kt          # จัดการข้อมูลโปรไฟล์ส่วนบุคคลของผู้ใช้
    │
    ├── service/                       # Background Service & Android IME Integration
    │   └── FlowboardIMEService.kt     # InputMethodService เชื่อมต่อ Lifecycle, InputConnection & Engine
    │
    ├── ui/                            # การแสดงผลแป้นพิมพ์ & การรับคำสั่งสัมผัส
    │   ├── KeyboardView.kt            # เรนเดอร์ผังปุ่ม 9 ช่อง & ควบคุมการลอยตัว/ย่อขยาย Window
    │   ├── KeyView.kt                 # แสดงผลตัวอักษรบนปุ่ม Tap, Up, Down, Left, Right
    │   └── SwipeDetector.kt           # ตรวจจับทิศทางการลากนิ้ว (Multi-touch & Swipe detection)
    │
    ├── data/                          # แหล่งข้อมูล & Data Models
    │   ├── FlowboardRepository.kt     # Repository กลางสำหรับถือ State และแคชข้อมูล Engine
    │   ├── AssetLoader.kt             # โหลดไฟล์ JSON Asset จาก background thread
    │   └── models/                    # Data Classes (MasterLayout, EngineWeights, TrieNode ฯลฯ)
    │
    └── testing/                       # เครื่องมือสอบทานระบบ Engine
        ├── BotTester.kt               # บอทจำลองการพิมพ์ (Ported 1:1 จาก bot.js)
        ├── BotTesterTest.kt           # Unit Test อัตโนมัติสำหรับวัด Tap Rate บน JUnit
        └── TestDataFactory.kt         # โหลด Asset เพื่อใช้ในการรัน Unit Test
```

---

## 🛠️ วิธีการรัน Unit Test & สอบทานประสิทธิภาพ Engine

สามารถรัน Unit Test ผ่าน Gradle Command Line หรือใน Android Studio เพื่อสอบทานอัตรา Tap Rate ของ Engine:

```bash
# รัน Unit Test ตรวจสอบ BotTester บน Android
./gradlew testDebugUnitTest --tests com.flowboard.ime.testing.BotTesterTest
```

---

## ⚠️ Rules & Constraints (กฎเหล็กในการพัฒนา)

* **Lifecycle Initialization:** ⛔ **ห้าม** สร้าง UI component หนัก ๆ ใน `onCreate()` ของ `InputMethodService` เด็ดขาด ให้ไปสร้างใน `onCreateInputView()` เพื่อป้องกัน Memory leaks และให้แอปโหลดเร็วขึ้น
* **Non-Blocking Data Parsing:** การโหลดไฟล์พจนานุกรม (JSON) และ Asset ต่าง ๆ ต้องทำบน Background Thread (`Dispatchers.IO`) เสมอ และใช้โครงสร้างข้อมูลแบบ Compressed Trie เพื่อประหยัด RAM
* **Single Engine Single Truth:** โค้ดใน `ScoringEngine.kt`, `LayoutManager.kt` และ `PersonalizationEngine.kt` ต้องรักษาความสอดคล้องระดับ 1:1 กับไฟล์อ้างอิง `scoring.js`, `layout.js`, `personalize.js` ใน Prototype 22 เสมอ ห้ามดัดแปลงสมการ N-Gram โดยไม่ได้รับการทดสอบผ่าน BotTester
* **WindowManager & Floating Resize Sync:** เวลาทำการย่อ/ขยายคีย์บอร์ดลอยตัว ห้ามแก้แค่ `scaleX`/`scaleY` ของ View เพราะจะทำให้โดนระบบตัดขอบ (Clip) **ต้อง** ปรับขนาด Bounding box ของ Window (ผ่าน `layoutParams.width` / `height`) ให้สอดคล้องกันเสมอ
* **Billing Activity Requirement:** ⛔ **ห้าม** เรียกหน้าต่างจ่ายเงิน (In-app purchase) จาก `InputMethodService` โดยตรง ระบบ `BillingClient` บังคับว่าต้องใช้ Context ของ `Activity` เท่านั้น

---

## 📜 License

พัฒนาโดยทีมงาน **Flowboard Project** (2026) — สำหรับแป้นพิมพ์สมาร์ทโฟนยุคใหม่ด้วยพลังการประมวลผล Offline AI Engine 100%
