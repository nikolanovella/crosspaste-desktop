import { CrossPasteCrypto } from "@/shared/core";

/**
 * Stored key pair format.
 * DER-encoded byte arrays serialized as number[] for chrome.storage compatibility.
 */
export interface StoredKeyPair {
  signPublicKey: number[];
  signPrivateKey: number[];
  cryptPublicKey: number[];
  cryptPrivateKey: number[];
}

const STORAGE_KEY = "keypairs_der";

function hasExtensionStorage(): boolean {
  return typeof chrome !== "undefined" && chrome.storage?.local !== undefined;
}

function readLocal<T>(key: string): T | null {
  const value = localStorage.getItem(key);
  return value ? (JSON.parse(value) as T) : null;
}

/** Convert Int8Array (Kotlin/JS ByteArray) to number[] for storage */
function toStorable(arr: Int8Array): number[] {
  return Array.from(arr);
}

/** Convert stored number[] back to Int8Array for core API */
export function toInt8Array(arr: number[]): Int8Array {
  return new Int8Array(arr);
}

export const KeyStore = {
  async getKeys(): Promise<StoredKeyPair | null> {
    if (!hasExtensionStorage()) return readLocal<StoredKeyPair>(STORAGE_KEY);
    const result = await chrome.storage.local.get(STORAGE_KEY);
    return (result[STORAGE_KEY] as StoredKeyPair) ?? null;
  },

  async saveKeys(keys: StoredKeyPair): Promise<void> {
    if (!hasExtensionStorage()) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(keys));
      return;
    }
    await chrome.storage.local.set({ [STORAGE_KEY]: keys });
  },

  async generateAndStore(): Promise<StoredKeyPair> {
    const jsKeyPair = await CrossPasteCrypto.generateKeyPair();
    const stored: StoredKeyPair = {
      signPublicKey: toStorable(jsKeyPair.signPublicKey),
      signPrivateKey: toStorable(jsKeyPair.signPrivateKey),
      cryptPublicKey: toStorable(jsKeyPair.cryptPublicKey),
      cryptPrivateKey: toStorable(jsKeyPair.cryptPrivateKey),
    };
    await this.saveKeys(stored);
    return stored;
  },

  async clear(): Promise<void> {
    if (!hasExtensionStorage()) {
      localStorage.removeItem(STORAGE_KEY);
      return;
    }
    await chrome.storage.local.remove(STORAGE_KEY);
  },
};
