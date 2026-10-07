// Price types of the contract (PriceType). Kept out of the client component so server pages can import it as plain data.
export const PRICE_TYPES = ["outlet", "cc", "distributor", "reporting", "nto"] as const;
export type PriceType = (typeof PRICE_TYPES)[number];
