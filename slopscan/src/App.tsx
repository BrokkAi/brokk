import { Routes, Route } from "react-router-dom";
import { Layout } from "./components/Layout";
import { HomePage } from "./pages/Home";
import { ScanPage } from "./pages/Scan";
import { DashboardPage } from "./pages/Dashboard";

function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="scan" element={<ScanPage />} />
        <Route path="scan-result/:id" element={<DashboardPage />} />
        <Route path="dashboard/:jobId" element={<DashboardPage />} />
      </Route>
    </Routes>
  );
}

export default App;
