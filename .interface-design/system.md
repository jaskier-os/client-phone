# Phone Client Design System

Gruvbox Material -- Material You shape language dressed in Gruvbox Dark warmth. Generous radii (16-24dp cards, asymmetric bubble tails), pill inputs, and circular action buttons bring the rounded, tactile feel of Material You. The palette stays Gruvbox Dark for warmth without brightness. Flat surfaces earn depth through color tiers alone, except chat contexts where subtle strokes define bubble edges. Monospace type in conversational contexts signals machine output.

Parent theme: `Theme.Material3.Dark.NoActionBar`

---

## Color

Four background tiers create depth without elevation. Accents carry meaning, not decoration.

### Backgrounds

| Token | Hex | Tier | Where |
|-------|-----|------|-------|
| `gbx_bg0_hard` | `#1D2021` | 0 -- chrome | Status bar, navigation bar, top bars, input bars |
| `gbx_bg` | `#282828` | 1 -- canvas | Window background, page background |
| `gbx_bg1` | `#3C3836` | 2 -- surface | Cards, input pills, assistant bubbles, dialog buttons, bottom sheet content |
| `gbx_bg2` | `#504945` | 3 -- emphasis | Selected items, dividers, outlines, image placeholders, ripple overlay on cards |

| `gbx_overlay` | `#CC1D2021` | -- overlay | Translucent panels over video/media content (bg0_hard at 80% opacity) |

Each tier is one step lighter. Surfaces sit on canvas; chrome sits beneath everything. No element should skip a tier (don't place bg2 directly on bg0_hard).

### Text

| Token | Hex | When |
|-------|-----|------|
| `gbx_fg` | `#EBDBB2` | Body text, primary content -- the default |
| `gbx_fg0` | `#FBF1C7` | Reserved. Not currently used distinctly from fg |
| `gbx_gray` | `#A89984` | Secondary text: timestamps, metadata, hints, empty states, loading text |

Two-tier text hierarchy only. If it's primary, use fg. If it's subordinate, use gray. No in-between.

### Semantic Accents

| Token | Hex | Meaning |
|-------|-----|---------|
| `gbx_orange` | `#FE8019` | Identity. Titles, headers, navigation tint, tab indicator, loading spinners. The brand color |
| `gbx_green` | `#98971A` | User / affirmative. User bubble tint, FAB, send button, alive indicators |
| `gbx_aqua` | `#689D6A` | System / informational. Tool messages, session borders, links |
| `gbx_red` | `#CC241D` | Destructive. Delete actions, error states |
| `gbx_yellow` | `#D79921` | Secondary accent (Material3 `colorSecondary`). Rarely used directly |
| `gbx_blue` | `#458588` | Categorical. Connection type badges ("BLE", "WiFi") |
| `gbx_purple` | `#B16286` | Special accent. Minimal usage |

### Derived Chat Colors

| Hex | Usage |
|-----|-------|
| `#2698971A` | User bubble fill (green at 15% opacity) |
| `#4D98971A` | User bubble stroke (green at 30% opacity) |
| `#0Fffffff` | Assistant bubble stroke (white at 6% opacity) |
| `#1A689D6A` | Tool bubble fill (aqua at 10% opacity) |

### Kotlin-Only Constants

| Hex | Usage |
|-----|-------|
| `#FB4934` | Bright red -- error text in snackbars/dialogs (higher contrast than `gbx_red`) |
| `#00AA00` | Permission granted indicator |
| `#AA0000` | Permission denied indicator |

### Material3 Mapping

| Role | Token |
|------|-------|
| `colorPrimary` / `colorOnPrimaryContainer` | `gbx_orange` |
| `colorOnPrimary` / `colorOnSecondary` | `gbx_bg` |
| `colorPrimaryContainer` / `colorOutline` | `gbx_bg2` |
| `colorSecondary` | `gbx_yellow` |
| `colorSurface` / `windowBackground` | `gbx_bg` |
| `colorOnSurface` | `gbx_fg` |
| `colorSurfaceVariant` | `gbx_bg1` |
| `colorOnSurfaceVariant` | `gbx_gray` |
| `statusBarColor` / `navigationBarColor` | `gbx_bg0_hard` |

---

## Depth

Zero elevation as default. No `cardElevation`, no `android:elevation`, no shadows on standard surfaces. Hierarchy comes from the four background tiers above.

**Exceptions:**
- Chat bubbles use 1dp strokes for subtle edge definition against the canvas
- Scroll-to-bottom button uses 4dp elevation (floating over scrollable content)
- Cards use ripple overlays (bg2 color) instead of elevation for touch feedback

---

## Spacing

Base unit: **4dp**. Core scale: **4 / 8 / 12 / 16 / 24 / 32**.

| Value | Intent | Typical Use |
|-------|--------|-------------|
| 4dp | Tight coupling | Related elements within a group: subtitle below title, tail corner radius on bubbles |
| 8dp | Sibling rhythm | Gap between stacked items, RecyclerView padding, chat bubble content padding (vertical), alive indicator size |
| 12dp | Field breathing room | Chat bubble content padding (horizontal), card margins (horizontal), top bar padding |
| 16dp | Container boundary | Card content padding, root layout padding, FAB margin, chat container horizontal padding |
| 24dp | Section break | Gap before action buttons, between major sections, dialog root padding, pill input radius |
| 32dp | Wide container padding | Programmatic horizontal padding in config screens |

**Derived values** (not part of the core scale, but consistently used):
- **6dp** -- Card vertical stack gap (topMargin + bottomMargin in chat list)
- **48dp** -- title paddingStart offset to clear a 40dp back button + 8dp visual gap
- **52dp** -- top bar height (chat detail)
- **72dp** -- RecyclerView bottom padding when a FAB is present (56dp FAB + 16dp margin)
- **80dp** -- bottom margin for stacked mini FAB above a regular FAB

---

## Typography

### Scale

| Size | Name | Weight | Color | Used For |
|------|------|--------|-------|----------|
| 10sp | Nano | normal | gray | Timestamps inside bubbles (currently hidden, reserved) |
| 11sp | Micro | normal | gray | MAC addresses, filenames, metadata messages |
| 12sp | Caption | normal | gray or aqua | Timestamps, durations, system messages, tool messages, chat list subtitles |
| 13sp | Small body | normal | varies | Inline button labels, loading text, bottom sheet fields |
| 14sp | Body | normal | fg | Chat messages (user + assistant), card titles, empty states |
| 16sp | Title | **bold** | orange or fg | Top bar titles (sans-serif, fg), card headers, section names |
| 18sp | Page title | **bold** | orange | Top bar screen titles (non-chat), back button character |

### Font Decision Rule

Use **monospace** when content represents machine output or raw data: chat messages (all roles), chat list item titles and subtitles, loading indicators, empty states, selection count.

Use **default sans-serif** for everything else: top bar titles (chat detail), form labels, card content, button labels, metadata, navigation.

When in doubt: if the user would expect to see it in a terminal, monospace. If they'd expect it in a settings panel, sans-serif.

### Weight Rule

Bold signals structural hierarchy (titles, headers, section names). Everything else is normal weight. There is no medium, no semibold, no light.

---

## Components

### Chat Bubbles

Asymmetric corner radii create a "tail" on the origin side. All bubbles are programmatically built ViewHolders (no item layout XML).

**Structure:** FrameLayout container (match_parent, 16dp horizontal padding) > LinearLayout outerWrap > LinearLayout bubbleWrap (with drawable bg, 12dp/8dp padding) > TextView

**User bubble** (`bg_bubble_user`):
- Fill: green at 15% (`#2698971A`)
- Stroke: 1dp green at 30% (`#4D98971A`)
- Radii: 20/20/20/4 (tail bottom-right)
- Gravity: END
- Max width: 80% of screen minus 24dp bubble padding

**Assistant bubble** (`bg_bubble_assistant`):
- Fill: bg1 (`#FF3C3836`)
- Stroke: 1dp white at 6% (`#0Fffffff`)
- Radii: 4/20/20/20 (tail top-left)
- Gravity: START
- Max width: 85% of screen minus 24dp bubble padding

**Tool bubble** (`bg_bubble_tool`):
- Fill: aqua at 10% (`#1A689D6A`)
- Radius: 8dp uniform
- Text: 12sp aqua monospace
- Gravity: CENTER_HORIZONTAL
- Max width: unconstrained

**System / metadata messages:** No background, centered, gray text, 12sp / 11sp.

**Spacing between messages:** 4dp same role, 12dp role switch.

**View recycling:** Always call `holder.container.clearAnimation()` in `onViewRecycled`. Always reset `maxWidth` to `Int.MAX_VALUE` for non-user/assistant roles.

**Safe list removal:** When removing messages by criteria (e.g., loading indicators), iterate in reverse to avoid index corruption with `notifyItemRemoved`.

### Chat List Cards

Programmatic card items for conversation and session entries. Use `bg_chat_card` (ripple with bg2 color on bg1 shape, 16dp radius).

```
Container: LinearLayout (vertical)
  Margins: 12dp horizontal, 6dp vertical
  Padding: 16dp all sides
  Background: bg_chat_card
  Children:
    TitleRow: LinearLayout (horizontal, center_vertical)
      AliveIndicator: 8dp green oval, GONE by default
      TitleView: monospace 14sp fg, weight=1, max 2 lines
    SubtitleView: monospace 12sp gray, 2dp topMargin
```

**Session variant:** 4dp aqua left border bar, pulsing alive indicator, aqua title text, long-click only (no tap).

### Card (Standard)

For non-chat contexts (devices, security reports, phone numbers). Every card is identical:

```xml
<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="8dp"
    app:cardBackgroundColor="@color/gbx_bg1"
    app:cardCornerRadius="12dp"
    app:cardElevation="0dp"
    app:strokeWidth="0dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <!-- Title: 16sp, orange, bold -->
        <!-- Body: 14sp, fg, normal -->
        <!-- Metadata: 12sp, gray, normal -->
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

Non-negotiable: bg1 background, 12dp radius, 0dp elevation, 0dp stroke, 16dp content padding, 8dp stack gap. No card deviates from this.

### Top Bar

**Standard (non-chat screens):**

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:padding="12dp"
    android:background="@color/gbx_bg0_hard">

    <ImageButton
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:src="@android:drawable/ic_menu_revert"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:scaleType="centerInside"
        app:tint="@color/gbx_orange" />

    <TextView
        android:layout_marginStart="12dp"
        android:textColor="@color/gbx_orange"
        android:textSize="18sp"
        android:textStyle="bold" />
</LinearLayout>
```

Background: bg0_hard. Back button: 40x40dp, orange-tinted, borderless ripple. Title: 12dp gap from button, 18sp orange bold. Effective height: ~48dp.

**Chat detail variant:** FrameLayout, fixed 52dp height, bg0_hard background, 1dp bg2 bottom border. ImageView back arrow (`ic_back`, 48x match_parent, 12dp padding, borderless ripple). Title: sans-serif bold 16sp fg (not orange), 56dp paddingStart, maxLines=1, ellipsize end.

**Selection mode bar:** Same 52dp height, bg2 background. Count text on left (monospace 14sp fg), action buttons on right ("Copy" in green, "X" in fg).

**Tab bar:**

```xml
<com.google.android.material.tabs.TabLayout
    android:background="@color/gbx_bg0_hard"
    app:tabIndicatorColor="@color/gbx_orange"
    app:tabSelectedTextColor="@color/gbx_orange"
    app:tabTextColor="@color/gbx_gray"
    app:tabMode="fixed"
    app:tabGravity="fill" />
```

### Input Bar (Chat)

```xml
<LinearLayout
    android:background="@color/gbx_bg0_hard"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:padding="8dp">

    <!-- Attach: 40dp ImageView, ic_attach (paperclip), borderless ripple -->
    <!-- Input: bg_input_pill (bg1, 24dp radius), monospace 14sp, 16dp H padding, 10dp V padding, maxLines=4 -->
    <!-- Send: bg_send_button (green oval, 40dp), ic_send, visibility=GONE initially -->
</LinearLayout>
```

Send button appears with overshoot scale animation when text is entered, disappears with accelerate animation + GONE when cleared. Always cancel previous animation before starting new one via `ViewPropertyAnimator`.

### Scroll-to-Bottom Button

```xml
<ImageView
    android:layout_width="36dp"
    android:layout_height="36dp"
    android:layout_gravity="bottom|center_horizontal"
    android:layout_marginBottom="8dp"
    android:src="@drawable/ic_arrow_downward"
    android:padding="8dp"
    android:background="@drawable/bg_scroll_to_bottom"
    android:elevation="4dp"
    android:visibility="gone" />
```

Background: bg1 oval with 1dp bg2 stroke. Shows/hides with 200ms alpha fade. Appears when user scrolls up from bottom (last visible < total - 2), hides when at bottom.

### Buttons

**Primary (filled):** Full width, Material3 default filled style (orange background, bg text). 16sp text. One per screen, for the main action.

**Tonal (secondary):** `Widget.Material3.Button.TonalButton`. Two layouts:
- *Paired:* two buttons side-by-side, each `layout_weight="1"`, 8dp gap
- *Standalone:* full width for tertiary actions

**Inline tonal:** `wrap_content` width, fixed 40dp height, 13sp text.

**Programmatic (dialogs/snackbars):** Background bg1, text fg. Destructive variant: text `#FB4934`.

### FAB

**Standard:** Green background (`gbx_green`), dark icon tint (`gbx_bg`), 16dp margin, `bottom|end` gravity.

**Mini:** Orange background (`gbx_orange`), dark icon tint, `fabSize="mini"`, `bottom|end` gravity.

**Stacked FABs:** Mini FAB sits above standard with `marginBottom="80dp"` (64dp FAB height + 16dp gap). Standard FAB keeps 16dp margin. Both `bottom|end`.

RecyclerViews beneath a FAB get 72dp bottom padding.

### Form Fields

```xml
<com.google.android.material.textfield.TextInputLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="12dp"
    style="@style/Widget.Material3.TextInputLayout.OutlinedBox">

    <com.google.android.material.textfield.TextInputEditText
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="Hint text"
        android:singleLine="true" />
</com.google.android.material.textfield.TextInputLayout>
```

Style: `OutlinedBox`. Gap between fields: 12dp. Last field before an action button: 24dp.

**Programmatic fields (bottom sheets):** bg1 background, fg text, gray hints, 13sp, padding 24/16/24/16.

### List Items

**Card items** (devices, security reports, phone numbers): Standard card pattern. Internal anatomy:
- Title row: 16sp orange bold, optional right-aligned metadata in 12sp gray
- Detail rows: 4dp marginTop, 13-14sp fg, optional badges
- Footer: 11sp monospace gray (MAC addresses, technical identifiers)

**Flat items** (call entries, catalogue audio): No card wrapper.
- Call entries: 8dp vertical / 16dp horizontal padding, 10dp indicator dot, 13sp body, 12sp metadata
- Catalogue audio: 8dp all-sides padding, 32dp icon, 13sp filename, 12sp size

### Catalogue Headers

Collapsible section dividers: 12dp paddingTop, 4dp paddingBottom/Start/End. Chevron 16x16dp with 4dp marginEnd. Label: 14sp orange bold. 1dp bg2 divider below with 4dp marginTop.

### Catalogue Media Grid

4dp item padding. Thumbnail: full width, 100dp height, centerCrop, bg2 placeholder. Play overlay: 28x28dp centered, 0.8 alpha. Filename: 11sp fg, single line, ellipsize end, 2dp marginTop.

### Empty State

Icon + text, centered in parent, `gone` by default:

```xml
<LinearLayout
    android:layout_gravity="center"
    android:orientation="vertical"
    android:gravity="center_horizontal"
    android:visibility="gone">

    <ImageView
        android:layout_width="64dp"
        android:layout_height="64dp"
        android:src="@drawable/ic_contextual_icon"
        android:alpha="0.3"
        android:scaleType="centerInside" />

    <TextView
        android:layout_marginTop="12dp"
        android:text="No items found"
        android:textColor="@color/gbx_gray"
        android:textSize="14sp"
        android:fontFamily="monospace" />
</LinearLayout>
```

Icon at 0.3 alpha provides visual anchor without competing with content. Text is 14sp gray monospace.

**Simple variant** (no icon): Single centered TextView, 14sp gray. Used for screens where an icon adds no meaning.

### Loading State

Centered vertically. 36x36dp orange-tinted spinner (`indeterminateTint="@color/gbx_orange"`). 13sp monospace gray label 12dp below. Initially hidden, toggled alongside empty state.

**Inline chat loading:** 3-dot animation (".","..","...") cycling at 500ms, rendered as a SYSTEM role message with `requestId="loading"`. Replaced Unicode spinner characters.

### Dialogs

**Shape:** 16dp rounded corners (all four). Applied via `materialAlertDialogTheme` in `Theme.Listener` -- all `MaterialAlertDialogBuilder` dialogs inherit this automatically. Background: bg1 (`colorSurface` override in theme overlay).

24dp root padding. Info rows: 14sp, 8dp marginBottom. Section headers: 14sp bold. 16dp marginBottom before dynamically generated content.

Programmatic dialogs that override `window.backgroundDrawable` must use `bg_dialog_rounded` (16dp radius, bg fill) instead of a flat color.

### Bottom Sheets

**Shape:** 24dp top corners, 0dp bottom corners. Applied via `bottomSheetDialogTheme` in `Theme.Listener` -- all `BottomSheetDialogFragment` subclasses inherit this automatically. Background: bg (`backgroundTint` on `Widget.App.BottomSheet`).

Content layouts inside bottom sheets must NOT set their own `android:background` or `setBackgroundColor` on the root view -- doing so paints over the rounded container corners. The theme handles the background color.

Container: bg background, padding 0/24/0/32. Horizontal content inset: 48dp paddingStart/End.

| Region | Size | Color | Padding (H/V) |
|--------|------|-------|----------------|
| Title | 16sp | fg | 48/16 top, 48/24 bottom |
| Error | 13sp | bright red (#FB4934) | 48/8 top, 48/24 bottom |
| Loading | spinner | gray | 0/24 |
| Info label | 14sp | aqua | 48/20 |
| Info value | 11sp | gray | 48/20 |
| Action label | 13sp | fg | 24/16 (button padding) |
| Send button | 14sp | green | 24/16 (button padding) |
| Input field | 13sp | fg on bg1, hint gray | 24/16 |

---

## Animations

### Message Entrance (`slide_up_fade_in`)

Applied to new chat messages as they appear. Only forward -- never re-animates on scroll-back (track `lastAnimatedPosition`).

```xml
<set android:interpolator="@android:anim/decelerate_interpolator" android:duration="250">
    <translate android:fromYDelta="40dp" android:toYDelta="0" />
    <alpha android:fromAlpha="0.0" android:toAlpha="1.0" />
</set>
```

Clear animation in `onViewRecycled` to prevent ghost animations on recycled views.

### Send Button Show/Hide

`ViewPropertyAnimator` (never dual ObjectAnimator):
- **Show:** scaleX/Y 0 -> 1, 200ms, `OvershootInterpolator`, set VISIBLE before animating
- **Hide:** scaleX/Y 1 -> 0, 150ms, `AccelerateInterpolator`, set GONE in `withEndAction`
- Always `.cancel()` previous animation before starting new one

### Scroll-to-Bottom Fade

200ms alpha animation. Fade in: set VISIBLE, alpha 0 -> 1. Fade out: alpha -> 0, set GONE in `withEndAction`.

### Pulsing Alive Indicator

`ObjectAnimator` on alpha, 1.0 -> 0.4, 750ms, `REVERSE` + `INFINITE`. Cancel in `onViewRecycled`.

### Loading Dots

Handler-based: cycle ".","..","..." at 500ms intervals. Uses SYSTEM message with `requestId="loading"`, removed when real content arrives.

---

## Drawable Registry

| File | Shape | Fill | Stroke | Radius | Used By |
|------|-------|------|--------|--------|---------|
| `bg_bubble_user` | rect | `#2698971A` | 1dp `#4D98971A` | 20/20/20/4 | User chat messages |
| `bg_bubble_assistant` | rect | bg1 | 1dp `#0Fffffff` | 4/20/20/20 | Assistant chat messages |
| `bg_bubble_tool` | rect | `#1A689D6A` | none | 8dp | Tool status messages |
| `bg_input_pill` | rect | bg1 | none | 24dp | Chat text input |
| `bg_send_button` | oval | green | none | -- | Send message button |
| `bg_chat_card` | ripple+rect | bg1 (ripple: bg2) | none | 16dp | Chat list items |
| `bg_scroll_to_bottom` | oval | bg1 | 1dp bg2 | -- | Scroll-to-bottom button |
| `bg_dialog_rounded` | rect | bg | none | 16dp | TodoPrimaryFragment manual window bg |
| `bg_avatar_circle` | oval | bg2 | none | -- | Unused (removed) |
| `bg_avatar_ring` | oval | none | 2dp green | -- | Unused (removed) |
