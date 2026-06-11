# Chat UI Rework: Gruvbox Material Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Rework all chat screens with Material 3 shape language -- rounded message bubbles, card-based chat list, pill input bar, and message arrival animations -- while keeping the Gruvbox Dark palette.

**Architecture:** All chat rendering is programmatic (Kotlin adapters build views in code, no item layout XMLs). The rework modifies adapter `onCreateViewHolder`/`onBindViewHolder` to produce bubble-shaped containers with GradientDrawable backgrounds. Layout XMLs change for top bar and input bar. New XML drawables provide reusable shapes. RecyclerView item animator handles entrance animations.

**Tech Stack:** Kotlin, Android View system, GradientDrawable, ObjectAnimator, RecyclerView.ItemAnimator, Material 3 components.

**Screens:** ChatsListFragment (list), ChatDetailActivity (history + input), ChatFragment (active voice chat).

**Existing drawables to reuse:** `ic_send.xml`, `ic_back.xml`, `ic_arrow_downward.xml`

---

### Task 1: Add Bubble Drawable Resources

**Files:**
- Create: `app/src/main/res/drawable/bg_bubble_user.xml`
- Create: `app/src/main/res/drawable/bg_bubble_assistant.xml`
- Create: `app/src/main/res/drawable/bg_bubble_tool.xml`
- Create: `app/src/main/res/drawable/bg_input_pill.xml`
- Create: `app/src/main/res/drawable/bg_send_button.xml`
- Create: `app/src/main/res/drawable/bg_chat_card.xml`
- Create: `app/src/main/res/drawable/bg_avatar_circle.xml`
- Create: `app/src/main/res/drawable/bg_avatar_ring.xml`
- Create: `app/src/main/res/drawable/bg_scroll_to_bottom.xml`
- Create: `app/src/main/res/anim/slide_up_fade_in.xml`

**Step 1: Create bubble drawables**

`bg_bubble_user.xml` -- green tint, tail bottom-right:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#2698971a" />
    <stroke android:width="1dp" android:color="#4D98971a" />
    <corners
        android:topLeftRadius="20dp"
        android:topRightRadius="20dp"
        android:bottomLeftRadius="20dp"
        android:bottomRightRadius="4dp" />
</shape>
```

`bg_bubble_assistant.xml` -- bg1 fill, tail top-left:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#FF3c3836" />
    <stroke android:width="1dp" android:color="#0Fffffff" />
    <corners
        android:topLeftRadius="4dp"
        android:topRightRadius="20dp"
        android:bottomLeftRadius="20dp"
        android:bottomRightRadius="20dp" />
</shape>
```

`bg_bubble_tool.xml` -- aqua pill:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#1A689d6a" />
    <corners android:radius="8dp" />
</shape>
```

`bg_input_pill.xml` -- pill-shaped input field:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#FF3c3836" />
    <corners android:radius="24dp" />
</shape>
```

`bg_send_button.xml` -- circular green button:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#FF98971a" />
    <size android:width="40dp" android:height="40dp" />
</shape>
```

`bg_chat_card.xml` -- rounded card for chat list:
```xml
<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="#FF504945">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#FF3c3836" />
            <corners android:radius="16dp" />
        </shape>
    </item>
</ripple>
```

`bg_avatar_circle.xml` -- avatar background:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#FF504945" />
</shape>
```

`bg_avatar_ring.xml` -- active chat ring around avatar:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <stroke android:width="2dp" android:color="#FF98971a" />
    <solid android:color="#00000000" />
</shape>
```

`bg_scroll_to_bottom.xml` -- scroll FAB background:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#FF3c3836" />
    <stroke android:width="1dp" android:color="#FF504945" />
</shape>
```

**Step 2: Create animation resource**

`app/src/main/res/anim/slide_up_fade_in.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="http://schemas.android.com/apk/res/android"
    android:interpolator="@android:anim/decelerate_interpolator"
    android:duration="250">
    <translate
        android:fromYDelta="40dp"
        android:toYDelta="0" />
    <alpha
        android:fromAlpha="0.0"
        android:toAlpha="1.0" />
</set>
```

**Step 3: Commit**

```bash
git add app/src/main/res/drawable/bg_bubble_user.xml \
  app/src/main/res/drawable/bg_bubble_assistant.xml \
  app/src/main/res/drawable/bg_bubble_tool.xml \
  app/src/main/res/drawable/bg_input_pill.xml \
  app/src/main/res/drawable/bg_send_button.xml \
  app/src/main/res/drawable/bg_chat_card.xml \
  app/src/main/res/drawable/bg_avatar_circle.xml \
  app/src/main/res/drawable/bg_avatar_ring.xml \
  app/src/main/res/drawable/bg_scroll_to_bottom.xml \
  app/src/main/res/anim/slide_up_fade_in.xml
git commit -m "feat: add bubble and card drawable resources for chat UI rework

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 2: Rework ChatAdapter (Active Chat Tab) with Bubbles

**Files:**
- Modify: `app/src/main/java/com/repository/listener/ui/ChatAdapter.kt`

**Step 1: Rewrite ChatAdapter with bubble rendering**

Replace the entire adapter. Key changes:
- `onCreateViewHolder`: Build a LinearLayout container holding a bubble (LinearLayout with drawable background) containing a TextView, plus a timestamp TextView below
- User bubbles: `bg_bubble_user` background, right-aligned, max 80% width
- Assistant bubbles: `bg_bubble_assistant` background, left-aligned, max 85% width
- Tool messages: `bg_bubble_tool` background, centered, wrap_content
- System/metadata: no bubble, centered, same as before
- Padding inside bubble: 12dp horizontal, 8dp vertical
- Margin between messages: 4dp same-role, 12dp role-switch (check previous message role in `onBindViewHolder`)

Full replacement code for `ChatAdapter.kt`:

```kotlin
package com.repository.listener.ui

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    companion object {
        private const val COLOR_FG = 0xFFebdbb2.toInt()
        private const val COLOR_GREEN = 0xFF98971a.toInt()
        private const val COLOR_AQUA = 0xFF689d6a.toInt()
        private const val COLOR_GRAY = 0xFFa89984.toInt()
    }

    private val messages = mutableListOf<ChatMessage>()
    private var lastAnimatedPosition = -1

    class MessageViewHolder(
        val container: FrameLayout,
        val bubbleWrap: LinearLayout,
        val textView: TextView,
        val timestampView: TextView
    ) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val ctx = parent.context
        val maxWidthUser = (parent.resources.displayMetrics.widthPixels * 0.80f).toInt()
        val maxWidthAssistant = (parent.resources.displayMetrics.widthPixels * 0.85f).toInt()

        val textView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(COLOR_FG)
        }

        val bubbleWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val timestampView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(COLOR_GRAY)
            visibility = View.GONE
        }

        val outerWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubbleWrap)
            addView(timestampView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(ctx, 4) })
        }

        val container = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(ctx, 16), 0, dpToPx(ctx, 16), 0)
            addView(outerWrap, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        return MessageViewHolder(container, bubbleWrap, textView, timestampView)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]
        val ctx = holder.itemView.context
        val outerWrap = holder.bubbleWrap.parent as LinearLayout
        val outerLp = outerWrap.layoutParams as FrameLayout.LayoutParams
        val maxWidthUser = (holder.itemView.resources.displayMetrics.widthPixels * 0.80f).toInt()
        val maxWidthAssistant = (holder.itemView.resources.displayMetrics.widthPixels * 0.85f).toInt()

        // Margin: 4dp same role, 12dp role switch
        val prevRole = if (position > 0) messages[position - 1].role else null
        val topMargin = if (prevRole != null && prevRole != msg.role) dpToPx(ctx, 12) else dpToPx(ctx, 4)
        (holder.container.layoutParams as? RecyclerView.LayoutParams)?.topMargin = topMargin

        holder.timestampView.visibility = View.GONE
        holder.bubbleWrap.maxWidth = Int.MAX_VALUE

        when (msg.role) {
            ChatMessage.Role.USER -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(COLOR_FG)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.bubbleWrap.setBackgroundResource(R.drawable.bg_bubble_user)
                holder.bubbleWrap.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 8), dpToPx(ctx, 12), dpToPx(ctx, 8))
                holder.bubbleWrap.maxWidth = maxWidthUser
                outerLp.gravity = Gravity.END
                holder.timestampView.gravity = Gravity.END
            }
            ChatMessage.Role.ASSISTANT -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(COLOR_FG)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.bubbleWrap.setBackgroundResource(R.drawable.bg_bubble_assistant)
                holder.bubbleWrap.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 8), dpToPx(ctx, 12), dpToPx(ctx, 8))
                holder.bubbleWrap.maxWidth = maxWidthAssistant
                outerLp.gravity = Gravity.START
                holder.timestampView.gravity = Gravity.START
            }
            ChatMessage.Role.TOOL -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(COLOR_AQUA)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.bubbleWrap.setBackgroundResource(R.drawable.bg_bubble_tool)
                holder.bubbleWrap.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 4), dpToPx(ctx, 12), dpToPx(ctx, 4))
                outerLp.gravity = Gravity.CENTER_HORIZONTAL
            }
            ChatMessage.Role.SYSTEM -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(COLOR_GRAY)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.bubbleWrap.background = null
                holder.bubbleWrap.setPadding(0, dpToPx(ctx, 4), 0, dpToPx(ctx, 4))
                outerLp.gravity = Gravity.CENTER_HORIZONTAL
            }
            ChatMessage.Role.METADATA -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(COLOR_GRAY)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.bubbleWrap.background = null
                holder.bubbleWrap.setPadding(0, dpToPx(ctx, 2), 0, dpToPx(ctx, 2))
                outerLp.gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        outerWrap.layoutParams = outerLp

        // Animate new items
        if (position > lastAnimatedPosition) {
            val anim = AnimationUtils.loadAnimation(ctx, R.anim.slide_up_fade_in)
            holder.container.startAnimation(anim)
            lastAnimatedPosition = position
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(text: String) {
        if (messages.isNotEmpty()) {
            messages.last().text = text
            notifyItemChanged(messages.size - 1)
        }
    }

    fun removeLoadingMessages() {
        val iter = messages.listIterator()
        while (iter.hasNext()) {
            val idx = iter.nextIndex()
            val msg = iter.next()
            if (msg.role == ChatMessage.Role.SYSTEM && msg.requestId == "loading") {
                iter.remove()
                notifyItemRemoved(idx)
            }
        }
    }

    fun getMessages(): List<ChatMessage> = messages.toList()

    fun clear() {
        val size = messages.size
        messages.clear()
        lastAnimatedPosition = -1
        notifyItemRangeRemoved(0, size)
    }

    private fun dpToPx(ctx: android.content.Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), ctx.resources.displayMetrics
        ).toInt()
    }
}
```

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/java/com/repository/listener/ui/ChatAdapter.kt
git commit -m "feat: rework ChatAdapter with bubble rendering and entrance animations

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 3: Rework ChatDetailAdapter with Bubbles + Selection

**Files:**
- Modify: `app/src/main/java/com/repository/listener/ui/ChatDetailAdapter.kt`

**Step 1: Rewrite ChatDetailAdapter**

Same bubble rendering as ChatAdapter but preserving the selection mode. Key differences from ChatAdapter:
- `onMessageClicked` and `onSelectionChanged` callbacks stay
- Selection highlight: overlay the bubble with a semi-transparent bg2 border (not background swap)
- `updateLastMessage`, `removeLoadingMessages`, `getMessages` methods stay
- Same `dpToPx` helper, same animation, same max-width constraints

Full replacement -- mirrors ChatAdapter structure but adds selection mode, click handlers, and the three streaming helper methods already present.

The code follows the exact same pattern as Task 2's ChatAdapter but with:
- Constructor takes `onMessageClicked` and `onSelectionChanged` callbacks
- `isSelectionMode` / `selectedPositions` fields
- In `onBindViewHolder`: selection highlight via container background color toggle
- Click/long-click handlers on container
- `clearSelection()`, `getSelectedMessages()`, `toggleSelection()` methods
- `submitMessages()` for bulk replace
- `updateLastMessage()`, `removeLoadingMessages()`, `getMessages()` for streaming

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/java/com/repository/listener/ui/ChatDetailAdapter.kt
git commit -m "feat: rework ChatDetailAdapter with bubbles and selection mode

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 4: Rework Chat Detail Layout (Top Bar + Input Bar)

**Files:**
- Modify: `app/src/main/res/layout/activity_chat_detail.xml`
- Modify: `app/src/main/java/com/repository/listener/ui/ChatDetailActivity.kt`

**Step 1: Update activity_chat_detail.xml**

Replace the layout with:
- Top bar: `gbx_bg0_hard` background, Material back icon (ImageView with `ic_back`, orange tint), 16sp bold sans-serif title, 1dp bottom border
- Message area: same RecyclerView, 16dp horizontal padding, no 10% calc
- Input bar: `gbx_bg0_hard` background, horizontal layout with:
  - Attach button: 40dp ImageView, `ic_tab_chat` or "+" text, gray tint
  - Pill input: EditText with `bg_input_pill` background, 24dp radius, 14sp monospace
  - Send button: 40dp ImageView, `ic_send`, white tint, `bg_send_button` background
- Scroll-to-bottom button: 36dp ImageButton, `bg_scroll_to_bottom`, `ic_arrow_downward`, positioned bottom-center of message area, GONE by default

**Step 2: Update ChatDetailActivity.kt**

- Remove the 10% padding post-layout (line ~103-106)
- Add scroll-to-bottom button logic:
  - Show when RecyclerView scrolled up (addOnScrollListener)
  - Hide when at bottom
  - Fade in/out animation (200ms)
  - On click: scrollToBottom()
- Add send button animation:
  - TextWatcher on input field
  - When text goes from empty to non-empty: scale animate send button 0->1 (200ms, overshoot)
  - When text goes from non-empty to empty: scale animate send button 1->0 (150ms, accelerate)
  - Initial state: send button scaleX=0, scaleY=0 (invisible but still in layout)

**Step 3: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/res/layout/activity_chat_detail.xml \
  app/src/main/java/com/repository/listener/ui/ChatDetailActivity.kt
git commit -m "feat: rework chat detail layout with pill input and scroll-to-bottom

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 5: Rework ChatsListAdapter with Cards + Avatars

**Files:**
- Modify: `app/src/main/java/com/repository/listener/ui/ChatsListAdapter.kt`

**Step 1: Rewrite ChatsListAdapter**

Replace `createChatHolder()` to build:
- Card container: `bg_chat_card` background (ripple + rounded), 12dp horizontal margin, 6dp vertical margin, 16dp padding
- Horizontal layout inside:
  - Left: 40dp avatar FrameLayout
    - Circle background (`bg_avatar_circle`): 40dp, contains centered TextView (first letter, 16sp green bold)
    - Ring overlay (`bg_avatar_ring`): 44dp, positioned -2dp offset, GONE by default (shown when active)
  - Right (12dp marginStart): vertical LinearLayout
    - Title: 14sp fg monospace, max 2 lines, ellipsize end
    - Subtitle: 12sp gray, device + time

Replace `createSessionHolder()`:
- Same card shape but with 4dp aqua left border (use a horizontal LinearLayout with a 4dp-wide aqua View + content)
- Title: "> dirname" aqua monospace
- Pulsing green dot: ObjectAnimator on alpha (0.4f to 1.0f, 750ms, reverse repeat)

In `onBindViewHolder`:
- Conversation: extract first character from `firstUserMessage` for avatar letter
- Set ring visibility: `if (!item.chat.closed) View.VISIBLE else View.GONE`
- Session: start/stop pulse animation on bind/recycle

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/java/com/repository/listener/ui/ChatsListAdapter.kt
git commit -m "feat: rework chat list with card layout, avatars, and active ring

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 6: Update ChatFragment with Padding + Dot Loading

**Files:**
- Modify: `app/src/main/res/layout/fragment_chat.xml`
- Modify: `app/src/main/java/com/repository/listener/ui/ChatFragment.kt`

**Step 1: Update fragment_chat.xml**

Add 16dp horizontal padding to the RecyclerView (replacing the 15% programmatic padding).

**Step 2: Update ChatFragment.kt**

- Remove the post-layout 15% padding calculation (lines ~177-180)
- Replace the 4-character Unicode spinner with a 3-dot pulse:
  - Cycle through ".", "..", "..." at 500ms intervals
  - Display as SYSTEM role message with `requestId = "loading"`
  - The dots appear inside an assistant-style bubble (handled by ChatAdapter automatically since SYSTEM role renders without bubble -- keep this simple)

**Step 3: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/res/layout/fragment_chat.xml \
  app/src/main/java/com/repository/listener/ui/ChatFragment.kt
git commit -m "feat: update ChatFragment with fixed padding and dot loading animation

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 7: Update Chat List Loading + Empty State

**Files:**
- Modify: `app/src/main/res/layout/fragment_chats_list.xml`

**Step 1: Update fragment_chats_list.xml**

- Loading: replace gray-tinted ProgressBar with orange-tinted (`gbx_orange`) Material circular indicator
- Empty state: add a 64dp chat bubble outline icon above the text (use `ic_tab_chat` at 64dp with 30% alpha gray tint), change text to "No conversations yet"

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/res/layout/fragment_chats_list.xml
git commit -m "feat: update chat list loading and empty state visuals

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 8: Final Build + Visual Verification

**Step 1: Clean build**

```bash
./gradlew clean assembleDebug
```
Expected: BUILD SUCCESSFUL

**Step 2: Install on device**

```bash
./gradlew installDebug
```

**Step 3: Manual verification checklist**
- [ ] Chat list shows rounded cards with avatars
- [ ] Active chats show green ring around avatar
- [ ] Remote sessions show aqua left border and pulsing dot
- [ ] Chat detail shows rounded bubbles (green-tint user, bg1 assistant)
- [ ] Input bar has pill shape with circular send button
- [ ] Send button animates in/out based on text content
- [ ] New messages slide up with fade animation
- [ ] Scroll-to-bottom button appears when scrolled up
- [ ] Tool messages show as aqua pills
- [ ] System/loading messages centered without bubble
- [ ] Active chat tab (ChatFragment) shows same bubble treatment
- [ ] Selection mode still works in chat detail (long press, copy, close)

**Step 4: Final commit if any fixes needed**
