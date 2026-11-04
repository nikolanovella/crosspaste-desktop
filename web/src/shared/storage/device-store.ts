import type { SyncInfo } from "@/shared/models/sync-info";

export interface ServerKeys {
  signPublicKey: string;
  cryptPublicKey: string;
}

export interface StoredDevice {
  targetAppInstanceId: string;
  syncInfo: SyncInfo;
  host: string;
  port: number;
  trusted: boolean;
  noteName?: string;
  serverKeys?: ServerKeys;
  /**
   * True when the device was auto-wiped after UNMATCHED and is now awaiting
   * a re-pair. Surfaces in UI as SyncState.UNVERIFIED. Cleared on successful
   * pair.
   */
  needsRePair?: boolean;
  addedAt: number;
}

const STORAGE_KEY = "devices";

function hasExtensionStorage(): boolean {
  return typeof chrome !== "undefined" && chrome.storage?.local !== undefined;
}

async function readDevices(): Promise<StoredDevice[]> {
  if (!hasExtensionStorage()) {
    const value = localStorage.getItem(STORAGE_KEY);
    return value ? (JSON.parse(value) as StoredDevice[]) : [];
  }
  const result = await chrome.storage.local.get(STORAGE_KEY);
  return (result[STORAGE_KEY] as StoredDevice[]) ?? [];
}

async function writeDevices(devices: StoredDevice[]): Promise<void> {
  if (!hasExtensionStorage()) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(devices));
    return;
  }
  await chrome.storage.local.set({ [STORAGE_KEY]: devices });
}

export const DeviceStore = {
  async getAll(): Promise<StoredDevice[]> {
    return readDevices();
  },

  async save(device: StoredDevice): Promise<void> {
    const devices = await readDevices();
    const index = devices.findIndex(
      (d) => d.targetAppInstanceId === device.targetAppInstanceId,
    );
    if (index >= 0) {
      devices[index] = device;
    } else {
      devices.push(device);
    }
    await writeDevices(devices);
  },

  async remove(targetAppInstanceId: string): Promise<void> {
    const devices = await readDevices();
    const filtered = devices.filter(
      (d) => d.targetAppInstanceId !== targetAppInstanceId,
    );
    await writeDevices(filtered);
  },

  async updateNote(
    targetAppInstanceId: string,
    noteName: string,
  ): Promise<void> {
    const devices = await readDevices();
    const device = devices.find(
      (d) => d.targetAppInstanceId === targetAppInstanceId,
    );
    if (device) {
      device.noteName = noteName || undefined;
      await writeDevices(devices);
    }
  },

  async get(targetAppInstanceId: string): Promise<StoredDevice | null> {
    const devices = await readDevices();
    return (
      devices.find(
        (d) => d.targetAppInstanceId === targetAppInstanceId,
      ) ?? null
    );
  },

  async setNeedsRePair(
    targetAppInstanceId: string,
    needsRePair: boolean,
  ): Promise<void> {
    const devices = await readDevices();
    const device = devices.find(
      (d) => d.targetAppInstanceId === targetAppInstanceId,
    );
    if (!device) return;
    if (needsRePair) {
      device.needsRePair = true;
      device.serverKeys = undefined;
    } else {
      device.needsRePair = undefined;
    }
    await writeDevices(devices);
  },
};
