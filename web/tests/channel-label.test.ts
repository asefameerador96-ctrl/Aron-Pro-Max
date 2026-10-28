// Channel labels for the dashboard `by_channel` rows: the 'unknown' code (memos with no channel) gets a neutral Bangla/English label.
import { describe, expect, it } from "vitest";
import { channelLabel } from "@/lib/dash/channel";
import { bn } from "@/lib/i18n/messages-bn";
import { en } from "@/lib/i18n/messages-en";

describe("channelLabel", () => {
  it("unknown is a neutral label in both languages, never a raw key", () => {
    expect(channelLabel("en", "unknown")).toBe("Unknown");
    expect(channelLabel("bn", "unknown")).toBe("অজানা");
    expect(channelLabel("en", "unknown", null)).not.toContain("channel.");
    expect(en["channel.unknown"]).toBe("Unknown");
    expect(bn["channel.unknown"]).toBe("অজানা");
  });
  it("the server's name wins; the six known codes use their messages; any other code is shown as given", () => {
    expect(channelLabel("en", "unknown", "No channel")).toBe("No channel");
    expect(channelLabel("en", "GT")).toBe("General trade");
    expect(channelLabel("bn", "HoReCa")).toBe("হোরেকা");
    expect(channelLabel("en", "grocery")).toBe("grocery");
  });
});
