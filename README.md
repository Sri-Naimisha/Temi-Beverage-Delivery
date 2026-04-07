# TemiApplication

Android app for a **Temi** robot that listens to **Firebase Realtime Database** at the **database root** (`location`, `status`, `orders`, …), drives **Charging → Pantry → Gaming** flows, speaks at delivery, and returns **home to Charging** after the guest taps **OK** at the gaming zone (or goes to **Pantry** again if more orders are pending). The manifest registers a **Temi skill** named *Temi Beverage Delivery*.

## What the screens look like

- **Customer (QR web) — `index.html`**: dark theme catalog with product cards (emoji “picture” or optional `imageUrl`), +/− quantity, and a “Your orders” section (queue + modify). Voice intro plays after the first tap (browser policy).
- **Admin (web) — `admin.html`**: dark theme dashboard with Inventory and Orders tabs, plus a small “Send Temi to …” dropdown for manual commands. Accept/Start Trip/Dispatch controls drive the robot via Firebase.
- **Temi tablet (Android) — `MainActivity`**: full-screen dark page showing a large status title + subtitle; when Temi arrives at `gaming`, an **OK** button appears for the guest.

### Temi tablet screen details (Android)

The Temi screen is a single full-screen page (no navigation UI) with:

- **Large headline** (`statusText`) showing the current phase (idle / arrived / moving).
- **Subtitle** (`txtWaiting`) showing guidance (e.g., “Waiting for staff…”, “Collect your order…”).
- **OK button** (`btnOk`) shown **only at `gaming`** after arrival so the guest can confirm pickup.

## Customer web app (default ordering UI)

- **`index.html`** (project root): customer **QR / mobile web** interface.
- **Voice intro**: Web Speech API after the initial **tap-to-start** overlay (browser policy). Includes a **Play again** button.
- **Catalog**: **5 beverages + 5 snacks** (Coke Can, Water, Living Labs rack snacks, etc.) with **picture** (emoji or optional `imageUrl` from Firebase), label, and **+ / −** quantity.
- **Submit**: creates `orders/{orderId}` with `status: "pending"` and a `sessionId`, sets `admin/notification_pending = true` and `admin/latest_order_id = {orderId}`.
- **Order queue UI**: users can submit multiple orders; “Your orders” shows queued/accepted/cancelled status.
- **Modify**: queued orders (`pending`) can be edited before admin accepts (updates `orders/{orderId}/items` and keeps `status: "pending"`). Cancel button was removed from the UI.
- **Waiting/accepted UX**: non-blocking footer status (e.g. “N orders in queue — waiting for admin”), and order cards update once admin changes status to `ongoing` / `accepted` / `complete`.
- **Inventory seed**: import **`database/inventory-seed.json`** into Realtime Database (or merge `inventory` + `admin` nodes). The page also falls back to embedded defaults if `inventory` is unreadable.

## Admin web app

- **`admin.html`** (project root): admin UI with **Firebase Authentication** (Email/Password).
- **Inventory tab**:
  - table with product name + quantity
  - **+1** button to increase quantity (uses Firebase transactions)
  - **−** button to decrease quantity (disabled at 0)
  - shows **Out of Stock** when quantity is 0
- **Orders tab**:
  - shows current and past orders (items, quantities, status)
  - **Accept** (Option A) reserves inventory and marks accepted **without moving Temi**:
    - atomically decrements `inventory/{sku}/quantity` for all items in the order (single transaction on `inventory/`)
    - `orders/{orderId}/status = "accepted"`
    - `orders/{orderId}/inventoryReservedAt = serverTimestamp()`
    - If stock is insufficient, Accept fails and nothing is decremented.
  - **Start trip** starts movement (only when no order is `ongoing` and Temi isn’t busy):
    - `orders/{orderId}/status = "ongoing"`
    - sets `location = "pantry"` to start pickup leg
  - When Temi arrives pantry (`status` contains `arrived_pantry`), **Dispatch → Gaming** appears for the `ongoing` order:
    - sets `location = "gaming"`
  - **Decline** button is present but disabled (per PSB)
- **Notification**:
  - driven by `admin/notification_pending`; pressing OK opens Orders and clears the flag.

### Admin Auth setup

1. In Firebase Console → **Authentication** → **Sign-in method**: enable **Email/Password**.
2. Create an admin user (email + password).
3. Open `admin.html` and sign in with that account.

## Inventory database (Firebase)

- **10 SKUs** in `database/inventory-seed.json`: **5 beverages** (includes **Coke Can**, **Water**) and **5 snacks** (including **Living Labs rack** items).
- Each item: `name`, `emoji`, optional `imageUrl`, `category` (`beverage` | `snack`), `quantity`, optional `customizable`.

## Three-location map (conceptual)

| Location (code constants) | Role |
|---------------------------|------|
| `Charging` (`LOC_CHARGING`) | Home / idle / dock — must match the **exact** name in Temi’s map |
| `pantry` (`LOC_PANTRY`) | Stock / load tray |
| `gaming` (`LOC_GAMING`) | Customer pickup / delivery stop |

**Important:** Edit `LOC_CHARGING`, `LOC_PANTRY`, and `LOC_GAMING` in `MainActivity.java` if your map uses different labels (e.g. "Charging Area", "Gaming Zone"). On robot ready, logcat prints **`TEMI_LOCATIONS`** with `robot.getLocations()` — spelling and capitalization must match.

## Intended operator flow

1. **Customer submits order** (`index.html`) → creates `orders/{orderId}` with `status="pending"` and sets `admin/notification_pending=true`.
2. **Admin accepts** (`admin.html`) → reserves inventory (decrements `inventory/*/quantity`) and sets `orders/{orderId}/status="accepted"` (no movement yet).
3. **Admin starts trip** → sets `status="ongoing"` and sets `location="pantry"` so Temi goes to pantry for pickup.
4. **Arrived at pantry** (Android) → `status="arrived_pantry"` and `location="none"`; staff loads items.
5. **Admin dispatches to gaming** → sets `location="gaming"`.
6. **Arrived at gaming** (Android) → `status="arrived_gaming"`, speaks the delivery line, shows OK button.
7. **Guest taps OK** (Android) → speaks “Good bye”, then:
   - if any order is still pending/accepted/ongoing (not delivered/cancelled) → sets `location="pantry"`
   - else → sets `location="Charging"`

There is **no** automatic 10s return to Pantry; **Gaming** exit is controlled by **OK** or by external Firebase writes.

## Firebase (flat root) and how it updates

```
root/
├── location: "none" | "pantry" | "gaming" | "Charging" | ...
├── status: "idle" | "arrived_pantry" | "arrived_gaming" | ...
├── admin/
│   └── notification_pending: false
│   └── latest_order_id: "{orderId}"
├── inventory/
│   └── ... (item SKUs)
└── orders/
    └── {orderId}/
        └── status: "pending" | "ongoing" | "accepted" | "complete" | "delivered" | "cancelled" | ...
        └── sessionId: "{uuid}"
        └── items: { "coke_can": 2, "water": 1, ... }
```

- **`location` / `status`:** Same as before; app reads/writes both.
- **`orders`:**
  - customer creates orders with `status="pending"`
  - admin sets `status="accepted"` (accepted, stock reserved, not started)
  - admin sets `status="ongoing"` (trip started)
  - after completion, admin should mark the served order `delivered`/`complete` so Temi doesn’t treat it as pending work
- **Temi decision after “Good bye”:** the Android app reads `orders/` once and goes to `pantry` if any order still needs service; otherwise goes to `Charging`.

## Architecture

| Piece | Role |
|--------|------|
| `MainActivity` | Firebase listeners, Temi listeners, navigation, TTS, OK button |
| Firebase Realtime Database | `location`, `status`, `orders` |
| Temi `Robot` API | `goTo`, `stopMovement`, `tiltAngle`, `speak(TtsRequest)`, kiosk, listeners |

## Navigation behavior (summary)

- **Idle / ignore:** `location` is `null`, `none`, or same as last handled command while not moving.
- **New target:** If the name exists in `robot.getLocations()`, after **3 s** delay → `goTo(target)`.
- **Arrival (`complete`):** Branch on **Pantry** / **Gaming** / **Charging** (see code); set `location` to `none` where appropriate; **no** auto timer to Pantry.
- **Failure (`abort` / `reject`):** After **7 s**, clear `location` then **500 ms** later retry the same destination.

## Temi integration

- **Permissions:** `INTERNET`, `MAP`, `LOCATIONS`, `SETTINGS`, `NAVIGATION` (`com.robotemi.permission.*`).
- **Metadata:** `com.robotemi.sdk.metadata.SKU` = `v1`; skill *Temi Beverage Delivery*.
- **Activity:** `singleInstance`, kiosk on ready, keep screen on, hide top bar.

## UI

- `activity_main.xml`: dark background (`#0F172A`), large status line, subtitle, **OK** (visible only at `gaming` after arrival).

## Project layout

```
app/
  src/main/java/com/infy/temiapplication/MainActivity.java
  src/main/AndroidManifest.xml
  src/main/res/layout/activity_main.xml
  google-services.json
```

## Build and run

- **JDK:** 11 · **SDK:** `compileSdk` / `targetSdk` 34, `minSdk` 24.
- **Firebase:** Realtime Database enabled; rules must allow the app to read/write `location`, `status`, and `orders` as needed.
- **Device:** Deploy to a **Temi** for full behavior.

## Tests

- `ExampleInstrumentedTest` asserts package `com.infy.temiapplication`.
