// The round (i) of the portal cards: a native disclosure with the card's definition, keyboard and screen-reader friendly, no script.
import { Icon } from "@/components/kit/icons";

export function InfoTip({ msg, openMsg }: { msg: string; openMsg: string }) {
  return (
    <details className="relative" data-testid="info-tip">
      <summary aria-label={openMsg} className="flex size-(--ctl-h) cursor-pointer list-none items-center justify-center rounded-full bg-accent-container text-accent-on-container hover:brightness-95 focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus [&::-webkit-details-marker]:hidden">
        <Icon name="info" size={18} />
      </summary>
      <p className="glass-pop absolute end-0 top-12 z-30 w-64 p-3 text-caption text-primary">{msg}</p>
    </details>
  );
}
