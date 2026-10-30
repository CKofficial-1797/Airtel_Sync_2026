// localStorage can throw (private mode, blocked storage); the app must keep working without it.

export const KEYS = {
  language: "udyam.language",
  sessionId: "udyam.sessionId",
} as const;

export function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function writeStorage(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // ignore: storage unavailable
  }
}

export function removeStorage(key: string): void {
  try {
    window.localStorage.removeItem(key);
  } catch {
    // ignore: storage unavailable
  }
}
