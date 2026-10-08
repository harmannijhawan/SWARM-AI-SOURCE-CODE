# SwarmAI Android – Design Specification  
*Prepared for the UI/UX team. All dimensions are in **dp**, colors in **hex**, typefaces in **font‑stack**. No code – only visual & interaction guidelines.*

---

## 1. Global Design Tokens  

| Token | Value |
|-------|-------|
| **Palette** | <ul><li>Primary: `#0A84FF` (Blue‑80)</li><li>Primary‑Dark: `#0060DF`</li><li>Secondary: `#FF9F0A` (Orange‑80)</li><li>Background: `#F5F5F7` (Gray‑10)</li><li>Surface: `#FFFFFF`</li><li>On‑Primary: `#FFFFFF`</li><li>On‑Surface: `#1C1C1E`</li><li>Divider: `#C6C6C8`</li><li>Success: `#34C759`</li><li>Error: `#FF3B30`</li></ul> |
| **Typography** | **Roboto** – `Roboto, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif` |
| **Font Scale** | <ul><li>Display‑Large: 32sp, weight Bold</li><li>Headline‑Medium: 24sp, weight Medium</li><li>Title‑Medium: 20sp, weight Medium</li><li>Body‑Large: 16sp, weight Regular</li><li>Label‑Small: 12sp, weight Regular</li></ul> |
| **Spacing** | 4‑dp grid: 4, 8, 12, 16, 24, 32, 40, 48, 64 |
| **Corner Radius** | 8 dp (cards, buttons) – 12 dp for modal dialogs |
| **Elevation** | 0 dp (surface), 2 dp (cards), 4 dp (bottom bar), 8 dp (dialog) |
| **Iconography** | Material Icons – 24 dp (regular), 20 dp (dense) – stroke 2 dp, color `On‑Surface` unless otherwise noted. |
| **Touch Target** | Minimum 48 dp × 48 dp. |
| **Animation** | Fade‑in/out 150 ms, slide‑up 250 ms, ripple 200 ms (standard Android). |

---

## 2. Layout Grid & Responsiveness  

| Breakpoint | Width (dp) | Columns | Gutter | Side Padding |
|------------|------------|---------|--------|--------------|
| **Phone (small)** | ≤ 360 | 4 | 8 | 16 |
| **Phone (regular)** | 361‑420 | 4 | 8 | 20 |
| **Tablet (portrait)** | 421‑600 | 6 | 12 | 24 |
| **Tablet (landscape)** | > 600 | 8 | 16 | 32 |

All screens use **ConstraintLayout**‑style constraints in Compose (`Modifier.fillMaxSize().padding(...)`). Content re‑flows to the next column when space permits (e.g., model cards on tablet become a 2‑column grid).

---

## 3. Component Library  

### 3.1 App Bar  
*Height:* 56 dp (phone) / 64 dp (tablet)  
*Background:* `Surface`  
*Left:* Navigation icon (hamburger) – 24 dp, `On‑Surface`  
*Title:* `Title‑Medium`, `On‑Surface`  
*Right:* Optional action (settings gear) – 24 dp  

**States**  
- **Default** – solid background.  
- **Scrolled** – elevation 4 dp, background unchanged.  

### 3.2 Bottom Navigation Bar  
*Height:* 56 dp  
*Background:* `Surface` + elevation 4 dp  
*Items:* Icon + label (`Label‑Small`).  
*Selected:* Icon & label `Primary`; unselected `On‑Surface` 60% opacity.  

### 3.3 Card (Model / Agent)  
*Size:* 160 dp × 200 dp (phone), 200 dp × 240 dp (tablet)  
*Background:* `Surface`  
*Radius:* 8 dp, **elevation:** 2 dp  
*Content:*  
- Top: Image (provided PNG) – 100 % width, 120 dp height, `scaleType = CropCenter`.  
- Bottom: Title (`Title‑Medium`), subtitle (`Body‑Large`), optional badge (chip).  

**Interaction**  
- **Default** – no overlay.  
- **Pressed** – 12 % black overlay, ripple.  
- **Focused** – outline 2 dp `Primary`.  

### 3.4 Chip (Badge)  
*Height:* 24 dp, **radius:** 12 dp  
*Background:* `Secondary` (for “New”) or `Primary‑Dark` (for “Beta”).  
*Text:* `Label‑Small`, `On‑Primary`.  

### 3.5 Button (Primary)  
*Height:* 48 dp, **radius:** 8 dp, **fill:** `Primary`.  
*Text:* `Title‑Medium`, `On‑Primary`.  

**States**  
- **Enabled** – solid `Primary`.  
- **Disabled** – `Primary` 30% opacity.  
- **Pressed** – `Primary‑Dark`.  

### 3.6 Switch (Settings)  
*Track:* `Divider` (off) / `Primary` (on).  
*Thumb:* `Surface` with 1 dp border `Divider`.  

### 3.7 Dialog (Demo Result)  
*Width:* 80 % of screen, max 360 dp.  
*Background:* `Surface`, radius 12 dp, elevation 8 dp.  
*Header:* Title (`Headline‑Medium`), close icon top‑right.  
*Body:* Scrollable `Body‑Large`.  
*Actions:* Primary button (“Run Again”) + Text button (“Close”).  

---

## 4. Screen‑by‑Screen Mockup Specification  

### 4.1 Onboarding Flow (3 screens)  

| Element | Placement | Size / Padding | Notes |
|---------|-----------|----------------|-------|
| **Background** | Full‑screen | `Background` | Gradient not required – solid. |
| **Top Image** | Center‑top | 240 dp height, full width, `scaleType = FitCenter`. Uses supplied PNG (e.g., `onboard_1.png`). |
| **Title** | Below image | `Display‑Large`, centered, margin‑top 24 dp. |
| **Subtitle** | Under title | `Body‑Large`, centered, margin‑top 12 dp, max‑width 280 dp. |
| **Progress Dots** | Bottom‑center | 3 circles, 8 dp diameter, spacing 8 dp. Active dot `Primary`, inactive `Divider`. |
| **CTA Button** | Bottom‑center | Primary button, width 80 % (max 280 dp), margin‑bottom 32 dp. Text: “Next” (or “Get Started” on final screen). |
| **Skip Link** | Top‑right | Text button, `Label‑Small`, `Primary` color, margin‑top 16 dp, margin‑end 16 dp. |

**Interaction**  
- Swiping left/right animates slide transition (horizontal, 300 ms).  
- Tap “Next” advances; “Skip” jumps to Home.  

### 4.2 Home Screen  

| Region | Content |
|--------|---------|
| **App Bar** | Title: **SwarmAI**; Settings icon (right). |
| **Top Section** | **Featured Model** card (full‑width, 200 dp height) – uses PNG `model_featured.png`. Overlay title + “Run Demo” button (primary, bottom‑right). |
| **Model Grid** | LazyVerticalGrid (2‑col phone, 3‑col tablet). Each card follows **Card** spec. Shows model thumbnail, name, and a small “Beta” chip if applicable. |
| **Floating Action Button** | Bottom‑right, circular, `Primary`, icon “add” (24 dp). Opens **Create Model** (out of scope). |
| **Bottom Nav** | Items: Home (selected), Agents, Settings. |

**States**  
- **Loading** – skeleton shimmer on cards (background `#E0E0E0`).  
- **Empty** – centered illustration + “No models found” text, primary button “Add Model”.  

### 4.3 Settings Screen  

| Section | Elements |
|---------|----------|
| **App Bar** | Title: **Settings**; back arrow left. |
| **Provider List** | Vertical list, each row: Provider logo (24 dp), name (`Body‑Large`), trailing switch. Switch toggles “Enabled”. |
| **Preferences** | **Section Header** (`Label‑Small`, uppercase, `On‑Surface` 60% opacity). Items: <ul><li>“Use GPU” – switch.</li><li>“Show Debug Logs” – switch.</li><li>“Language” – dropdown (chevron). </li></ul> |
| **About** | Card with app version, build number, and “Open Source Licenses” link. |
| **Logout Button** | Full‑width primary button, red background `Error` for “Sign Out”. |

**Interaction**  
- Switch