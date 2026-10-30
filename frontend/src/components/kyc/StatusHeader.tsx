import { CircleAlert, CircleCheck, CircleX } from "lucide-react";
import type { ReactNode } from "react";
import type { KycOverall } from "../../api/types";

const STYLES: Record<KycOverall, { box: string; icon: ReactNode }> = {
  VERIFIED: { box: "border-green-300 bg-green-100 text-green-950", icon: <CircleCheck className="size-8 shrink-0 text-green-800" aria-hidden /> },
  ACTION_REQUIRED: { box: "border-amber-300 bg-amber-100 text-amber-950", icon: <CircleAlert className="size-8 shrink-0 text-amber-800" aria-hidden /> },
  REJECTED: { box: "border-red-200 bg-red-50 text-red-950", icon: <CircleX className="size-8 shrink-0 text-red-700" aria-hidden /> },
};

interface Props {
  overall: KycOverall;
  title: string;
  children?: ReactNode;
}

export function StatusHeader({ overall, title, children }: Props) {
  const { box, icon } = STYLES[overall];
  return (
    <section className={`flex items-start gap-3 rounded-2xl border p-4 ${box}`} data-testid="status-header">
      {icon}
      <div className="min-w-0 flex-1">
        <h2 className="text-lg font-bold">{title}</h2>
        {children}
      </div>
    </section>
  );
}
