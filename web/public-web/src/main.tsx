import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import { createDefaultRepositories, RepositoriesProvider } from './repositoryContext'
import './styles.css'

const container = document.getElementById('root')
if (!container) {
  throw new Error('Root container #root is missing from index.html')
}

// One set of repositories for the whole page session (mirrors the Android AppContainer).
const repositories = createDefaultRepositories()

createRoot(container).render(
  <StrictMode>
    <RepositoriesProvider value={repositories}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </RepositoriesProvider>
  </StrictMode>,
)

// Best-effort durability request (§35) - never required for correctness, never repeated.
if ('storage' in navigator && 'persist' in navigator.storage) {
  navigator.storage.persist().catch(() => undefined)
}
