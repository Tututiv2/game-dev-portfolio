import { useEffect, useMemo, useRef, useState } from "react";
import type { ChangeEvent } from "react";
import type { Asset, ExportResponse } from "../types/api";

type ShadowPaneProps = {
  spriteSheetAssets: Asset[];
  latestExport: ExportResponse | null;
  latestCodeSnippet: string;
};

function ShadowPane({
  spriteSheetAssets,
  latestExport,
  latestCodeSnippet,
}: ShadowPaneProps) {
  const [selectedSheetId, setSelectedSheetId] = useState<string | null>(null);
  const [fps, setFps] = useState(12);
  const [isPlaying, setIsPlaying] = useState(true);
  const [code, setCode] = useState(latestCodeSnippet);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  const selectedSheet = useMemo(
    () => spriteSheetAssets.find((asset) => asset.id === selectedSheetId) ?? null,
    [spriteSheetAssets, selectedSheetId],
  );

  const previewConfig = useMemo(() => {
    if (!selectedSheet) {
      return null;
    }
    const fromLatest =
      latestExport && latestExport.spritesheet_asset_id === selectedSheet.id
        ? latestExport
        : null;

    const frameCount = fromLatest?.frame.count ?? selectedSheet.frame_count ?? 1;
    const frameWidth =
      fromLatest?.frame.width ??
      Math.max(1, Math.floor((selectedSheet.width ?? 256) / Math.max(frameCount, 1)));
    const frameHeight = fromLatest?.frame.height ?? selectedSheet.height ?? 256;

    return {
      frameCount: Math.max(1, frameCount),
      frameWidth,
      frameHeight,
    };
  }, [latestExport, selectedSheet]);

  useEffect(() => {
    if (!selectedSheetId && spriteSheetAssets.length > 0) {
      const defaultId =
        latestExport?.spritesheet_asset_id ?? spriteSheetAssets[0].id;
      setSelectedSheetId(defaultId);
    }
  }, [latestExport, selectedSheetId, spriteSheetAssets]);

  useEffect(() => {
    if (!latestCodeSnippet || code.trim()) {
      return;
    }
    setCode(latestCodeSnippet);
  }, [latestCodeSnippet, code]);

  useEffect(() => {
    if (!canvasRef.current || !selectedSheet || !previewConfig) {
      return;
    }
    const canvas = canvasRef.current;
    canvas.width = previewConfig.frameWidth;
    canvas.height = previewConfig.frameHeight;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      return;
    }

    const image = new Image();
    image.src = selectedSheet.url;

    let rafId = 0;
    let frameIndex = 0;
    let lastTick = performance.now();
    const frameDuration = 1000 / Math.max(1, fps);

    const draw = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(
        image,
        frameIndex * previewConfig.frameWidth,
        0,
        previewConfig.frameWidth,
        previewConfig.frameHeight,
        0,
        0,
        previewConfig.frameWidth,
        previewConfig.frameHeight,
      );
    };

    const animate = (timestamp: number) => {
      if (image.complete) {
        if (isPlaying && timestamp - lastTick >= frameDuration) {
          frameIndex = (frameIndex + 1) % previewConfig.frameCount;
          lastTick = timestamp;
        }
        draw();
      }
      rafId = window.requestAnimationFrame(animate);
    };

    rafId = window.requestAnimationFrame(animate);
    return () => window.cancelAnimationFrame(rafId);
  }, [selectedSheet, previewConfig, isPlaying, fps]);

  const saveCodeToFile = async () => {
    if ("showSaveFilePicker" in window) {
      const handle = await (
        window as Window & {
          showSaveFilePicker: (args: {
            suggestedName: string;
            types: Array<{ description: string; accept: Record<string, string[]> }>;
          }) => Promise<{
            createWritable: () => Promise<{
              write: (content: string) => Promise<void>;
              close: () => Promise<void>;
            }>;
          }>;
        }
      ).showSaveFilePicker({
        suggestedName: "shadow-sprite-snippet.js",
        types: [
          {
            description: "JavaScript",
            accept: { "application/javascript": [".js"] },
          },
        ],
      });
      const writable = await handle.createWritable();
      await writable.write(code);
      await writable.close();
      return;
    }

    const blob = new Blob([code], { type: "application/javascript" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "shadow-sprite-snippet.js";
    link.click();
    URL.revokeObjectURL(url);
  };

  const loadLocalCode = () => {
    fileInputRef.current?.click();
  };

  const onFilePicked = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    const text = await file.text();
    setCode(text);
    event.target.value = "";
  };

  return (
    <section className="shadow-pane">
      <div className="shadow-preview">
        <div className="shadow-toolbar">
          <label>
            Sprite Sheet
            <select
              value={selectedSheetId ?? ""}
              onChange={(event) => setSelectedSheetId(event.target.value)}
            >
              {spriteSheetAssets.map((asset) => (
                <option key={asset.id} value={asset.id}>
                  {asset.id.slice(0, 8)} ({asset.width}x{asset.height})
                </option>
              ))}
            </select>
          </label>
          <label>
            FPS
            <input
              type="number"
              min={1}
              max={60}
              value={fps}
              onChange={(event) => setFps(Math.max(1, Number(event.target.value) || 1))}
            />
          </label>
          <button type="button" onClick={() => setIsPlaying((prev) => !prev)}>
            {isPlaying ? "Pause" : "Play"}
          </button>
        </div>
        <div className="shadow-canvas-wrap">
          <canvas ref={canvasRef} />
        </div>
      </div>
      <div className="shadow-editor">
        <div className="shadow-toolbar">
          <button type="button" onClick={() => setCode(latestCodeSnippet)}>
            Load Latest Snippet
          </button>
          <button type="button" onClick={saveCodeToFile}>
            Save .js
          </button>
          <button type="button" onClick={loadLocalCode}>
            Load .js
          </button>
        </div>
        <textarea
          value={code}
          onChange={(event) => setCode(event.target.value)}
          className="shadow-code"
          spellCheck={false}
        />
        <input
          type="file"
          ref={fileInputRef}
          onChange={onFilePicked}
          accept=".js,.txt"
          className="hidden-file-input"
        />
      </div>
    </section>
  );
}

export default ShadowPane;
