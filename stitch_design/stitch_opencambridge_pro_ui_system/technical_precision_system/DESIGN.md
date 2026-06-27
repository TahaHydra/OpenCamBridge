---
name: Technical Precision System
colors:
  surface: '#121317'
  surface-dim: '#121317'
  surface-bright: '#38393d'
  surface-container-lowest: '#0d0e12'
  surface-container-low: '#1a1b1f'
  surface-container: '#1e1f23'
  surface-container-high: '#292a2e'
  surface-container-highest: '#343539'
  on-surface: '#e3e2e7'
  on-surface-variant: '#bac9cc'
  inverse-surface: '#e3e2e7'
  inverse-on-surface: '#2f3034'
  outline: '#849396'
  outline-variant: '#3b494c'
  surface-tint: '#00daf3'
  primary: '#c3f5ff'
  on-primary: '#00363d'
  primary-container: '#00e5ff'
  on-primary-container: '#00626e'
  inverse-primary: '#006875'
  secondary: '#c8c6c5'
  on-secondary: '#313030'
  secondary-container: '#4a4949'
  on-secondary-container: '#bab8b7'
  tertiary: '#b1ffb5'
  on-tertiary: '#003912'
  tertiary-container: '#49ed72'
  on-tertiary-container: '#006727'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#9cf0ff'
  primary-fixed-dim: '#00daf3'
  on-primary-fixed: '#001f24'
  on-primary-fixed-variant: '#004f58'
  secondary-fixed: '#e5e2e1'
  secondary-fixed-dim: '#c8c6c5'
  on-secondary-fixed: '#1c1b1b'
  on-secondary-fixed-variant: '#474646'
  tertiary-fixed: '#69ff87'
  tertiary-fixed-dim: '#3ce36a'
  on-tertiary-fixed: '#002108'
  on-tertiary-fixed-variant: '#00531e'
  background: '#121317'
  on-background: '#e3e2e7'
  surface-variant: '#343539'
typography:
  headline-lg:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
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
  code-md:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.05em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 4px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
  gutter: 12px
  margin: 20px
---

## Brand & Style

This design system is engineered for high-performance utility and technical clarity. It targets developers and streamers who require a high-information-density environment without cognitive overload. The aesthetic is **Technical Minimalism**, merging the systematic rigor of Material 3 with the sleek, high-contrast utility of modern developer tools like Linear and OBS Studio.

The brand personality is authoritative, precise, and transparent. It avoids decorative "fluff" in favor of functional geometry and an **OLED-first dark mode** strategy. The emotional response should be one of complete control and professional reliability. Visual interest is generated through crisp typography, vibrant status accents, and subtle structural borders rather than heavy gradients or shadows.

## Colors

The palette is optimized for low-light environments and OLED displays to reduce power consumption and maximize contrast.

- **Backgrounds:** The base layer is pure `#000000`. Secondary surfaces and containers use `#121212` to create a subtle sense of depth without breaking the "true black" aesthetic.
- **Accents:** The primary action color is **Electric Cyan** (`#00E5FF`). It is used sparingly for active states, focus rings, and primary call-to-actions to maintain its high-impact signal.
- **Semantic Status:** 
  - **Success/Live:** `#00C853` (Soft Green) indicates active streams or healthy connections.
  - **Error/Stop:** `#FF4081` (Vibrant Pink/Red) indicates critical failures or recording states.
- **Text & Borders:** High-contrast white (`#FFFFFF`) is reserved for primary headings. Sub-text and structural borders use varying shades of deep gray to maintain a clear hierarchy.

## Typography

The typography system utilizes a dual-font approach to separate narrative content from technical data.

- **Inter:** Chosen for its exceptional legibility and neutral, professional tone. It handles all UI labels, headings, and body copy.
- **JetBrains Mono:** Used for all technical telemetry, port numbers, URLs, and status chips. This provides a clear visual "mode switch" for the user when reading data versus reading navigation.

Headlines use tighter letter spacing and heavier weights to feel "engineered" and impactful. Labels and technical data use a slightly smaller scale but maintain high readability through increased line height and uppercase styling for micro-labels.

## Layout & Spacing

This design system uses a **4px baseline grid** to ensure precise alignment of compact technical elements.

- **Layout Model:** A flexible, density-adjustable grid. For the main dashboard, a **12-column fluid grid** is used with narrow 12px gutters to maximize screen real estate.
- **Density:** Information density is high. Padding inside components is kept tight (typically 8px to 12px) to allow for multi-view monitoring.
- **Breakpoints:**
  - **Mobile (<640px):** Single column stack, navigation moves to a bottom bar.
  - **Tablet (640px - 1024px):** 2-column layout for sidebars and main feed.
  - **Desktop (>1024px):** Multi-pane "Studio" layout with fixed-width sidebars (280px) and a fluid central viewport.

## Elevation & Depth

In an OLED-first system, depth is communicated through **tonal layering and hairline borders** rather than shadows.

- **Level 0 (Base):** Pure black `#000000`.
- **Level 1 (Cards/Panels):** Surface color `#121212` with a 1px solid border of `#2C2C2E`.
- **Level 2 (Popovers/Modals):** Surface color `#1C1C1E` with a slightly brighter border `#3A3A3C`.
- **Interactions:** Hover states are indicated by increasing the border brightness or applying a subtle Cyan tint to the border, rather than changing the background color significantly. This maintains the "dark" integrity of the UI.

## Shapes

The shape language balances the "technical" feel with modern software aesthetics. 

- **Primary Containers:** Large cards and main panels use **16px to 24px corner radii** (`rounded-lg` and `rounded-xl`). This softens the high-contrast interface and makes it feel like a premium consumer-pro product.
- **Small Components:** Buttons, input fields, and status chips use a smaller **8px radius** to maintain a compact, precise footprint.
- **Interactive Triggers:** Selectors and checkboxes use a consistent 4px radius for a sharper, more focused appearance.

## Components

- **Buttons:** Primary buttons are solid Electric Cyan with black text. Secondary buttons are ghost-style with a thin gray border that turns Cyan on hover.
- **Status Chips:** Small, monospaced text inside a subtle border. For "Live" status, include a 6px pulsing dot using the Success color.
- **Input Fields:** Pure black background with a 1px gray border. On focus, the border transitions to Electric Cyan. Labels are always positioned above the field in `label-sm` typography.
- **Cards:** Used to group camera feeds or settings. Cards must have a 1px border to separate them from the pure black background.
- **Data Meters:** For CPU/dropped frames, use thin horizontal bars with segmented increments to mimic hardware rack equipment.
- **Lists:** High-density rows with a 1px separator. Use monospaced fonts for any numerical values or IDs within the list.