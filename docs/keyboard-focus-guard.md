# Keyboard Focus Guard and Remembered Inventory List Filters

**Kind:** current. Two independent QoL options under Nurgling Settings → Quality of Life, both
default **off**:

- **Keyboard focus guard** (`NConfig.Key.keyboardFocusGuard`). Text and search fields only take
  typing after you deliberately activate them; otherwise keys go to the normal hotkeys. Closing a
  window returns the keyboard to the map. Toggle hotkey: **Toggle Keyboard Focus Guard**, unbound by
  default, in Options → Keybindings → Special and inline next to the checkbox.
- **Remember inventory list filters** (`NConfig.Key.rememberInvListFilters`). A newly opened
  inventory starts with the list-panel filters of the most recently active inventory in the same
  session. These values are held for the current session only and never written to disk.

Code: `src/nurgling/FocusGuard.java`, `src/nurgling/InventoryListFilters.java`, hooks in
`NGameUI` and `NInventory`. Tests: `test/nurgling/FocusGuardTest.java`,
`test/nurgling/InventoryListFiltersTest.java`.

## Why: how stock focus handling steals hotkeys

Line numbers are as of the commit that added this document.

1. **Keys go to the focused path first.** `UI.keydown` (`UI.java:938`) dispatches a
   `KeyDownEvent` from the root. It sends a `GlobKeyEvent` (the hotkeys: `globtype`, belts,
   `RootWidget`'s `gk` forwarding to the server) only if nothing on the focused path handled the
   key. `FocusedKeyEvent.propagation` (`Widget.java:1197`) follows `focused` through focus
   controllers and does no visibility check. A focused `TextEntry` consumes every unmodified
   printable key (`ReadLine.PCLine.key2`), Shift+letters included.
2. **Fields focus themselves.** `TextEntry`, `SSearchBox` and `Searchbox` call `setcanfocus(true)`.
   `Widget.add0` (`:249`) checks only the child's own `visible` flag, not its ancestors'. It then
   calls `newfocusable`, which on a focus controller with nothing focused focuses that child (`:618`).
   The first field added to a window therefore becomes that window's focus, even inside a hidden
   panel. A window remembers its focused child, and `gotfocus` reactivates it whenever the window
   regains focus.
3. **Closing a window hands focus to whatever is next in z-order.** `findfocus()` (`:639`) picks
   the topmost visible `autofocus` child, which is whichever window was raised last, not the map. It
   checks the `visible` field, so a window still animating its hide qualifies.

Inventory specifics:
- The container list panel's minimum-quality field (`NInventory.qualityFilterEntry`) was added into
  the hidden `rightPanel`. By path 2 it became each container window's focus as soon as the
  container opened. Letters vanished into a field you could not see, and digits silently set the
  filter.
- `applyPanelState` flips `visible` directly, which never drops focus.
- `GameUI`'s inventory `hide()` wiped the search but left `searchVisible`, the search panel and the
  title-bar toggle in whatever state they had.

## Keyboard Focus Guard: behavior

A **text input** is a `TextEntry` (any subclass), `SSearchBox`, `Searchbox` or `NTextArea`
(`FocusGuard.isTextInput`). With the guard on, the session's focused leaf (`FocusGuard.leaf`, which
follows `focused` the way key events do) is allowed to type only while it is **armed**. A field
becomes armed in three ways:

| How | Where |
|---|---|
| You click it. | `NGameUI.mousedown` calls `ev.propagate(this)` first, so the clicked field has already taken focus, then `FocusGuard.clicked(c)`. That arms the focused text input if the click point lies inside it, and disarms otherwise. |
| A user action opens it for typing. | `FocusGuard.requestTyping(entry)`: inventory search toggle (`NInventory.focusSearch`), timers quick-add and duration chips (`TimersPanel`), craft-flow name/override/quantity editors (`NCraftFlow`), milestone tag editor (`MilestoneWindowExtension`), person picker (`PersonPicker`). A request made while a click is being dispatched survives that click's `clicked()`. |
| It is the field of a text-entry dialog that has just taken focus for the first time. | Windows implementing `FocusGuard.TypingDialog`: `TextInputWindow`, `ActionConfigWindow`, `NAreaPresetNameDialog`, `NAreaPresetDescriptionDialog`, `NAreaMoveDialog`, `SaveCraftPresetDialog`, `NGroupLabelPopup`, `NEditFolderName`, `NEditAreaName`, `NBasePlannerWidget.NamePromptWindow`. Each dialog gets one grant. |

The arming ends on **any change of the focused leaf**. `FocusGuard.observe()` runs from
`NGameUI.setfocus`, `NGameUI.delfocusable`, every `NGameUI.tick`, and before each key and click.
Leaving a field and coming back, whether by clicking another window, by a window opening or by one
closing, therefore needs a new click. The guard also never arms a field that is not shown, meaning
every ancestor up to the GameUI is `visible()`. Turning the option off, or toggling it with the
hotkey, clears all state.

**Keys.** `NGameUI.keydown`:
1. The toggle hotkey is checked first, so it works while a field is eating keys.
2. Then, if the guard is on:
   - If `focused` is detached or not shown (a window mid-hide-animation), focus goes to the map.
   - If the leaf is an unarmed text input, the handler calls `ev.stop()` and returns `false`.
     `Event.stop()` (`Widget.java:837`) ends propagation with `phandled = false`, so the focused
     window and field never see the key. `Event.dispatch` returns false, and `UI.keydown` then sends
     the ordinary `GlobKeyEvent`. No hotkey is re-dispatched by hand.
   - **Escape and Tab** always take the normal focused path. They don't type, and windows use them
     to close (`Window.keydown`), clear a search (`HintTextEntry`) or skip focus cycling.
3. An armed field gets every key as before: Enter, Backspace/Delete, arrows, Home/End,
   Ctrl+A/C/V and so on.

Unarmed, Enter goes to the hotkeys too (e.g. chat's quick-line `kb_quick`). Ctrl/Alt combinations
were never consumed by `ReadLine` except editing shortcuts.

**Closing a window.** `NGameUI.delfocusable` runs when the focused window is removed or hidden
(`Widget.remove` → `setcanfocus(false)`, `Widget.hide`). With the guard on, if focus moved because
of it, focus goes to the map instead of the next window by z-order. Map-only hotkeys in
`NMapView.keydown` keep working.

**Not affected:**
- The chat quick line and the console command line use `ui.grabkeys` and the root widget
  respectively, so neither passes through `NGameUI.keydown`.
- Login screens have no `NGameUI`.
- Mouse grabs (dragging a selection in a field, `PersonPicker`) bypass `NGameUI.mousedown`. That is
  harmless: focus transitions still disarm.

**Ownership.** Each `NGameUI` owns its `FocusGuard` (`NGameUI.focusGuard`). The armed and pending
references point into that session's own widget tree, the granted-dialog set is weak, and
`NGameUI.dispose` resets it. No guard state is static. `FocusGuard.requestTyping` resolves the
guard through the widget's own `ui.gui`. The option itself is a normal persisted `NConfig` key,
shared by all sessions like every QoL setting.

## Remember Inventory List Filters: behavior

`InventoryListFilters` (`NGameUI.invFilters`, one per session) holds a template `State`:
minimum-quality text, grouping, display type, and the simplified-list sort (`compactNameAscending`,
`compactQuantityAscending`, `compactLastSortType`). The expanded list has no user-selectable sort.

- **Active inventory.** On every focus change and tick, `NGameUI.focusChanged` looks at the focused
  top-level window. If that window holds an `NInventory` with a list panel that is not already the
  active one, `activateListFilters()` makes it active and copies its current state into the
  template.
- **Changes.** The quality field, the grouping and display dropboxes, and the sort arrows call
  `reportListFiltersChanged()`. Only the active inventory updates the template. Clicking a control
  in another window focuses that window first, which makes it active.
- **New inventory.** `NInventory.setupRightPanel` (containers via `installListPanelIfContainer`,
  the main inventory via `installMainInv`) reads `template()` before building the widgets. The
  fields, the dropbox selections, the sort-arrow toggle states (`a = !ascending`) and the quality
  text are initialized from it. The list is rebuilt once, at the end, as before.
- **Example.** A (q≥1) → B (q≥10) → click A → open C: C starts at q≥1. Open inventories are never
  changed. Closing an inventory doesn't touch the template; the active reference is weak.
- **Session only.** The template lives in memory, so a client restart starts from defaults.
  Simultaneous sessions keep separate templates.
- **Display type.** It used to be one process-wide `static` that every open inventory read live.
  It is now per inventory, and a new inventory starts from the last display type chosen anywhere
  (`lastDisplayType`), which keeps the old "new windows follow your last choice" behavior when the
  option is off.

## Independent fixes (active regardless of either option)

- **Quality field.** It is the minimum quality (`q >= N`). It is built with `visible = false`, so
  it never auto-focuses, and `applyPanelState` shows it with the expanded panel.
- **Hiding the panel.** It drops the field's focus (`delfocusable`) when the panel hides it.
- **Typing in the quality field.**
  - Printable keys other than digits and `.` aren't typed, so letters fall through to the hotkeys.
  - Pasted text is sanitized (`InventoryListFilters.sanitizeQuality`).
  - Parsing never throws: `parseMinQuality` treats empty, `.`, NaN or garbage as no filter.
- **Inventory search.**
  - A wiped search (`wipeSearch`, also called when the inventory window hides) is a closed one:
    `searchVisible`, the panel and the `NHeaderToggle` are reset together.
  - `toggleSearch` keeps the toggle in step with the panel.
  - `wipeSearch` and `NSearchWidget`'s field resolve `itemsForSearch` through the inventory's own
    `ui.gui`, not `NUtils.getGameUI()`.

## Settings and keybinding

- `QoL.java`: two checkboxes in the "Quality of Life" section, an inline
  `NKeyBindButton(kb_focusGuard)`, `load`/`save`, and `syncFocusGuard()`. The hotkey handler
  (`NGameUI.toggleFocusGuard`) calls `syncFocusGuard()` on the open panel, so a later Save does not
  revert the toggle.
- `NGameUI.kb_focusGuard = KeyBinding.get("keyboard-focus-guard", KeyMatch.nil)`.
- Keybindings screen: one `addbtn` line in `OptWnd.BindingPanel`'s Special block.
- Strings: `qol.keyboard_focus_guard*`, `qol.focus_guard.{enabled,disabled}`,
  `qol.remember_inv_filters*` and `opt.keybind.keyboard_focus_guard` in both `messages*.properties`.

## Upstream-derived files touched

Only `src/haven/OptWnd.java` changed: one `addbtn` line, because the stock keybind list is built
there. Everything else hooks overridable methods (`setfocus`, `delfocusable`, `keydown`,
`mousedown`, `tick`) in `NGameUI`, or lives in fork-owned classes. See
`docs/fork-customization-ledger.md`, "Keyboard Focus Guard and remembered inventory list filters".

## Known limitations

- **Filter-style windows** (MenuSearch, Cookbook, Areas, Storage items, Todo, map marker list, craft
  atlas) are deliberately not auto-armed on open: you click the field to type. Adding a
  `requestTyping` call at a window's open path would change that for that window.
- **Clicks under overlays.** The click test is geometric: the click point must lie inside the
  focused field. A click on a non-window overlay drawn over a focused field that doesn't take focus
  itself would arm it.
- **Unarmed keys.** Key-up events still reach an unarmed field; nothing in-tree types on key-up.
  Backspace and Enter on an unarmed field go to the hotkey path; Backspace ends up as a server `gk`,
  as it does when the map has focus.

## Verification status

**Automated** (`ant test`):
- `FocusGuardTest` drives real `Widget.Event.dispatch` with a root, a GameUI stand-in wired like
  `NGameUI`, windows and fields. It covers:
  - auto-focused fields
  - guard-off legacy behavior
  - click to arm, and click elsewhere to disarm
  - returning to a field needing a new click
  - closing a window not arming the next one's field
  - Escape passthrough
  - hidden fields
  - a request surviving its opening click
  - `TypingDialog` getting one grant
  - toggle-off leaving no stale state
  - session isolation
- `InventoryListFiltersTest` covers:
  - A→B→A→C
  - only the active inventory updating the template
  - closing an inventory not erasing the template
  - disabled means no template
  - session isolation
  - sort fields carried
  - quality sanitize/parse
- `NConfigSyncKeysTest` counts the two new keys.

**Manual, in client (not yet run):**
1. Cookbook open, open a container, close it: hotkeys work and nothing is typed into Cookbook.
2. MenuSearch, Areas, Storage items, map marker list, Todo: hotkeys work until you click the field.
   Clicking another window and coming back needs a new click.
3. Area preset name, folder rename and TextInputWindow prompts accept typing at once.
4. Drag-select text in a field. Ctrl+A/C/V. Enter submits, Escape closes or clears.
5. Container with the expanded list: letters don't enter the quality field; after a click, digits
   do. Main inventory: toggle search on, type, close the inventory, reopen: search closed, toggle off.
6. Remember filters: A→B→A→C, panel closed/simplified/expanded, sort arrows show their state,
   restart resets, two sessions stay independent.
7. Hotkey bound in Keybindings and in QoL: works while a field has focus; the QoL checkbox follows
   it, and Save does not revert it.
8. Regression: chat entry and quick chat, Combat Reactor keys, belts, movement, session switching,
   Ctrl/Alt combos, logout.
