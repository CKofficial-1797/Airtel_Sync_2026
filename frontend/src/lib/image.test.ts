import { describe, expect, it } from "vitest";
import { ImageRejected, MAX_INPUT_BYTES, MAX_UPLOAD_BYTES, prepareImage, targetSize } from "./image";

describe("targetSize", () => {
  it("scales a landscape photo so the long side is 2000 px", () => {
    expect(targetSize(4000, 3000)).toEqual({ width: 2000, height: 1500, resized: true });
  });

  it("scales a portrait photo so the long side is 2000 px", () => {
    expect(targetSize(3000, 4000)).toEqual({ width: 1500, height: 2000, resized: true });
  });

  it("rounds to whole pixels and keeps the aspect ratio", () => {
    expect(targetSize(4032, 3024)).toEqual({ width: 2000, height: 1500, resized: true });
    expect(targetSize(5000, 1234)).toEqual({ width: 2000, height: 494, resized: true });
  });

  it("leaves images at or below the limit untouched", () => {
    expect(targetSize(2000, 1000)).toEqual({ width: 2000, height: 1000, resized: false });
    expect(targetSize(1446, 1894)).toEqual({ width: 1446, height: 1894, resized: false }); // K02 GST specimen
    expect(targetSize(1240, 1754)).toEqual({ width: 1240, height: 1754, resized: false }); // other specimens
  });
});

describe("prepareImage (no createImageBitmap in jsdom)", () => {
  const file = (size: number, type: string, name = "photo") => new File([new Uint8Array(size)], name, { type });
  const problem = (f: File) => prepareImage(f).catch((e: ImageRejected) => e.problem);

  it("rejects other file types", async () => {
    expect(await problem(file(10, "application/pdf", "a.pdf"))).toBe("type");
    expect(await problem(file(10, "image/heic", "a.heic"))).toBe("type");
  });

  it("accepts an empty type only with an image extension", async () => {
    const jpg = file(10, "", "IMG_1.JPG");
    await expect(prepareImage(jpg)).resolves.toBe(jpg);
    expect(await problem(file(10, "", "notes.txt"))).toBe("type");
  });

  it("returns a small enough photo byte-identical", async () => {
    const f = file(100, "image/jpeg");
    await expect(prepareImage(f)).resolves.toBe(f);
  });

  it("rejects what is still over 5 MB, and absurdly large inputs before decoding", async () => {
    expect(await problem(file(MAX_UPLOAD_BYTES + 1, "image/png"))).toBe("size");
    expect(await problem(file(MAX_INPUT_BYTES + 1, "image/jpeg"))).toBe("size");
  });
});
