// Adds a "Phone" tab to the harness's own Settings dialog, in both on-device and full-engine mode.
// The tab is a copy of an existing tab button, so it matches the dialog's look in every theme;
// tapping it navigates to dsh-app://settings, which MainActivity opens as the native App settings screen.
(() => {
  if (window.__dshPhoneTab) return
  window.__dshPhoneTab = true
  const ICON = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="6" y="2" width="12" height="20" rx="2.5"/><path d="M11 18h2"/></svg>'
  const add = () => {
    const list = document.querySelector('[data-shortcut-modal="settings"] nav > div')
    if (list === null || list.querySelector('[data-dsh-phone-tab]') !== null) return
    const template = [...list.querySelectorAll('button')].find((button) => !button.hasAttribute('aria-current'))
    if (template === undefined) return
    const tab = template.cloneNode(true)
    tab.dataset.dshPhoneTab = ''
    tab.removeAttribute('data-modal-autofocus')
    const spans = tab.querySelectorAll('span')
    if (spans[0] !== undefined) spans[0].innerHTML = ICON
    if (spans[1] !== undefined) spans[1].textContent = 'Phone'
    tab.setAttribute('aria-label', 'Phone: app settings')
    tab.addEventListener('click', () => { location.href = 'dsh-app://settings' })
    list.append(tab)
  }
  new MutationObserver(add).observe(document.documentElement, { childList: true, subtree: true })
  add()
})()
