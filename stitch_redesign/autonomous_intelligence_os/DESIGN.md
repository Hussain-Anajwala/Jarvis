---
name: Autonomous Intelligence OS
colors:
  surface: '#0f131c'
  surface-dim: '#0f131c'
  surface-bright: '#353942'
  surface-container-lowest: '#0a0e16'
  surface-container-low: '#181c24'
  surface-container: '#1c2028'
  surface-container-high: '#262a33'
  surface-container-highest: '#31353e'
  on-surface: '#dfe2ee'
  on-surface-variant: '#bdc8d1'
  inverse-surface: '#dfe2ee'
  inverse-on-surface: '#2c3039'
  outline: '#87929a'
  outline-variant: '#3e484f'
  surface-tint: '#7bd0ff'
  primary: '#8ed5ff'
  on-primary: '#00354a'
  primary-container: '#38bdf8'
  on-primary-container: '#004965'
  inverse-primary: '#00668a'
  secondary: '#a4c9ff'
  on-secondary: '#00315d'
  secondary-container: '#0267b8'
  on-secondary-container: '#d6e5ff'
  tertiary: '#a1d2ff'
  on-tertiary: '#003351'
  tertiary-container: '#5cb9ff'
  on-tertiary-container: '#00486f'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#c4e7ff'
  primary-fixed-dim: '#7bd0ff'
  on-primary-fixed: '#001e2c'
  on-primary-fixed-variant: '#004c69'
  secondary-fixed: '#d4e3ff'
  secondary-fixed-dim: '#a4c9ff'
  on-secondary-fixed: '#001c39'
  on-secondary-fixed-variant: '#004883'
  tertiary-fixed: '#cce5ff'
  tertiary-fixed-dim: '#93ccff'
  on-tertiary-fixed: '#001d31'
  on-tertiary-fixed-variant: '#004b73'
  background: '#0f131c'
  on-background: '#dfe2ee'
  surface-variant: '#31353e'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 40px
    fontWeight: '600'
    lineHeight: 48px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Inter
    fontSize: 30px
    fontWeight: '600'
    lineHeight: 38px
    letterSpacing: -0.015em
  headline-md:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  title-lg:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '500'
    lineHeight: 26px
  title-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '500'
    lineHeight: 24px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-lg:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 20px
    letterSpacing: 0.01em
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.02em
  label-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.03em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1rem
  margin: 1rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
---

## Brand & Style

This design system establishes a focused, privacy-first mobile computing paradigm for on-device AI operations. Drawing inspiration from modern utilitarian OS shells and refined technical instrumentation, the aesthetic rejects sensory overload and synthetic flashiness in favor of tranquil precision, absolute stability, and immediate clarity.

### Personality & Demographics
- **Tone:** Methodical, quiet, hyper-competent, sovereign.
- **Audience:** Developers, power users, privacy advocates, and professionals orchestrating high-context workflows on edge hardware.
- **Emotional Response:** Complete cognitive ease, psychological safety, and absolute command over system-level autonomy without the threat of data leakage.

### Visual Architecture
The system merges **Corporate Modernism** with **Engineered Minimalism**. Depth is established not via aggressive drop shadows, but through tonal layering of carbon-tinted obsidian surfaces, low-contrast tactical borders, and selective electric-blue luminance reserved strictly for real-time computation, active inference, and system state confirmation.

## Colors

The palette operates in strict dark mode across every screen without variant themes. Visual hierarchy relies on tonal luminance stepping and selective chromatic bursts.

### Palette Architecture
- **Base Canvas (`#0B0F17`):** The foundational substrate of the application. Pure absorption, non-reflective, anchoring the OS experience.
- **Surface Level 1 (`#131A26`):** The default elevated surface for structural panels, persistent headers, and systemic dock frames.
- **Surface Level 2 / Container (`#1C2536`):** Modular units, cards, dialogue sheets, and distinct operational hubs.
- **Border / Divider (`#222E42`):** Structural delineation lines providing precise technical definition without visual friction.
- **Primary Accent (`#38BDF8`):** Electric Sky. Denotes active AI synthesis, verified privacy seals, and primary functional triggers.
- **Secondary Accent (`#60A5FA`):** Kinetic Blue. Used for interactive states, hover/focus rings, and secondary telemetry tags.
- **Tertiary Accent (`#0284C7`):** Deep Cyber Blue. Used for container fills behind bright active iconography and progress trajectories.
- **Text Primary (`#F8FAFC`):** Crisp alabaster for maximum contrast on dark grounds.
- **Text Secondary (`#94A3B8`):** Muted slate for metadata, supporting technical parameters, and standard labels.
- **Text Tertiary (`#64748B`):** Deep slate for inactive hardware telemetry, timestamps, and subtle structural watermarks.

## Typography

The typographic hierarchy is calibrated strictly around **Inter** for uniform mechanical legibility, neutral rendering, and tabular numeric alignment across mobile displays.

### Execution Principles
- **Weight Pairing:** Titles and display elements utilize `600` (Semi-Bold) to create crisp anchors against dark backgrounds. Explanatory and contextual passages employ `400` (Regular) to mitigate optical bleeding on dark OLED/LCD panels.
- **Letter Spacing:** Headlines utilize slightly tightened tracking (`-0.01em` to `-0.02em`) to maintain cohesion at large sizes. Small-scale functional metadata (`label-sm`, `label-md`) introduces positive tracking to ensure instant scannability during automated log execution.
- **Data Rendering:** Timestamps, compute counters, process IDs, and model parameters should leverage OpenType tabular figures (`tnum`) to eliminate spatial jitter during real-time status updates.

## Layout & Spacing

Layout geometry follows an 8pt base grid with a 4pt sub-grid for high-density Android components.

### Grid & Boundaries
- **Mobile Handset Viewport:** Dynamic 4-column fluid layout with `16px` (`1rem`) outer canvas margins and `16px` gutters.
- **Tablet / Foldable Viewport:** 8-column layout utilizing `24px` margins and `16px` gutters, transitioning multi-stage execution flows into side-by-side master-detail splits.
- **Safe Zones:** Content respects Android system bars (status bar and gesture navigation pill) using native window insets, ensuring edge-to-edge rendering behind semi-transparent controls.

### Density and Cadence
- Vertical component stacking is separated by `space-md` (`16px`) by default.
- Structural sub-sections (e.g., separating automation triggers from operational logs) utilize `space-xl` (`32px`).
- Card padding remains uniformly committed to `space-md` (`16px`) for micro metrics and `space-lg` (`24px`) for primary control consoles.

## Elevation & Depth

This design system avoids traditional fuzzy drop shadows. Depth and spatial priority are communicated through Material 3-aligned tonal surface stepping paired with low-contrast vector boundaries.

### Tonal Hierarchy
- **Level 0 (Base Substrate):** Canvas background (`#0B0F17`).
- **Level 1 (Docked/Fixed Elements):** App headers, contextual action bars, and bottom navigation pill containers (`#131A26`) with a top border of `1px solid #222E42`.
- **Level 2 (Active Cards & Structural Units):** System cards, plan containers, and permission sheets (`#1C2536`) with an encompassing perimeter outline of `1px solid #222E42`.
- **Level 3 (Modals & Overlays):** Floating dialogs and bottom system sheets (`#222E42`), layered above a 60% opacity `#0B0F17` scrim.

### Luminescent State Elevation
Active inference and focused inputs manifest elevation via localized inner/outer chromatic glow rather than black shadows:
- **Active AI Processing:** Outlines transition from `#222E42` to `#38BDF8` with a restrained ambient outer glow: `box-shadow: 0 0 16px -2px rgba(56, 189, 248, 0.18)`.

## Shapes

The geometric identity balances organic tactility with mechanical enclosure through three strict radius specifications:

- **16px (`rounded-lg`):** Standard operational cards, execution plan blocks, permission consent containers, and dialogue windows.
- **24px / Full Pill:** Primary interactive buttons, floating execution controllers, text input boundaries, and the master navigation bar.
- **12px (`rounded-md`):** Status badges, sensor chips, filter pills, and inline code parameters.

## Components

### Buttons
- **Primary Execution Button:** Pill geometry (height `48px`, border-radius `24px`), solid `#38BDF8` fill with `#0B0F17` semi-bold typography (`label-lg`). Pressed state steps down to `#0284C7`.
- **Secondary System Button:** Pill geometry, `#1C2536` background with a `1px solid #222E42` border and `#F8FAFC` label.
- **Destructive/Revoke Button:** Pill geometry, transparent fill with `1px solid rgba(239, 68, 68, 0.4)` and `#EF4444` label.

### Chips & Badges
- **Status Chips:** Height `28px`, border-radius `12px`. Neutral state uses `#131A26` fill, `1px solid #222E42` border, and `#94A3B8` text (`label-sm`).
- **Active AI / Hardware Indicator:** Fill tinted with 10% `#38BDF8` opacity, `1px solid #38BDF8` border, and crisp `#38BDF8` text accompanied by a static or pulsing `6px` status dot.

### Lists
- Rendered with flush dividers (`1px solid #222E42`).
- List items feature a minimum touch target of `56px`, containing primary action titles in `#F8FAFC`, secondary execution details in `#94A3B8`, and trailing metadata or toggles aligned to the right margin.

### Checkboxes, Switches & Radio Controls
- **Switches:** Android M3 track style (width `52px`, height `32px`). Inactive track uses `#1C2536` with a `#222E42` border and `#64748B` thumb. Active track fills with `#0284C7` with a `#38BDF8` thumb.
- **Checkboxes & Radios:** `20px` bounds with `1px solid #222E42`. When checked, surfaces fill with `#38BDF8` with a `#0B0F17` check/dot glyph.

### Input Fields
- Enclosed pill-style or rounded container (height `52px`, border-radius `16px`).
- Background `#131A26`, border `1px solid #222E42`, text `#F8FAFC`, placeholder `#64748B`.
- Focused state shifts the border to `1px solid #38BDF8` with zero jitter.

### Cards
- Radius `16px`, background `#1C2536`, perimeter border `1px solid #222E42`, internal padding `16px`. Used to isolate atomic plans, log segments, and automation triggers.

### System Navigation Pill
- **Structure:** Floating bottom dock anchored with `16px` margins from screen edges and bottom insets. Pill-shaped (`height: 64px`, border-radius: `32px`).
- **Surfaces:** `#131A26` at 95% opacity with an ultra-thin border (`1px solid #222E42`).
- **5 Global Destinations:**
  1. **Home:** System readiness, telemetry, active quick-prompt.
  2. **Plans:** Deconstructed LLM step-by-step execution chains.
  3. **History:** Immutable local run logs and audit trails.
  4. **Automations:** Background daemons, triggers, and scheduled jobs.
  5. **Permissions:** Micro-sandboxing, local hardware access guards, and data enclave statuses.
- **Item States:** Inactive destinations display `#64748B` iconography. The active destination displays a `#38BDF8` icon surrounded by a subtle, soft-glowing container pill (`rgba(56, 189, 248, 0.12)`).