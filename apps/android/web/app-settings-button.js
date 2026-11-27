// Floating "App settings" button for every app page, in both on-device and full-engine mode.
// Tapping navigates to dsh-app://settings, which MainActivity opens as the native settings screen.
// Dragging moves it along the right edge; the position is kept in localStorage.
(() => {
  if (document.getElementById('dsh-app-settings')) return
  const KEY = 'dsh-app-settings-top'
  const button = document.createElement('button')
  button.id = 'dsh-app-settings'
  button.type = 'button'
  button.title = 'App settings'
  button.setAttribute('aria-label', 'App settings')
  button.innerHTML = '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>'
  let top = 0.4
  try { const saved = Number(localStorage.getItem(KEY)); if (saved > 0 && saved < 1) top = saved } catch (unavailable) { /* storage blocked: default position */ }
  Object.assign(button.style, {
    position: 'fixed', right: '0', top: `${top * 100}%`, zIndex: '2147483000', width: '34px', height: '40px',
    padding: '0 0 0 4px', border: 'none', borderRadius: '20px 0 0 20px', display: 'grid', placeItems: 'center',
    background: 'rgba(128,128,128,.28)', color: 'inherit', opacity: '.8', touchAction: 'none', cursor: 'pointer',
    backdropFilter: 'blur(8px)', webkitBackdropFilter: 'blur(8px)',
  })
  let startY = 0, startTop = 0, dragged = false, active = false
  button.addEventListener('pointerdown', (e) => { active = true; dragged = false; startY = e.clientY; startTop = button.getBoundingClientRect().top; button.setPointerCapture(e.pointerId) })
  button.addEventListener('pointermove', (e) => {
    if (!active) return
    const dy = e.clientY - startY
    if (!dragged && Math.abs(dy) < 6) return
    dragged = true
    const max = innerHeight - button.offsetHeight - 8
    button.style.top = `${Math.min(max, Math.max(8, startTop + dy))}px`
  })
  button.addEventListener('pointerup', () => {
    if (!active) return
    active = false
    if (!dragged) { location.href = 'dsh-app://settings'; return }
    try { localStorage.setItem(KEY, String(button.getBoundingClientRect().top / innerHeight)) } catch (unavailable) { /* position not kept */ }
  })
  button.addEventListener('pointercancel', () => { active = false })
  document.body.append(button)
})()
