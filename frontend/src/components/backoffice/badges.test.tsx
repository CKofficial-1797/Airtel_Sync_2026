import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { BandPill } from "./BandPill";
import { ChannelBadge } from "./ChannelBadge";
import { ReasonBars } from "./ReasonBars";

describe("BandPill", () => {
  it.each([
    ["HOT", "Hot"],
    ["WARM", "Warm"],
    ["COLD", "Cold"],
  ])("writes %s out in words", (band, text) => {
    render(<BandPill band={band} />);
    expect(screen.getByText(text)).toBeInTheDocument();
  });
});

describe("ChannelBadge", () => {
  it.each([
    ["DIGITAL_NUDGE", "Digital nudge"],
    ["INSIDE_SALES", "Inside sales"],
    ["FIELD_SALES", "Field sales"],
    ["CHANNEL_PARTNER", "Channel partner"],
  ])("names %s", (channel, text) => {
    render(<ChannelBadge channel={channel} />);
    expect(screen.getByText(text)).toBeInTheDocument();
  });
});

describe("ReasonBars", () => {
  it("says which way each reason pushes, in words as well as colour", () => {
    render(
      <ReasonBars
        reasons={[
          { labelEn: "Active GSTIN", labelHi: "सक्रिय GSTIN", direction: "+", contribution: 1.2 },
          { labelEn: "Micro business", labelHi: "माइक्रो", direction: "-", contribution: -0.6 },
        ]}
      />,
    );
    expect(screen.getByText("Active GSTIN")).toHaveTextContent("Raises the score:");
    expect(screen.getByText("Micro business")).toHaveTextContent("Lowers the score:");
  });

  it("explains an empty list", () => {
    render(<ReasonBars reasons={[]} />);
    expect(screen.getByText(/too little/)).toBeInTheDocument();
  });
});
