# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-08-11
- Primary product surfaces: library/home, document reader, search, bookmarks, document info, reader settings sheets
- Evidence reviewed: `core/designsystem`, every production composable in `core/ui` and `feature/*/impl`, `TeddReaderApp`, `ReaderNavHost`, Android/iOS platform adapters, existing Compose previews

## Brand
- Personality: calm editorial, tactile without imitation, focused, dependable
- Trust signals: strong typographic hierarchy, generous whitespace, predictable controls, direct local-file language, stable reader chrome
- Avoid: generic dashboard styling, indigo-heavy “template app” color, card/pill soup, gradients, oversized empty decoration, duplicate actions, unexplained symbols

## Product goals
- Goals: import a local or selected Google Drive document quickly; resume recent reading; keep reading controls discoverable without obstructing content
- Non-goals: social/library-store features, ornamental dashboards, configuration-heavy home screens
- Success signals: one obvious primary action per state, no horizontal clipping at 240 dp, reader gestures remain continuous while chrome hides

## Personas and jobs
- Primary personas: phone readers opening local TXT/PDF/EPUB files; tablet/foldable readers using longer sessions
- User jobs: import a local or Google Drive document, resume a document, navigate pages, search/bookmark, tune reading comfort
- Key contexts of use: one-handed narrow phones, dark rooms, portrait and landscape, intermittent short sessions

## Information architecture
- Primary navigation: Home -> Reader -> contextual Search / Bookmarks / Document info / Settings sheet
- Core routes/screens: Home, Reader, Search, Bookmarks, Document info
- Content hierarchy: current document/content first; primary action second; secondary metadata and configuration last

## Design principles
- One state, one primary action: never repeat the same CTA in header and empty state.
- Content owns width: interactive list rows use the full available safe width; internal padding stays inside their clickable container.
- Chrome yields to reading: a content drag/swipe hides visible reader chrome without cancelling the page gesture.
- Narrow first: layouts must work at 240 dp before adding wider-screen enhancements.
- Tradeoffs: prefer wrapping/stacking over shrinking touch targets; prefer one column on phones over dense grids.

## Visual language
- Color: warm eggshell canvas, near-black ink, muted clay accent, restrained sage support, and charcoal night surfaces. Accent color is for the current/primary action only. System bars continue the active surface.
- Typography: hierarchy comes from size, weight, line height, and whitespace—not many colors. Screen titles feel editorial; controls remain plain and compact; reading text stays user-controlled. Use platform fonts in this pass; add no font dependency.
- Spacing/layout rhythm: 4/8/12/16/24/32 dp rhythm; 20 dp phone margins; flat edge-to-edge collections with padded rows; bounded readable columns on large screens.
- Shape/radius/elevation: 4/8/12 dp radii for controls and meaningful groups. Prefer whitespace and 1 dp separators to elevation. No nested cards and no pill shape unless the control is genuinely compact/selectable.
- Motion: 120-200 ms, interruptible, and functional. Reader chrome fades/slides minimally; page motion continues uninterrupted.
- Imagery/iconography: one shared vector icon set with consistent 20/24 dp optical size. No text glyph icons. Content descriptions are mandatory.

## Components
- Existing components to reuse after restyling: `TeddListItem`, `TeddButton`, `TeddChip`, `TeddEmptyState`, `ReaderChromeSurface`, `ReaderTopControls`, `ReaderBottomControls`.
- New/changed components: editorial screen header, horizontal document card pager with portrait 3:4 covers, segmented sort/filter control, multi-select action strip, import choice dialog, compact reader bottom chrome, shared vector icon actions, drag observer for chrome dismissal.
- Variants and states: compact phone (<= 359 dp), regular phone, wide/tablet; loading, empty, error, populated, disabled.
- Token/component ownership: colors/type/spacing/shapes stay in `core/designsystem`; reusable interaction layout stays in `core/ui`.

## Accessibility
- Target standard: WCAG 2.2 AA where applicable and Android touch-target guidance.
- Keyboard/focus behavior: keep semantic roles and native focus order; do not hide chrome on keyboard navigation.
- Contrast/readability: system bar icons follow background luminance; reader text/background remain user-controlled.
- Screen-reader semantics: every icon action has a content description; rows expose one coherent click target.
- Reduced motion and sensory considerations: `PageAnimation.NONE` remains available; no essential state is motion-only.

## Responsive behavior
- Supported breakpoints/devices: compact 240-359 dp, regular 360-599 dp, medium 600-839 dp, expanded 840 dp and wider; portrait and landscape.
- Layout adaptations: wrap filter chips; remove redundant trailing metadata; stack reader progress/actions below 360 dp; cap readable content width; use adaptive grids only where cards remain independently understandable.
- Touch/hover differences: touch-first; pointer support uses the same actions without gesture-only requirements.

## Screen contracts
- App shell: the root theme owns the full-window background. Destinations own their safe content insets; no global system-bar padding is allowed.
- Home: a compact editorial masthead leads into Favorites and Recent pagers. Empty and populated states each expose exactly one Add documents CTA. Its dialog groups files and folders under the local device and shows Google Drive as a separate cloud source when available; each source is one coherent row rather than a stack of equal-emphasis buttons. Document cards use a horizontal pager with portrait 3:4 covers, keep title/format/meta inside the card, prefer real PDF/EPUB cover bytes before a shaped book-cover fallback, support clipped ripple, and allow long-press multi-select across both sections. Overflow actions still handle bookmark and single-item delete; destructive multi-delete requires confirmation and never removes original files.
- Search: one top navigation action, one query field, and one search action. IME Search submits. Blank, loading, no-result, error, and result states are mutually exclusive.
- Bookmarks: top navigation is never duplicated in the empty state. Tapping a bookmark opens it; edit/delete live in a separate secondary action surface. Delete requires confirmation.
- Document info: metadata appears once. Long values wrap or stack instead of squeezing labels. Reading statistics use human-readable duration and rate units.
- Reader: content always owns the full viewport. The page is the hero; controls use translucent/tonal edge chrome rather than floating card clusters. Visible chrome hides once a content drag crosses touch slop, while the selected page motion owns both slow and fast swipes without falling through to a default pager fling. `SCROLL` keeps the full document page stream in one stable lazy container instead of swapping previous/current/next windows. Center tap toggles chrome; page-edge taps and swipes navigate. TXT/EPUB two-finger pinch previews scale immediately and commits one font-size save/repage on release. PDF keeps session-local 1x-4x zoom with finite, viewport-clamped pan, resets pan on page change, and exposes a View sheet slider fallback. Auto-scroll is session-scoped, stops when Reader leaves, and uses an unlabeled 0.01-1.00 speed slider.
- Settings and reader sheets: options are grouped by user job, not implementation type. Draft slider values update previews immediately and commit once at gesture end. Unsupported future controls are omitted rather than shown disabled.
- Placeholder/unknown destination: use a standard top bar, concise explanation, and one recovery action.

## Component contracts
- Destination root modifiers/TeddScaffold/TeddTopBar: draw edge-to-edge backgrounds, consume insets exactly once, and keep app-bar backgrounds behind the status bar without a visual-only wrapper.
- TeddButton: support primary, secondary, text, and destructive emphasis; preserve a minimum 48 dp touch target; never use the same primary emphasis twice in one state.
- TeddIconButton: production calls require a non-empty content description and a minimum 48 dp touch target. Text glyphs are replaced by shared vector resources.
- TeddChip: selected state is visual and semantic; labels never encode selection with punctuation; ripple stays clipped to the visible pill while Material handles the minimum touch target.
- TeddListItem: modifier order is container size -> clickable/ripple -> internal content padding. Title/supporting text ellipsize predictably and the entire row is one coherent semantic target.
- Home document card: portrait 3:4 cards use a compact 180 dp width and start-align with the screen content edge on every window size, allowing unfolded foldables to show multiple covers instead of centering one oversized card; title, format, and compact metadata stay inside the card; real cover bytes render full-bleed first, then the shaped book-cover fallback; clipped ripple, selected semantics, and long-click semantics are required; system back clears multi-selection before normal navigation, and the compact overflow control retains a 48 dp minimum touch target while staying secondary to the full-card target.
- TeddInfoRow: use a label/value row only when both remain readable; stack at compact width or for long values.
- TeddTextField/TeddSearchField: expose keyboard type/action, error/supporting text, focus behavior, and IME-safe scrolling.
- TeddSwitchRow/TeddCheckboxRow/TeddRadioRow: the parent row owns the single selection semantic and the visual control is non-interactive; no duplicate focus target.
- TeddSliderRow: title/value remain visible at 240 dp, the slider spans the available width, and semantics announce the formatted value.
- TeddEmptyState/TeddErrorBanner/TeddLoadingIndicator: preserve the screen background, announce state changes, and provide at most one recovery action.
- TeddModalBottomSheet/TeddOptionGroup: account for navigation and IME insets, keep headings stable, and avoid nested elevated cards.
- ReaderChromeSurface/ReaderTopControls/ReaderBottomControls: use reader-theme colors, contrast-aware system icons, interruptible visibility motion, and compact/regular layouts without shrinking controls.
- ReaderPageSurface/ReaderProgressBar: cap line length, adapt margins without reducing readability, and expose progress semantics independent of visual labels.

## Adaptive matrix
| Surface | 240-359 dp | 360-599 dp | 600-839 dp | 840+ dp |
| --- | --- | --- | --- | --- |
| Standard screens | single column, stacked actions | single column | centered bounded column | bounded content; adaptive card grid only where appropriate |
| Home documents | compact horizontal pager with portrait 3:4 covers | compact horizontal pager with portrait 3:4 covers | start-aligned horizontal pager with multiple visible covers | start-aligned horizontal pager with multiple visible covers |
| Search/bookmarks | one result per row | one result per row | bounded single column | optional two-column bookmarks; search remains single column |
| Document stats | stacked label/value | two-column stat cards when space allows | two columns | up to four columns |
| Reader page | one page | one page | two pages only when each pane remains at least 280 dp | two bounded pages |
| Reader bottom chrome | progress row + trailing actions row | one row | one row | one centered row |
| Modal sheets | full safe width | full safe width | capped width | capped width |

## Interaction model
- Pointer input observers never consume events unless they own the gesture outcome. Reader multi-touch owns the Initial pass ahead of page navigation, keeps ownership until every pointer lifts, and PDF one-finger pan only takes over while zoom > 1.
- Gesture visibility state is captured at gesture start; callbacks use updated state without restarting pointer input.
- Destructive actions require explicit confirmation and are never adjacent to the primary action with equal emphasis. Home multi-select starts on long press, tap toggles selection across Favorites and Recent, and selections that disappear from filtered/visible content are removed immediately.
- Navigation and screen actions remain available to keyboard, accessibility services, and pointer input; gesture shortcuts are additive.
- Motion uses the design-system 120/200/300 ms durations, is interruptible, and has a no-animation reader option.
- State restoration covers navigation, query text, selected filters, sheet drafts, and current document location; transient loading/errors remain ViewModel-owned.

## Definition of done
- Every production composable is assigned to a documented component or screen contract in `docs/ui-ux/COMPOSABLE_UX_SPEC.md`.
- Every major screen has compact (280 dp), regular (360 dp), and expanded visual evidence; reader also has night and chrome-hidden evidence.
- No production layout clips or creates horizontal scrolling at 240 dp with font scale 1.3.
- All interactive targets are at least 48 dp and have roles, labels, selected/checked state, and deterministic focus order.
- System-bar backgrounds match the active app or reader surface in light, dark, sepia, custom, and high-contrast states.
- Android host tests, iOS simulator tests, Android debug assembly, and screenshot/semantics checks pass.
- A visual review confirms one primary action, no card/pill soup, consistent type hierarchy, and no text glyph icon on every production screen.

## Interaction states
- Loading: centered progress with stable screen background.
- Empty: one explanation and one primary CTA.
- Error: inline recoverable banner without displacing navigation.
- Success: content replaces transient status where possible.
- Disabled: retain labels and sufficient contrast; suppress interaction semantics.
- Offline/slow network: local imports stay available; Google Drive failures use the existing import error feedback without blocking local sources.

## Content voice
- Tone: short, direct, calm.
- Terminology: Open file, Recent documents, Search, Bookmarks, Document info.
- Microcopy rules: sentence case; no duplicate labels in one state; metadata uses concise units.

## Implementation constraints
- Framework/styling system: Kotlin Multiplatform, Compose Multiplatform, Navigation 3.
- Design-token constraints: reuse current design-system tokens; no new dependency for layout changes.
- Performance constraints: reader gestures must not allocate or restart animation pipelines on every pointer move.
- Compatibility constraints: Android min SDK 24, iOS shared UI, edge-to-edge system bars.
- Test/screenshot expectations: add narrow and regular previews for changed screens; pure gesture decisions receive focused tests; Android debug build must pass.

## Open questions
- [ ] Whether tablets should show Reader plus a supporting contents/bookmarks pane through Navigation 3 scenes after the phone redesign stabilizes / product owner / medium impact
- [ ] Whether custom reader colors ship after this redesign; the currently incomplete option stays hidden until a real picker and contrast validation exist / product owner / medium impact
