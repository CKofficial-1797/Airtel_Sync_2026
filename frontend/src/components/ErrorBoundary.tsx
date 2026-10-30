import { Component, type ErrorInfo, type ReactNode } from "react";
import { withTranslation, type WithTranslation } from "react-i18next";

interface State {
  failed: boolean;
}

class Boundary extends Component<WithTranslation & { children: ReactNode }, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    if (import.meta.env.DEV) {
      console.error(error, info.componentStack); // dev only: never log user data in production
    }
  }

  render() {
    const { t, children } = this.props;
    if (!this.state.failed) return children;
    return (
      <div role="alert" className="flex min-h-dvh flex-col items-center justify-center gap-3 bg-slate-50 p-6 text-center">
        <h1 className="text-xl font-semibold text-slate-900">{t("errorBoundary.title")}</h1>
        <p className="text-slate-600">{t("errorBoundary.body")}</p>
        <button
          type="button"
          onClick={() => window.location.reload()}
          className="min-h-11 rounded-xl bg-teal-700 px-5 font-semibold text-white"
        >
          {t("errorBoundary.reload")}
        </button>
      </div>
    );
  }
}

export const ErrorBoundary = withTranslation()(Boundary);
