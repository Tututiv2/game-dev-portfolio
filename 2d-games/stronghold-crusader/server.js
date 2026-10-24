const http = require("http");
const fs = require("fs");
const path = require("path");
const WebSocket = require("ws");

const PORT = process.env.PORT || 3000;
const root = __dirname;

const mimeTypes = {
  ".html": "text/html",
  ".css": "text/css",
  ".js": "application/javascript",
  ".json": "application/json"
};

const server = http.createServer((req, res) => {
  const reqPath = req.url === "/" ? "/index.html" : req.url;
  const filePath = path.join(root, reqPath);

  if (!filePath.startsWith(root)) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end("Not found");
      return;
    }
    const ext = path.extname(filePath);
    const mime = mimeTypes[ext] || "application/octet-stream";
    res.writeHead(200, { "Content-Type": mime });
    res.end(data);
  });
});

const wss = new WebSocket.Server({ server });
const clients = new Set();

wss.on("connection", (socket) => {
  clients.add(socket);
  socket.send(JSON.stringify({ type: "welcome", message: "Connected to prototype sync server." }));

  socket.on("message", (raw) => {
    for (const client of clients) {
      if (client !== socket && client.readyState === WebSocket.OPEN) {
        client.send(raw.toString());
      }
    }
  });

  socket.on("close", () => {
    clients.delete(socket);
  });
});

server.listen(PORT, () => {
  console.log(`Server running on http://localhost:${PORT}`);
  console.log("WebSocket endpoint shares the same port.");
});
