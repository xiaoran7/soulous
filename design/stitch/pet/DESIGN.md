---
name: Luminous Ethereal
colors:
  surface: '#fcf8f7'
  surface-dim: '#ddd9d8'
  surface-bright: '#fcf8f7'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f7f3f2'
  surface-container: '#f1edec'
  surface-container-high: '#ebe7e6'
  surface-container-highest: '#e5e2e1'
  on-surface: '#1c1b1b'
  on-surface-variant: '#454742'
  inverse-surface: '#313030'
  inverse-on-surface: '#f4f0ef'
  outline: '#767872'
  outline-variant: '#c6c7c0'
  surface-tint: '#5e5e5c'
  primary: '#5e5e5c'
  on-primary: '#ffffff'
  primary-container: '#fdfbf7'
  on-primary-container: '#747471'
  inverse-primary: '#c8c6c3'
  secondary: '#845400'
  on-secondary: '#ffffff'
  secondary-container: '#feb246'
  on-secondary-container: '#6f4600'
  tertiary: '#605e5f'
  on-tertiary: '#ffffff'
  tertiary-container: '#fffafb'
  on-tertiary-container: '#757374'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#e4e2de'
  primary-fixed-dim: '#c8c6c3'
  on-primary-fixed: '#1b1c1a'
  on-primary-fixed-variant: '#474744'
  secondary-fixed: '#ffddb6'
  secondary-fixed-dim: '#ffb95a'
  on-secondary-fixed: '#2a1800'
  on-secondary-fixed-variant: '#643f00'
  tertiary-fixed: '#e6e1e2'
  tertiary-fixed-dim: '#c9c5c6'
  on-tertiary-fixed: '#1c1b1c'
  on-tertiary-fixed-variant: '#484647'
  background: '#fcf8f7'
  on-background: '#1c1b1b'
  surface-variant: '#e5e2e1'
  amber-highlight: '#FFBF00'
  glass-fill: rgba(255, 255, 255, 0.4)
  glass-border: rgba(255, 255, 255, 0.6)
  ink-text: '#2D2926'
  mist-gray: '#E5E1DA'
typography:
  display-lg:
    fontFamily: Manrope
    fontSize: 48px
    fontWeight: '700'
    lineHeight: '1.1'
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Manrope
    fontSize: 32px
    fontWeight: '600'
    lineHeight: '1.2'
  headline-md:
    fontFamily: Manrope
    fontSize: 24px
    fontWeight: '600'
    lineHeight: '1.3'
  body-lg:
    fontFamily: Hanken Grotesk
    fontSize: 18px
    fontWeight: '400'
    lineHeight: '1.6'
  body-md:
    fontFamily: Hanken Grotesk
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.6'
  label-md:
    fontFamily: Hanken Grotesk
    fontSize: 14px
    fontWeight: '600'
    lineHeight: '1.2'
    letterSpacing: 0.05em
  caption:
    fontFamily: Hanken Grotesk
    fontSize: 12px
    fontWeight: '500'
    lineHeight: '1.4'
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  unit: 8px
  container-max: 1440px
  gutter: 24px
  margin-desktop: 64px
  card-gap: 32px
---

## Brand & Style

The design system for this study app centers on the concept of **Luminous Ethereal**, a design direction that prioritizes mental clarity, focus, and emotional tranquility. It is crafted for a target audience that seeks an immersive, gamified learning environment without the cognitive load of traditional, dense productivity tools.

The visual style is a refined interpretation of **Glassmorphism**, characterized by translucent surfaces that feel lightweight and "breathable." By utilizing floating elements and a scatter-style layout, the interface mimics a physical desk space where items are arranged organically rather than constrained by rigid, heavy sidebars. The emotional response is intended to be quiet and focused, turning the "grind" of studying into a serene, rewarding ritual.

## Colors

The palette is anchored in **warm, off-white tones** to reduce eye strain during long study sessions. Unlike clinical white interfaces, the primary background (`#FDFBF7`) evokes the texture of premium parchment or soft morning light.

**Amber Highlights** serve as the energetic pulse of the application. They are used sparingly for calls-to-action, progress indicators, and "Experience Points" (XP) visualizations, providing a sense of warmth and achievement. The secondary color acts as a bridge between the neutral background and the vibrant amber highlights.

Translucency is a functional color here. Elements utilize a "Glass Fill" with variable opacity and "Glass Border" to define edges without adding visual weight.

## Typography

The typography strategy balances modern precision with humanistic warmth. **Manrope** is used for headlines to provide a grounded, professional structure that feels contemporary. **Hanken Grotesk** is selected for body text and labels due to its exceptional legibility and sharp, technical edge, which complements the AI-driven nature of the app.

To maintain the "breathable" aesthetic, line heights are generous (1.6x for body text), and tracking is slightly adjusted for labels to ensure they stand out even when placed on translucent glass backgrounds.

## Layout & Spacing

This design system employs a **scatter-style fluid grid** that prioritizes depth over density. 

- **Navigation**: Eschews the traditional sidebar for a top-mounted "Scatter Menu." This menu is a floating glass bar with blurred background effects, allowing the content of the study room or dashboard to peek through.
- **Content Area**: A fixed-width container (1440px) centered on the screen, with massive margins (64px) to create an airy, focused frame.
- **Grids**: Components are arranged in a 12-column system, but elements are encouraged to use "offset" positions to mimic the organic arrangement of objects on a physical desk.
- **Density**: Extremely low. Every functional group must be separated by at least 32px to ensure the UI feels "quiet."

## Elevation & Depth

Depth is conveyed through **backlight and translucency** rather than traditional drop shadows.

1.  **Backdrop Blurs**: All floating cards must utilize `backdrop-filter: blur(20px)` to create a sense of being "suspended" above the background.
2.  **Luminous Glow**: Instead of dark shadows, active elements or "focused" cards project a soft, low-opacity amber glow (`rgba(255, 179, 71, 0.15)`) to simulate light passing through the glass.
3.  **Tonal Stacking**: Depth is hierarchical. The base layer is the soft-white background. The second layer consists of translucent cards. The third layer (modals/popovers) uses a higher opacity glass with a thin, bright white border (1.5px) to indicate it is "closer" to the user.

## Shapes

The shape language is defined by **organic, exaggerated curves**. Sharp corners are avoided to maintain the "quiet" and "gentle" brand persona.

- **Main Cards**: Use a consistent 24px radius.
- **Active States/Buttons**: Use pill-shaped (rounded-full) containers.
- **Interactive Elements**: Smaller components like input fields or nested chips use a 12px radius to maintain a family resemblance to the larger cards without appearing overly circular.

## Components

### Glass Cards
The primary container for all content. They should have a 1px white border at 60% opacity on the top and left, and a 1px "mist-gray" border on the bottom and right to simulate a subtle 3D glass edge.

### Floating Navigation
The top menu should be a single floating pill-shaped bar. Icons (Lucide-react) should be used with `label-md` text. The active state is indicated by a soft amber glow behind the icon, not a solid background color.

### Study Stopwatch
A large, centered circular progress ring using a gradient from `#FFB347` to `#FFBF00`. The background of the study room should use high-quality, blurred ambient imagery that can be customized by the user.

### Buttons
- **Primary**: Pill-shaped, solid Amber (`#FFBF00`) with white text and a soft amber glow on hover.
- **Ghost/Glass**: Pill-shaped, `glass-fill` with a `glass-border`. Becomes slightly more opaque on hover.

### Inputs & Chat
The AI Decompose Chat should feel like a "floating conversation." Message bubbles use varying levels of glass translucency—user messages are slightly warmer (tinted with amber), and AI messages are pure frost white.

### Pets & Economy
Experience bars and coin counters should be treated as "jewel" elements—high saturation, subtle gradients, and physical depth, contrasting against the flat, airy glass of the rest of the UI.