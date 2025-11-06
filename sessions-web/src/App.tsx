import { BrowserRouter, Routes, Route, Link } from 'react-router-dom'
import SessionsPage from './pages/SessionsPage'
import SessionView from './pages/SessionView'
import './styles/App.css'

function App() {
  return (
    <BrowserRouter>
      <div className="app">
        <header className="app-header">
          <Link to="/" className="app-title">
            <h1>Brokk Sessions</h1>
          </Link>
          <nav className="app-nav">
            <Link to="/" className="nav-link">Sessions</Link>
          </nav>
        </header>
        <main className="app-main">
          <Routes>
            <Route path="/" element={<SessionsPage />} />
            <Route path="/session/:id" element={<SessionView />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}

export default App
