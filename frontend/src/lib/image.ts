/** Client-side photo checks + downscale. The server re-checks type and size; this only saves the owner's data. */

export const MAX_SIDE_PX = 2000;
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
/** Above this we don't even try to decode (protects low-end phones). Normal camera photos are far below it. */
export const MAX_INPUT_BYTES = 30 * 1024 * 1024;
const JPEG_QUALITY = 0.9;
const ACCEPTED = ["image/png", "image/jpeg"];

export type ImageProblem = "type" | "size" | "decode";

export class ImageRejected extends Error {
  readonly problem: ImageProblem;

  constructor(problem: ImageProblem) {
    super(`Image rejected: ${problem}`);
    this.name = "ImageRejected";
    this.problem = problem;
  }
}

/** Long side capped at maxSide, aspect ratio kept. Images already small enough are returned unchanged. */
export function targetSize(width: number, height: number, maxSide = MAX_SIDE_PX): { width: number; height: number; resized: boolean } {
  const longest = Math.max(width, height);
  if (longest <= maxSide) {
    return { width, height, resized: false };
  }
  const scale = maxSide / longest;
  return { width: Math.round(width * scale), height: Math.round(height * scale), resized: true };
}

function isAcceptedType(file: File): boolean {
  // Some Android pickers leave the type empty; the server still verifies the magic bytes.
  return ACCEPTED.includes(file.type) || (file.type === "" && /\.(png|jpe?g)$/i.test(file.name));
}

async function resize(bitmap: ImageBitmap, width: number, height: number, name: string): Promise<File> {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) {
    throw new ImageRejected("decode");
  }
  context.drawImage(bitmap, 0, 0, width, height);
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", JPEG_QUALITY));
  if (!blob) {
    throw new ImageRejected("decode");
  }
  return new File([blob], `${name.replace(/\.[^.]+$/, "")}.jpg`, { type: "image/jpeg" });
}

/**
 * Returns the file to upload: the original bytes when the photo is small enough, otherwise a 2000 px JPEG.
 * The 5 MB limit applies to the result, so a large camera photo is shrunk rather than refused.
 */
export async function prepareImage(file: File): Promise<File> {
  if (!isAcceptedType(file)) {
    throw new ImageRejected("type");
  }
  if (file.size > MAX_INPUT_BYTES) {
    throw new ImageRejected("size");
  }
  let result = file;
  if (typeof createImageBitmap === "function") {
    let bitmap: ImageBitmap;
    try {
      bitmap = await createImageBitmap(file);
    } catch {
      throw new ImageRejected("decode");
    }
    try {
      const size = targetSize(bitmap.width, bitmap.height);
      if (size.resized) {
        result = await resize(bitmap, size.width, size.height, file.name);
      }
    } finally {
      bitmap.close();
    }
  }
  if (result.size > MAX_UPLOAD_BYTES) {
    throw new ImageRejected("size");
  }
  return result;
}
