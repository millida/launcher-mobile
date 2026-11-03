// React for the website screens. The site's module does not carry its own React: it reads these four
// from globalThis.__MILLIDA_UI_SHARED__ (see scripts/launcher-ui/vite.config.mjs in millida-web), so the
// app provides the same React 19 the site is built against.
import * as React from 'react'
import * as ReactJsx from 'react/jsx-runtime'
import * as ReactDOM from 'react-dom'
import * as ReactDOMClient from 'react-dom/client'

globalThis.__MILLIDA_UI_SHARED__ ??= Object.freeze({
  react: React,
  'react/jsx-runtime': ReactJsx,
  'react-dom': ReactDOM,
  'react-dom/client': ReactDOMClient,
})
