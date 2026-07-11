# Flowboard 9-Key Keyboard

## 🎯 Goal (เป้าหมายของโปรเจกต์)
สร้างแอปพลิเคชันคีย์บอร์ดแบบ 9 ปุ่ม (9-Key Keyboard) ที่ออกแบบมาเพื่อแก้ปัญหาการพิมพ์ผิด (นิ้วเบียด) โดยเฉพาะ โดยเน้นให้ผู้ใช้สามารถ **พิมพ์ด้วยมือเดียวได้อย่างสะดวก รวดเร็ว และแม่นยำ**

## 📂 Folder Structure (โครงสร้างไฟล์สำคัญ)
โค้ดหลักของแอปพลิเคชันทั้งหมดอยู่ที่โฟลเดอร์ `app/src/main/java/com/flowboard/ime/` โดยมีองค์ประกอบสำคัญดังนี้:

* **`service/`** (`FlowboardIMEService.kt`): หัวใจหลักของแอป (InputMethodService) ทำหน้าที่จัดการ Lifecycle ของคีย์บอร์ด เชื่อมต่อกับช่องป้อนข้อความ (InputConnection) และผูก UI เข้าด้วยกัน
* **`ui/`** (`KeyboardView.kt`, `KeyView.kt`, `SwipeDetector.kt`): จัดการเรื่องการแสดงผล UI, เลย์เอาต์แบบลอยตัว (Floating layouts), และการตอบสนองต่อการสัมผัส (Multi-touch, การลากนิ้วพิมพ์, และจำกัดขอบเขตการลาก/ย่อขยาย)
* **`engine/`** (`LayoutManager.kt`, `ScoringEngine.kt`, `ProfileManager.kt`): แกนหลักประมวลผล (Logic) สำหรับสลับโปรไฟล์ภาษา, จัดการผังปุ่มกดแบบไดนามิก, และอัลกอริทึมคาดเดาคำ/ให้คะแนนคำศัพท์
* **`data/`** (`AssetLoader.kt`, `FlowboardRepository.kt`, `models/`): จัดการข้อมูล เช่น การแคชไฟล์ Asset, Repository Pattern, และดึงข้อมูล JSON ของพจนานุกรม
* **`.agents/skills/`**: เก็บไฟล์ Document (`SKILL.md`) ควบคุมแนวทางสถาปัตยกรรม (เช่น IME framework, WindowManager, Localization และ Billing)

## ⚠️ Rules & Constraints (กฎเหล็กที่ห้ามละเมิดเด็ดขาด)
* **Lifecycle Initialization:** ⛔ **ห้าม** สร้าง UI component หนัก ๆ ใน `onCreate()` ของ `InputMethodService` เด็ดขาด ให้ไปสร้างใน `onCreateInputView()` เพื่อป้องกัน Memory leaks และให้แอปโหลดเร็วขึ้น (เลย์เอาต์ต้องปรับตาม `EditorInfo.inputType`)
* **Dynamic Localization:** เนื่องจากการเปลี่ยนภาษาของเครื่องจะไม่กระทบ `InputMethodService` โดยตรง (เพราะเป็น Background service ที่รันยาว) การสลับภาษาของคีย์บอร์ดจึงต้องใช้ `ContextWrapper` (เช่น `LocaleHelper`) เปลี่ยนภาษา Context แบบ On-the-fly แทน
* **Non-Blocking Data Parsing:** การโหลดไฟล์พจนานุกรม (JSON) และ Asset ต่าง ๆ ต้องทำบน Background Thread เสมอ (`Dispatchers.IO`) และให้เก็บเฉพาะข้อมูลภาษาที่กำลังใช้อยู่ไว้ใน RAM (ใช้โครงสร้างข้อมูลแบบ Trie เพื่อประหยัด Memory)
* **WindowManager & Floating Resize Sync:** เวลาทำการย่อ/ขยายคีย์บอร์ดลอยตัว ห้ามแก้แค่ `scaleX`/`scaleY` ของ View เพราะจะทำให้โดนระบบตัดขอบ (Clip) **ต้อง** ไปคำนวณและปรับขนาด Bounding box ของ Window (ผ่าน `layoutParams.width` / `height`) ให้สอดคล้องกันเสมอ
* **Billing Activity Requirement:** ⛔ **ห้าม** เรียกหน้าต่างจ่ายเงิน (In-app purchase) จาก `InputMethodService` โดยตรง ระบบ `BillingClient` บังคับว่าต้องใช้ Context ของ `Activity` เท่านั้น ต้องทำปุ่มส่งผู้ใช้ไปจ่ายเงินที่หน้า Settings หรือ Shop Activity แทน
