import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
// Base and shared styles first, so each layout's own rules can override them.
import './index.css'
import './shared/shared.css'
import { App } from './app'

const root = document.getElementById('app')
if (!root) throw new Error('missing #app')

// iOS Safari pinch-zooms despite user-scalable=no; a remote page should never zoom.
document.addEventListener('gesturestart', (e) => e.preventDefault())

// When the phone keyboard opens, iOS shrinks only the *visual* viewport and scrolls the page under
// it, so a full-height layout ends up half hidden with the text box behind the keyboard. Sizing the
// app to the visual viewport keeps the whole remote, text box included, above the keyboard.
const vv = window.visualViewport
if (vv) {
  const fit = () => {
    document.documentElement.style.setProperty('--app-height', `${vv.height}px`)
    window.scrollTo(0, 0)
  }
  vv.addEventListener('resize', fit)
  vv.addEventListener('scroll', fit)
  fit()
}

// Lets Android install the remote as an app, which is what puts it in the system share menu.
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {})

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
