import { Fragment, StrictMode, useEffect, useState, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { StoreProvider } from './api/store';
import { AuthProvider, useAuth } from './auth/auth';
import { Guard, Layout } from './components/Layout';
import { Login } from './pages/Login';
import { Dashboard } from './pages/Dashboard';
import { AgentDetail, Agents } from './pages/Agents';
import { Policies } from './pages/Policies';
import { Budgets } from './pages/Budgets';
import { Approvals } from './pages/Approvals';
import { OperationDetail, Operations } from './pages/Operations';
import { Software, SubscriptionDetail } from './pages/Software';
import { AssetDetail, Assets } from './pages/Assets';
import { ConnectionDetail, Connections } from './pages/Connections';
import { ImportDetail, Imports } from './pages/Imports';
import { FindingDetail, Findings } from './pages/Findings';
import { Analytics } from './pages/Analytics';
import { Org } from './pages/Org';
import { NotFound, Notifications } from './pages/Notifications';
import { Cabinet } from './pages/Cabinet';
import { People } from './pages/People';
import './styles.css';
import { getLang, initLang, onLang } from './i18n';

initLang();

function Protected() {
  const { user } = useAuth();
  return user ? <Layout /> : <Navigate to="/login" replace />;
}

/** Сотрудник без ролей персонала начинает со своего кабинета, остальные — с обзора. */
function Home() {
  const { canView } = useAuth();
  return canView('dashboard') ? <Dashboard /> : <Navigate to="/cabinet" replace />;
}

/** A new language re-renders every page (numbers and dates follow it, ADR 0074); the store keeps its data. */
function LangKeyed({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState(getLang());
  useEffect(() => onLang(setLangState), []);
  return <Fragment key={lang}>{children}</Fragment>;
}

function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<Protected />}>
        <Route index element={<Home />} />
        <Route path="cabinet/*" element={<Guard section="cabinet"><Cabinet /></Guard>} />
        <Route path="agents" element={<Guard section="agents"><Agents /></Guard>} />
        <Route path="agents/:id" element={<Guard section="agents"><AgentDetail /></Guard>} />
        <Route path="policies" element={<Guard section="policies"><Policies /></Guard>} />
        <Route path="budgets" element={<Guard section="budgets"><Budgets /></Guard>} />
        <Route path="approvals" element={<Guard section="approvals"><Approvals /></Guard>} />
        <Route path="operations" element={<Guard section="operations"><Operations /></Guard>} />
        <Route path="operations/:id" element={<Guard section="operations"><OperationDetail /></Guard>} />
        <Route path="software" element={<Guard section="software"><Software /></Guard>} />
        <Route path="software/subscriptions/:id" element={<Guard section="software"><SubscriptionDetail /></Guard>} />
        <Route path="assets" element={<Guard section="assets"><Assets /></Guard>} />
        <Route path="assets/:id" element={<Guard section="assets"><AssetDetail /></Guard>} />
        <Route path="connections" element={<Guard section="connections"><Connections /></Guard>} />
        <Route path="connections/:id" element={<Guard section="connections"><ConnectionDetail /></Guard>} />
        <Route path="imports" element={<Guard section="imports"><Imports /></Guard>} />
        <Route path="imports/:id" element={<Guard section="imports"><ImportDetail /></Guard>} />
        <Route path="findings" element={<Guard section="findings"><Findings /></Guard>} />
        <Route path="findings/:id" element={<Guard section="findings"><FindingDetail /></Guard>} />
        <Route path="analytics" element={<Guard section="analytics"><Analytics /></Guard>} />
        <Route path="org" element={<Guard section="org"><Org /></Guard>} />
        <Route path="people" element={<Guard section="people"><People /></Guard>} />
        <Route path="notifications" element={<Guard section="notifications"><Notifications /></Guard>} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <StoreProvider>
        <AuthProvider>
          <LangKeyed><App /></LangKeyed>
        </AuthProvider>
      </StoreProvider>
    </BrowserRouter>
  </StrictMode>,
);
