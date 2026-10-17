const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');
const port = Number(process.env.PORT || 8080);
const server = http.createServer((req,res) => {
  res.writeHead(200, { 'Content-Type':'text/plain' }); res.end('UltraStream signaling online');
});
const wss = new WebSocketServer({ server, maxPayload: 1024 * 1024 });
const rooms = new Map();
function send(ws, data) { if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data)); }
function notify(room, excluded, data) {
  for (const peer of rooms.get(room) || []) if (peer !== excluded) send(peer, data);
}
wss.on('connection', ws => {
  ws.on('message', bytes => {
    let msg; try { msg = JSON.parse(bytes.toString()); } catch { return; }
    if (msg.type === 'join') {
      if (typeof msg.room !== 'string' || !/^[a-zA-Z0-9_-]{4,64}$/.test(msg.room)) return ws.close(1008, 'Invalid room');
      if (!['sender','viewer'].includes(msg.role)) return ws.close(1008, 'Invalid role');
      const peers = rooms.get(msg.room) || new Set();
      if ([...peers].some(p => p.role === msg.role)) return ws.close(1008, 'Role already occupied');
      ws.room = msg.room; ws.role = msg.role; peers.add(ws); rooms.set(ws.room, peers);
      if (ws.role === 'viewer') notify(ws.room, ws, {type:'viewer-ready'});
      if (ws.role === 'sender' && [...peers].some(p => p.role === 'viewer')) send(ws, {type:'viewer-ready'});
      return;
    }
    if (!ws.room || msg.room !== ws.room) return;
    if (!['offer','answer','candidate'].includes(msg.type)) return;
    if ((msg.type === 'offer' && ws.role !== 'sender') || (msg.type === 'answer' && ws.role !== 'viewer')) return;
    notify(ws.room, ws, msg);
  });
  ws.on('close', () => {
    if (!ws.room) return;
    const peers = rooms.get(ws.room);
    peers?.delete(ws);
    notify(ws.room, ws, {type:ws.role === 'viewer' ? 'viewer-left' : 'sender-left'});
    if (!peers?.size) rooms.delete(ws.room);
  });
});
server.listen(port, '0.0.0.0', () => console.log('Signaling port', port));
