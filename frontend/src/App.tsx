import { Route, Routes } from "react-router-dom";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { Layout } from "./components/Layout";
import { BackofficeShell } from "./components/backoffice/BackofficeShell";
import { Advisor } from "./pages/Advisor";
import { BackofficeGate } from "./pages/backoffice/BackofficeGate";
import { BackofficeInsights } from "./pages/backoffice/BackofficeInsights";
import { BackofficeLead } from "./pages/backoffice/BackofficeLead";
import { BackofficeQueue } from "./pages/backoffice/BackofficeQueue";
import { Kyc } from "./pages/Kyc";
import { Landing } from "./pages/Landing";
import { NotFound } from "./pages/NotFound";
import { Quote } from "./pages/Quote";
import { Track } from "./pages/Track";
import { TrackLookup } from "./pages/TrackLookup";

function CustomerApp() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Landing />} />
        <Route path="/advisor" element={<Advisor />} />
        <Route path="/quote" element={<Quote />} />
        <Route path="/kyc/:orderId" element={<Kyc />} />
        <Route path="/track" element={<TrackLookup />} />
        <Route path="/track/:orderId" element={<Track />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </Layout>
  );
}

export function App() {
  return (
    <ErrorBoundary>
      <Routes>
        <Route path="/backoffice/login" element={<BackofficeGate />} />
        <Route path="/backoffice" element={<BackofficeShell />}>
          <Route index element={<BackofficeQueue />} />
          <Route path="leads/:kind/:ref" element={<BackofficeLead />} />
          <Route path="insights" element={<BackofficeInsights />} />
        </Route>
        <Route path="*" element={<CustomerApp />} />
      </Routes>
    </ErrorBoundary>
  );
}
