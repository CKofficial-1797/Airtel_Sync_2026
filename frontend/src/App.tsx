import { Route, Routes } from "react-router-dom";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { Layout } from "./components/Layout";
import { Advisor } from "./pages/Advisor";
import { Kyc } from "./pages/Kyc";
import { Landing } from "./pages/Landing";
import { NotFound } from "./pages/NotFound";
import { Quote } from "./pages/Quote";
import { TrackPlaceholder } from "./pages/TrackPlaceholder";

export function App() {
  return (
    <ErrorBoundary>
      <Layout>
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route path="/advisor" element={<Advisor />} />
          <Route path="/quote" element={<Quote />} />
          <Route path="/kyc/:orderId" element={<Kyc />} />
          <Route path="/track" element={<TrackPlaceholder />} />
          <Route path="/track/:orderId" element={<TrackPlaceholder />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Layout>
    </ErrorBoundary>
  );
}
