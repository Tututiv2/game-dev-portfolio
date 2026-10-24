import { useMemo, useState } from "react";

type ChatRole = "user" | "assistant";

type ChatMessage = {
  role: ChatRole;
  content: string;
};

type FileEntry = {
  name: string;
  content: string;
};

const INITIAL_FILES: FileEntry[] = [
  {
    name: "index.html",
    content:
      "<!doctype html>\n<html>\n  <head><meta charset='utf-8'><title>Shadow App</title></head>\n  <body>\n    <h1>Shadow App</h1>\n    <p>Build your game directly here.</p>\n  </body>\n</html>\n",
  },
  {
    name: "game.js",
    content:
      "const canvas = document.createElement('canvas');\ncanvas.width = 640;\ncanvas.height = 360;\ndocument.body.appendChild(canvas);\nconst ctx = canvas.getContext('2d');\nctx.fillStyle = '#111827';\nctx.fillRect(0, 0, canvas.width, canvas.height);\nctx.fillStyle = '#10b981';\nctx.fillRect(40, 40, 120, 80);\n",
  },
];

function App() {
  const [projectName, setProjectName] = useState("Shadow Project");
  const [files, setFiles] = useState<FileEntry[]>(INITIAL_FILES);
  const [activeFileName, setActiveFileName] = useState(INITIAL_FILES[0].name);
  const [chatInput, setChatInput] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      role: "assistant",
      content:
        "Shadow is ready. Tell me what game feature you want to build and I will draft code.",
    },
  ]);
  const [persona, setPersona] = useState("Builder");

  const activeFile = useMemo(
    () => files.find((file) => file.name === activeFileName) ?? files[0],
    [files, activeFileName],
  );

  const previewHtml = useMemo(() => {
    const html = files.find((file) => file.name.endsWith(".html"))?.content ?? "";
    const js = files.find((file) => file.name.endsWith(".js"))?.content ?? "";
    return `${html}\n<script>${js}</script>`;
  }, [files]);

  const updateActiveFile = (nextContent: string) => {
    setFiles((prev) =>
      prev.map((file) =>
        file.name === activeFile.name ? { ...file, content: nextContent } : file,
      ),
    );
  };

  const sendMessage = () => {
    const trimmed = chatInput.trim();
    if (!trimmed) {
      return;
    }
    setMessages((prev) => [
      ...prev,
      { role: "user", content: trimmed },
      {
        role: "assistant",
        content:
          `(${persona}) Got it. I can generate code for this next. If you want, I can patch ${activeFile.name} first.`,
      },
    ]);
    setChatInput("");
  };

  const saveActiveFile = async () => {
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
        suggestedName: activeFile.name,
        types: [
          {
            description: "Text files",
            accept: { "text/plain": [".txt", ".html", ".js", ".css", ".json"] },
          },
        ],
      });
      const writable = await handle.createWritable();
      await writable.write(activeFile.content);
      await writable.close();
      return;
    }

    const blob = new Blob([activeFile.content], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = activeFile.name;
    link.click();
    URL.revokeObjectURL(url);
  };

  return (
    <main className="shadow-app">
      <header className="topbar">
        <div>
          <h1>Shadow App</h1>
          <p>Standalone AI coding workspace (separate from Shadow Gen)</p>
        </div>
        <input
          value={projectName}
          onChange={(event) => setProjectName(event.target.value)}
          className="project-name"
        />
      </header>

      <section className="layout">
        <aside className="sidebar">
          <h2>Files</h2>
          <div className="file-list">
            {files.map((file) => (
              <button
                key={file.name}
                type="button"
                className={file.name === activeFile.name ? "active" : ""}
                onClick={() => setActiveFileName(file.name)}
              >
                {file.name}
              </button>
            ))}
          </div>
        </aside>

        <section className="workspace">
          <div className="workspace-pane">
            <div className="pane-header">
              <strong>{activeFile.name}</strong>
              <button type="button" onClick={saveActiveFile}>
                Save File
              </button>
            </div>
            <textarea
              className="editor"
              value={activeFile.content}
              onChange={(event) => updateActiveFile(event.target.value)}
              spellCheck={false}
            />
          </div>

          <div className="workspace-pane">
            <div className="pane-header">
              <strong>Live Preview</strong>
            </div>
            <iframe title="shadow-preview" srcDoc={previewHtml} className="preview" />
          </div>
        </section>

        <aside className="chat-panel">
          <div className="pane-header">
            <strong>Shadow AI</strong>
            <select value={persona} onChange={(event) => setPersona(event.target.value)}>
              <option>Builder</option>
              <option>Architect</option>
              <option>Debugger</option>
            </select>
          </div>
          <div className="chat-feed">
            {messages.map((message, index) => (
              <div key={`${message.role}-${index}`} className={`bubble ${message.role}`}>
                {message.content}
              </div>
            ))}
          </div>
          <div className="chat-input">
            <textarea
              rows={3}
              value={chatInput}
              onChange={(event) => setChatInput(event.target.value)}
              placeholder="Ask Shadow AI to generate code..."
            />
            <button type="button" onClick={sendMessage}>
              Send
            </button>
          </div>
        </aside>
      </section>
    </main>
  );
}

export default App;
