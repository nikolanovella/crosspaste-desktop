import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { SyncApi } from "@/shared/api/sync";
import type { SyncInfo } from "@/shared/models/sync-info";
import type { KeyExchangeResponse } from "@/shared/models/key-exchange";
import { KeyStore, toInt8Array } from "@/shared/storage/key-store";
import { DeviceStore, type StoredDevice } from "@/shared/storage/device-store";
import { CrossPasteHash, CrossPasteJson, createSecureMessageProcessor } from "@/shared/core";
import { WsClient } from "@/shared/ws/ws-client";
import { WsMessageType, type WsEnvelope } from "@/shared/ws/ws-types";
import "./style.css";

type Step = "connect" | "compare" | "ready";
type NativeClipboard = { postMessage(message: unknown): Promise<unknown> };
declare global {
  interface Window {
    webkit?: { messageHandlers?: { clipboard?: NativeClipboard } };
  }
}

const APP_ID_KEY = "crosspaste_ios_app_instance_id";
const PROTOCOL_VERSION = 3;

function appInstanceId(): string {
  let id = localStorage.getItem(APP_ID_KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(APP_ID_KEY, id);
  }
  return id;
}

function ownSyncInfo(id: string): SyncInfo {
  return {
    appInfo: {
      appInstanceId: id,
      appVersion: "2.3.1",
      appRevision: "iOS",
      userName: "iPhone",
      pairingVersion: 2,
    },
    endpointInfo: {
      deviceId: id,
      deviceName: "iPhone",
      platform: { name: "iPhone", arch: "arm64", bitMode: 64, version: "iOS" },
      hostInfoList: [],
      port: 0,
    },
  };
}

function textFromPaste(json: string): string | null {
  try {
    const data = JSON.parse(CrossPasteJson.parsePasteData(json)) as {
      pasteAppearItem?: { type?: string; text?: string; url?: string } | null;
      pasteCollection?: { pasteItems?: Array<{ type?: string; text?: string; url?: string }> };
    };
    const item = data.pasteAppearItem ?? data.pasteCollection?.pasteItems?.[0];
    if (item?.type === "text") return item.text ?? null;
    if (item?.type === "url") return item.url ?? null;
  } catch {
    return null;
  }
  return null;
}

function App() {
  const [step, setStep] = useState<Step>("connect");
  const [host, setHost] = useState("");
  const [port, setPort] = useState("13129");
  const [pin, setPin] = useState("");
  const [localPin, setLocalPin] = useState<number | null>(null);
  const [device, setDevice] = useState<SyncInfo | null>(null);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [connected, setConnected] = useState(false);
  const [clipText, setClipText] = useState("");
  const [latestRemote, setLatestRemote] = useState("");
  const ws = useRef<WsClient | null>(null);
  const cryptoProcessor = useRef<Awaited<ReturnType<typeof createSecureMessageProcessor>> | null>(null);
  const targetConfig = useRef<{ host: string; port: number; targetAppInstanceId: string } | null>(null);
  const pendingExchange = useRef<(KeyExchangeResponse & { requestTimestamp: number }) | null>(null);

  useEffect(() => () => ws.current?.close(), []);

  async function connect() {
    setBusy(true); setMessage("");
    try {
      const numericPort = Number(port);
      if (!host.trim() || !Number.isInteger(numericPort) || numericPort < 1 || numericPort > 65535) {
        throw new Error("Enter the desktop IP address and port.");
      }
      const id = appInstanceId();
      const config = { host: host.trim(), port: numericPort, appInstanceId: id };
      const version = await SyncApi.telnet(config);
      if (version !== PROTOCOL_VERSION) throw new Error(`CrossPaste protocol mismatch (desktop ${version}, iPhone ${PROTOCOL_VERSION}).`);
      const info = await SyncApi.getSyncInfo(config);
      if ((info.appInfo.pairingVersion ?? 1) < 2) throw new Error("This desktop needs a newer CrossPaste pairing protocol.");
      const pairConfig = { ...config, targetAppInstanceId: info.appInfo.appInstanceId };
      await SyncApi.showPairingCode(pairConfig).catch(() => {});
      const exchange = await SyncApi.exchangeV2(pairConfig);
      pendingExchange.current = exchange;
      const code = await SyncApi.computeLocalSAS(exchange.cryptPublicKey);
      targetConfig.current = { host: config.host, port: numericPort, targetAppInstanceId: info.appInfo.appInstanceId };
      setDevice(info);
      setLocalPin(code);
      setStep("compare");
      setMessage("Compare the code below with the code shown on your desktop.");
    } catch (error) {
      const exchange = pendingExchange.current;
      const target = targetConfig.current;
      if (exchange && target) {
        void SyncApi.cancelV2({ ...target, appInstanceId: appInstanceId() }, exchange.requestTimestamp).catch(() => {});
      }
      pendingExchange.current = null;
      setMessage(error instanceof Error ? error.message : String(error));
    } finally { setBusy(false); }
  }

  async function pair() {
    const target = targetConfig.current;
    if (!target || !device || localPin === null) return;
    if (!/^\d{6}$/.test(pin) || Number(pin) !== localPin) {
      setMessage("The codes don’t match. Cancel and try pairing again.");
      return;
    }
    setBusy(true); setMessage("");
    const id = appInstanceId();
    const config = { ...target, appInstanceId: id };
    try {
      const exchange = pendingExchange.current;
      if (!exchange) throw new Error("The pairing session expired. Start again.");
      const check = await SyncApi.computeLocalSAS(exchange.cryptPublicKey);
      if (check !== localPin) throw new Error("The pairing exchange changed. Start again.");
      await SyncApi.confirmV2(config, exchange.signPublicKey, ownSyncInfo(id));
      const stored: StoredDevice = {
        targetAppInstanceId: device.appInfo.appInstanceId,
        syncInfo: device,
        host: target.host,
        port: target.port,
        trusted: true,
        serverKeys: { signPublicKey: exchange.signPublicKey, cryptPublicKey: exchange.cryptPublicKey },
        addedAt: Date.now(),
      };
      await DeviceStore.save(stored);
      pendingExchange.current = null;
      await openSocket(stored);
      setStep("ready");
      setMessage("Paired securely. Keep CrossPaste open on this screen to sync.");
    } catch (error) {
      const exchange = pendingExchange.current;
      if (exchange) {
        void SyncApi.cancelV2(config, exchange.requestTimestamp).catch(() => {});
        pendingExchange.current = null;
      }
      setMessage(error instanceof Error ? error.message : String(error));
    } finally { setBusy(false); }
  }

  async function openSocket(target: StoredDevice) {
    const keys = await KeyStore.getKeys();
    if (!keys?.cryptPrivateKey || !target.serverKeys?.cryptPublicKey) throw new Error("Pairing keys are missing.");
    const processor = await createSecureMessageProcessor(
      toInt8Array(keys.cryptPrivateKey),
      CrossPasteHash.base64Decode(target.serverKeys.cryptPublicKey),
    );
    cryptoProcessor.current = processor;
    const client = new WsClient({
      host: target.host,
      port: target.port,
      appInstanceId: appInstanceId(),
      targetAppInstanceId: target.targetAppInstanceId,
      pairingVersion: 2,
      createAuthProcessor: async () => processor,
    });
    client.onMessage = (envelope: WsEnvelope) => { void receive(client, envelope); };
    client.onDisconnect = () => setConnected(false);
    client.onConnect = () => setConnected(true);
    ws.current = client;
    if (!await client.connect()) throw new Error("Pairing succeeded, but the secure sync connection failed.");
  }

  async function receive(client: WsClient, envelope: WsEnvelope) {
    try {
      let payload = envelope.payload;
      if (envelope.encrypted) {
        const processor = cryptoProcessor.current;
        if (!processor) throw new Error("No trusted device key.");
        const plain = await processor.decrypt(new Int8Array(payload));
        payload = new Uint8Array(plain.buffer, plain.byteOffset, plain.byteLength);
      }
      if (envelope.type === WsMessageType.PASTE_PUSH) {
        const text = textFromPaste(new TextDecoder().decode(payload));
        if (text !== null) setLatestRemote(text);
        if (envelope.requestId) {
          await client.sendEnvelope({ type: WsMessageType.PASTE_PUSH_ACK, payload: new Uint8Array(), encrypted: false, requestId: envelope.requestId });
        }
      } else if (envelope.type === WsMessageType.HEARTBEAT) {
        await client.sendEnvelope({ type: WsMessageType.HEARTBEAT_ACK, payload: new Uint8Array(), encrypted: false });
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    }
  }

  async function readClipboard() {
    const bridge = window.webkit?.messageHandlers?.clipboard;
    if (!bridge) { setMessage("iPhone clipboard access is unavailable."); return; }
    try {
      const value = await bridge.postMessage({ action: "read" });
      setClipText(typeof value === "string" ? value : "");
    } catch { setMessage("Could not read the iPhone clipboard."); }
  }

  async function sendText() {
    const client = ws.current;
    const target = targetConfig.current;
    const text = clipText.trim();
    if (!client?.isActive || !target || !text) return;
    const id = appInstanceId();
    const bytes = new TextEncoder().encode(text);
    const hash = CrossPasteHash.hashText(text);
    const item = { type: "text", identifiers: ["text/plain"], hash, size: bytes.length, text };
    const paste = {
      id: Date.now(), appInstanceId: id, favorite: false,
      pasteAppearItem: item, pasteCollection: { pasteItems: [item] },
      pasteType: 0, source: "iPhone", size: bytes.length, hash,
      pasteState: 1,
    };
    try {
      const normalized = CrossPasteJson.parsePasteData(JSON.stringify(paste));
      await client.sendEnvelope({ type: WsMessageType.PASTE_PUSH, payload: new TextEncoder().encode(normalized), encrypted: false });
      setMessage("Sent to your paired desktop.");
    } catch (error) { setMessage(error instanceof Error ? error.message : String(error)); }
  }

  async function copyIncoming() {
    if (!latestRemote) return;
    const bridge = window.webkit?.messageHandlers?.clipboard;
    if (!bridge) { setMessage("iPhone clipboard access is unavailable."); return; }
    try { await bridge.postMessage({ action: "write", text: latestRemote }); setMessage("Copied to your iPhone clipboard."); }
    catch { setMessage("Could not write the iPhone clipboard."); }
  }

  return <main className="app">
    <header><span className="mark">C</span><div><h1>CrossPaste</h1><p>iPhone companion</p></div><span className={connected ? "dot on" : "dot"} /></header>
    {step === "connect" && <section className="card">
      <h2>Connect to your desktop</h2>
      <p>Enter the IP and port shown in CrossPaste on your Mac or PC.</p>
      <label>IP address<input value={host} onChange={e => setHost(e.target.value)} placeholder="192.168.1.100" autoCapitalize="none" /></label>
      <label>Port<input value={port} onChange={e => setPort(e.target.value)} inputMode="numeric" /></label>
      <button disabled={busy} onClick={() => void connect()}>{busy ? "Connecting…" : "Connect"}</button>
    </section>}
    {step === "compare" && <section className="card">
      <h2>Verify this device</h2>
      <p>Compare this code with the code shown on <b>{device?.appInfo.userName ?? "your desktop"}</b>.</p>
      <div className="pin">{String(localPin ?? "").padStart(6, "0")}</div>
      <p>Enter the desktop code to confirm both screens match.</p>
      <label>Desktop code<input value={pin} onChange={e => setPin(e.target.value.replace(/\D/g, "").slice(0, 6))} inputMode="numeric" maxLength={6} /></label>
      <button disabled={busy || pin.length !== 6} onClick={() => void pair()}>{busy ? "Pairing…" : "Confirm and pair"}</button>
      <button className="secondary" onClick={() => {
        const exchange = pendingExchange.current;
        const target = targetConfig.current;
        if (exchange && target) void SyncApi.cancelV2({ ...target, appInstanceId: appInstanceId() }, exchange.requestTimestamp).catch(() => {});
        pendingExchange.current = null;
        ws.current?.close(); setStep("connect"); setPin(""); setMessage("");
      }}>Cancel</button>
    </section>}
    {step === "ready" && <>
      <section className="card"><h2>{device?.appInfo.userName ?? "Desktop"}</h2><p>{connected ? "Secure connection active" : "Reconnecting…"}</p></section>
      <section className="card"><h2>Send from iPhone</h2>
        <button className="secondary" onClick={() => void readClipboard()}>Read iPhone clipboard</button>
        <textarea value={clipText} onChange={e => setClipText(e.target.value)} placeholder="Type or read text to send" />
        <button disabled={!connected || !clipText.trim()} onClick={() => void sendText()}>Send to desktop</button>
      </section>
      <section className="card"><h2>Latest from desktop</h2>
        <textarea readOnly value={latestRemote} placeholder="Text copied on your desktop will appear here while this app is open." />
        <button className="secondary" disabled={!latestRemote} onClick={() => void copyIncoming()}>Copy to iPhone clipboard</button>
      </section>
    </>}
    {message && <p className="message">{message}</p>}
    <footer>Same Wi-Fi · End-to-end paired · Foreground sync</footer>
  </main>;
}

createRoot(document.getElementById("root")!).render(<App />);
