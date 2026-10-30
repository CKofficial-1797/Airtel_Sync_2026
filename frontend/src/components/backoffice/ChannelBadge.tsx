import { Car, Handshake, Headset, Send } from "lucide-react";
import { useTranslation } from "react-i18next";

const ICONS: Record<string, typeof Car> = {
  DIGITAL_NUDGE: Send,
  INSIDE_SALES: Headset,
  FIELD_SALES: Car,
  CHANNEL_PARTNER: Handshake,
};

export function ChannelBadge({ channel }: { channel: string }) {
  const { t } = useTranslation();
  const Icon = ICONS[channel] ?? Send;
  return (
    <span className="inline-flex items-center gap-1 rounded-lg bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-800">
      <Icon className="size-3.5" aria-hidden />
      {t(`backoffice.channel.${channel}`)}
    </span>
  );
}
