import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { kiranaQuote, kiranaRecommendation } from "../test/fixtures";
import { RecommendationCard } from "./RecommendationCard";

describe("RecommendationCard", () => {
  it("renders totals and items from the engine JSON", () => {
    render(
      <RecommendationCard recommendation={kiranaRecommendation()} quote={kiranaQuote()} stage="RECOMMENDED" onOpenQuote={() => {}} />,
    );
    expect(screen.getByTestId("card-monthly")).toHaveTextContent("₹1,597");
    expect(screen.getByTestId("card-setup")).toHaveTextContent("₹0 one-time setup");
    expect(screen.getByTestId("card-contract")).toHaveTextContent("12 month contract");
    expect(screen.getByText("Biz Fibre 100 × 1")).toBeInTheDocument();
    expect(screen.getByText("Business Postpaid 299 × 2")).toBeInTheDocument();
    expect(screen.getByText("100 Mbps business fibre for 1 site(s)")).toBeInTheDocument();
  });

  it("shows whatever the JSON says, e.g. a customised total", () => {
    render(
      <RecommendationCard
        recommendation={kiranaRecommendation()}
        quote={kiranaQuote({ monthlyTotalInr: 46955, oneTimeTotalInr: 100800, contractMonths: 24 })}
        stage="CUSTOMISING"
        onOpenQuote={() => {}}
      />,
    );
    expect(screen.getByTestId("card-monthly")).toHaveTextContent("₹46,955");
    expect(screen.getByTestId("card-setup")).toHaveTextContent("₹1,00,800");
    expect(screen.getByText("Your updated bundle")).toBeInTheDocument();
  });

  it("makes the CTA prominent when ready for checkout", async () => {
    const onOpen = vi.fn();
    render(<RecommendationCard recommendation={null} quote={kiranaQuote()} stage="READY_FOR_CHECKOUT" onOpenQuote={onOpen} />);
    await userEvent.click(screen.getByRole("button", { name: /Review quote & continue/ }));
    expect(onOpen).toHaveBeenCalledOnce();
  });
});
