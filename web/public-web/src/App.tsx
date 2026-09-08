import { NavLink, Route, Routes } from 'react-router-dom'
import { strings } from './strings'
import { HomeScreen } from './routes/HomeScreen'
import { HistoryScreen } from './routes/HistoryScreen'
import { NewReportScreen } from './routes/NewReportScreen'

/**
 * The exact three primary destinations the Phase 5 brief requires (§2) - no more, no
 * fewer. One shared nav element handles both the mobile bottom bar and the desktop top
 * bar via CSS (`styles.css`), rather than two separate implementations.
 */
export function App() {
  return (
    <div className="app">
      <header className="top-bar">
        <span className="brand">{strings.appName}</span>
        <nav className="top-nav" aria-label="Elsődleges navigáció">
          <PrimaryNavLinks />
        </nav>
      </header>

      <main className="app__main">
        <Routes>
          <Route path="/" element={<HomeScreen />} />
          <Route path="/uj-bejelentes" element={<NewReportScreen />} />
          <Route path="/elozmenyek" element={<HistoryScreen />} />
          <Route path="*" element={<HomeScreen />} />
        </Routes>
      </main>

      <nav className="bottom-nav" aria-label="Elsődleges navigáció">
        <PrimaryNavLinks />
      </nav>
    </div>
  )
}

function PrimaryNavLinks() {
  return (
    <>
      <NavLink to="/" end className={({ isActive }) => `nav-link${isActive ? ' nav-link--active' : ''}`}>
        {strings.navHome}
      </NavLink>
      <NavLink to="/uj-bejelentes" className={({ isActive }) => `nav-link${isActive ? ' nav-link--active' : ''}`}>
        {strings.navNewReport}
      </NavLink>
      <NavLink to="/elozmenyek" className={({ isActive }) => `nav-link${isActive ? ' nav-link--active' : ''}`}>
        {strings.navHistory}
      </NavLink>
    </>
  )
}
