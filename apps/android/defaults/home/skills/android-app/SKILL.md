---
name: android-app
description: Use when the user asks to change how the DeepSeek Harness Android app looks (theme, colors, glass or blur effects, a background or GIF while working, mobile layout tweaks) or wants to see an HTML page, report, chart, or SVG rendered on the phone.
---

# Android app: styling and artifacts

You are running inside the DeepSeek Harness Android app. The whole harness runs on the phone. Two app features are available to you.

## Show HTML and SVG artifacts

Write a self-contained `.html` or `.svg` file in the workspace and present it with the `present` tool. The user taps it to open a preview tab that renders the page, scripts included.

- Keep everything in one file: inline CSS, inline JavaScript, and data URLs for images. Remote `https://` images and scripts load too, but `fetch` to other sites fails unless they allow cross-origin requests.
- Design for a phone first: include `<meta name="viewport" content="width=device-width, initial-scale=1">`, use fluid widths, and keep tap targets at least 44px.

## Restyle the app

The app applies the CSS file `/dsh/home/android-ui/theme.css` on top of its own styles and reloads it within about two seconds of every change. Only CSS is accepted: no scripts. Files over 512 KB are ignored.

- The file is outside the workspace, so every write needs the user's approval: use the `write` or `edit` tool with `sandbox_permissions: "danger-full-access"` and a one-sentence justification naming the style change. If the user declines, do not retry another way.
- Read the existing file first and edit it rather than replacing it, so the user keeps earlier changes they liked.
- While you are working, the app sets the `data-dsh-working` attribute on the `<html>` element. Use `html[data-dsh-working]` selectors for working-only effects, such as an animated gradient or a GIF background.
- Background images and GIFs may use `https://` URLs or `data:` URLs.
- Respect both color schemes with `@media (prefers-color-scheme: dark)`, and respect `@media (prefers-reduced-motion: reduce)` by turning animations off.
- Keep the conversation readable: text contrast of at least 4.5:1, and never hide the message input, the send button, or the settings controls.

If a style breaks the app, the user can long-press the app icon and choose **Safe mode**, which starts the app without the custom style. Tell the user about this after your first style change.

Example: frosted glass panels and a moving gradient while working.

```css
:root { --glass: rgb(255 255 255 / 8%); }
body { background: radial-gradient(circle at 20% 0%, #3a2a6b, #101116 60%) fixed; }
[class*="panel"], [class*="Panel"], [role="dialog"] {
  background-color: var(--glass) !important;
  backdrop-filter: blur(18px) saturate(160%);
  -webkit-backdrop-filter: blur(18px) saturate(160%);
}
html[data-dsh-working] body {
  background: linear-gradient(120deg, #1b1f3a, #3a1f4d, #1f3a4d) fixed;
  background-size: 300% 300%;
  animation: dsh-working 6s ease infinite;
}
@keyframes dsh-working { 50% { background-position: 100% 50%; } }
@media (prefers-reduced-motion: reduce) { html[data-dsh-working] body { animation: none; } }
```
