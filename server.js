/**
 * ChatApp Backend Server
 * ======================
 * Stack: Node.js + Express + WebSocket (ws) + SQLite (better-sqlite3)
 *
 * All FREE, open-source dependencies:
 *   - express:          MIT license - HTTP REST API framework
 *   - ws:               MIT license - WebSocket server (real-time + WebRTC signaling)
 *   - better-sqlite3:   MIT license - fast, synchronous SQLite
 *   - bcryptjs:         MIT license - password hashing
 *   - jsonwebtoken:     MIT license - JWT auth tokens
 *   - uuid:             MIT license - unique IDs
 *   - cors:             MIT license - cross-origin support
 *
 * Install: npm install
 * Run:     node server.js
 * Default port: 3000
 *
 * For Android emulator, connect via: http://10.0.2.2:3000
 * For real device, use your machine's local IP: http://192.168.x.x:3000
 */

const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const Database = require('better-sqlite3');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const cors = require('cors');

// ==================== CONFIG ====================
const PORT = process.env.PORT || 3000;
const JWT_SECRET = process.env.JWT_SECRET || 'your-super-secret-key-change-in-production';
const JWT_EXPIRES = '30d';

// ==================== DATABASE SETUP ====================
const db = new Database('./chatapp.db');

// Enable WAL mode for better concurrent performance
db.pragma('journal_mode = WAL');

// Create tables
db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    password TEXT NOT NULL,
    display_name TEXT NOT NULL,
    avatar TEXT,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
  );

  CREATE TABLE IF NOT EXISTS conversations (
    id TEXT PRIMARY KEY,
    participant1_id TEXT NOT NULL,
    participant2_id TEXT NOT NULL,
    last_message TEXT DEFAULT '',
    last_message_time INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    FOREIGN KEY (participant1_id) REFERENCES users(id),
    FOREIGN KEY (participant2_id) REFERENCES users(id),
    UNIQUE(participant1_id, participant2_id)
  );

  CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    content TEXT NOT NULL,
    type TEXT DEFAULT 'TEXT',
    status TEXT DEFAULT 'SENT',
    timestamp INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    FOREIGN KEY (conversation_id) REFERENCES conversations(id),
    FOREIGN KEY (sender_id) REFERENCES users(id)
  );

  CREATE TABLE IF NOT EXISTS message_reads (
    conversation_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    last_read_time INTEGER DEFAULT 0,
    PRIMARY KEY (conversation_id, user_id)
  );

  CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, timestamp DESC);
  CREATE INDEX IF NOT EXISTS idx_conv_participants ON conversations(participant1_id, participant2_id);
`);

console.log('✅ Database initialized');

// ==================== EXPRESS APP ====================
const app = express();
const server = http.createServer(app);

app.use(cors());
app.use(express.json());

// ==================== AUTH MIDDLEWARE ====================
function requireAuth(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader?.startsWith('Bearer ')) {
    return res.status(401).json({ success: false, message: 'No token provided' });
  }
  try {
    const token = authHeader.split(' ')[1];
    const decoded = jwt.verify(token, JWT_SECRET);
    req.userId = decoded.userId;
    next();
  } catch {
    res.status(401).json({ success: false, message: 'Invalid token' });
  }
}

// ==================== AUTH ROUTES ====================

// POST /api/auth/register
app.post('/api/auth/register', (req, res) => {
  const { username, password, displayName } = req.body;

  if (!username || !password || !displayName) {
    return res.status(400).json({ success: false, message: 'All fields are required' });
  }
  if (username.length < 3) {
    return res.status(400).json({ success: false, message: 'Username must be at least 3 characters' });
  }
  if (password.length < 6) {
    return res.status(400).json({ success: false, message: 'Password must be at least 6 characters' });
  }

  try {
    const hashedPassword = bcrypt.hashSync(password, 10);
    const userId = uuidv4();

    db.prepare(`
      INSERT INTO users (id, username, password, display_name)
      VALUES (?, ?, ?, ?)
    `).run(userId, username.toLowerCase(), hashedPassword, displayName);

    const token = jwt.sign({ userId }, JWT_SECRET, { expiresIn: JWT_EXPIRES });
    res.status(201).json({
      success: true,
      data: {
        token,
        user: { id: userId, username, displayName, online: true }
      }
    });
  } catch (e) {
    if (e.message.includes('UNIQUE')) {
      return res.status(409).json({ success: false, message: 'Username already taken' });
    }
    res.status(500).json({ success: false, message: 'Server error' });
  }
});

// POST /api/auth/login
app.post('/api/auth/login', (req, res) => {
  const { username, password } = req.body;

  const user = db.prepare('SELECT * FROM users WHERE username = ?').get(username?.toLowerCase());
  if (!user || !bcrypt.compareSync(password, user.password)) {
    return res.status(401).json({ success: false, message: 'Invalid credentials' });
  }

  const token = jwt.sign({ userId: user.id }, JWT_SECRET, { expiresIn: JWT_EXPIRES });
  res.json({
    success: true,
    data: {
      token,
      user: {
        id: user.id,
        username: user.username,
        displayName: user.display_name,
        avatar: user.avatar,
        online: true
      }
    }
  });
});

// GET /api/auth/me
app.get('/api/auth/me', requireAuth, (req, res) => {
  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(req.userId);
  if (!user) return res.status(404).json({ success: false, message: 'User not found' });
  res.json({
    success: true,
    data: {
      id: user.id,
      username: user.username,
      displayName: user.display_name,
      avatar: user.avatar,
      online: onlineUsers.has(req.userId)
    }
  });
});

// ==================== USER ROUTES ====================

// GET /api/users - all users except self
app.get('/api/users', requireAuth, (req, res) => {
  const users = db.prepare('SELECT id, username, display_name, avatar FROM users WHERE id != ?')
    .all(req.userId);
  res.json({
    success: true,
    data: {
      users: users.map(u => ({
        id: u.id,
        username: u.username,
        displayName: u.display_name,
        avatar: u.avatar,
        online: onlineUsers.has(u.id)
      }))
    }
  });
});

// GET /api/users/search?q=query
app.get('/api/users/search', requireAuth, (req, res) => {
  const { q } = req.query;
  if (!q || q.length < 2) {
    return res.json({ success: true, data: { users: [] } });
  }
  const users = db.prepare(`
    SELECT id, username, display_name, avatar 
    FROM users 
    WHERE id != ? AND (username LIKE ? OR display_name LIKE ?)
    LIMIT 20
  `).all(req.userId, `%${q}%`, `%${q}%`);

  res.json({
    success: true,
    data: {
      users: users.map(u => ({
        id: u.id,
        username: u.username,
        displayName: u.display_name,
        avatar: u.avatar,
        online: onlineUsers.has(u.id)
      }))
    }
  });
});

// ==================== CONVERSATION ROUTES ====================

// GET /api/conversations
app.get('/api/conversations', requireAuth, (req, res) => {
  const convs = db.prepare(`
    SELECT c.*, 
      u1.display_name as p1_name, u1.avatar as p1_avatar,
      u2.display_name as p2_name, u2.avatar as p2_avatar
    FROM conversations c
    JOIN users u1 ON c.participant1_id = u1.id
    JOIN users u2 ON c.participant2_id = u2.id
    WHERE c.participant1_id = ? OR c.participant2_id = ?
    ORDER BY c.last_message_time DESC
  `).all(req.userId, req.userId);

  const result = convs.map(c => {
    const isP1 = c.participant1_id === req.userId;
    const participantId = isP1 ? c.participant2_id : c.participant1_id;
    const participantName = isP1 ? c.p2_name : c.p1_name;
    const participantAvatar = isP1 ? c.p2_avatar : c.p1_avatar;

    // Unread count
    const lastRead = db.prepare(`
      SELECT last_read_time FROM message_reads 
      WHERE conversation_id = ? AND user_id = ?
    `).get(c.id, req.userId);

    const unreadCount = db.prepare(`
      SELECT COUNT(*) as count FROM messages 
      WHERE conversation_id = ? AND sender_id != ? AND timestamp > ?
    `).get(c.id, req.userId, lastRead?.last_read_time || 0).count;

    return {
      id: c.id,
      participantId,
      participantName,
      participantAvatar,
      lastMessage: c.last_message,
      lastMessageTime: c.last_message_time,
      unreadCount,
      isOnline: onlineUsers.has(participantId)
    };
  });

  res.json({ success: true, data: result });
});

// POST /api/conversations - get or create
app.post('/api/conversations', requireAuth, (req, res) => {
  const { participantId } = req.body;
  if (!participantId) {
    return res.status(400).json({ success: false, message: 'participantId required' });
  }

  // Order IDs consistently so we don't create duplicates
  const [p1, p2] = [req.userId, participantId].sort();

  let conv = db.prepare('SELECT * FROM conversations WHERE participant1_id = ? AND participant2_id = ?')
    .get(p1, p2);

  if (!conv) {
    const convId = uuidv4();
    db.prepare('INSERT INTO conversations (id, participant1_id, participant2_id) VALUES (?, ?, ?)')
      .run(convId, p1, p2);
    conv = db.prepare('SELECT * FROM conversations WHERE id = ?').get(convId);
  }

  const participant = db.prepare('SELECT * FROM users WHERE id = ?').get(participantId);
  res.json({
    success: true,
    data: {
      id: conv.id,
      participantId,
      participantName: participant.display_name,
      participantAvatar: participant.avatar,
      lastMessage: conv.last_message,
      lastMessageTime: conv.last_message_time,
      unreadCount: 0,
      isOnline: onlineUsers.has(participantId)
    }
  });
});

// ==================== MESSAGE ROUTES ====================

// GET /api/messages/:conversationId
app.get('/api/messages/:conversationId', requireAuth, (req, res) => {
  const { conversationId } = req.params;
  const page = parseInt(req.query.page) || 1;
  const limit = parseInt(req.query.limit) || 50;
  const offset = (page - 1) * limit;

  // Verify user is participant
  const conv = db.prepare(`
    SELECT * FROM conversations WHERE id = ? 
    AND (participant1_id = ? OR participant2_id = ?)
  `).get(conversationId, req.userId, req.userId);

  if (!conv) return res.status(403).json({ success: false, message: 'Not authorized' });

  const messages = db.prepare(`
    SELECT m.*, u.display_name as sender_name
    FROM messages m
    JOIN users u ON m.sender_id = u.id
    WHERE m.conversation_id = ?
    ORDER BY m.timestamp DESC
    LIMIT ? OFFSET ?
  `).all(conversationId, limit, offset);

  const total = db.prepare('SELECT COUNT(*) as count FROM messages WHERE conversation_id = ?')
    .get(conversationId).count;

  res.json({
    success: true,
    data: {
      messages: messages.map(m => ({
        id: m.id,
        conversationId: m.conversation_id,
        senderId: m.sender_id,
        senderName: m.sender_name,
        content: m.content,
        type: m.type,
        status: m.status,
        timestamp: m.timestamp
      })),
      hasMore: offset + messages.length < total,
      page
    }
  });
});

// POST /api/messages
app.post('/api/messages', requireAuth, (req, res) => {
  const { conversationId, content, type = 'TEXT' } = req.body;

  if (!conversationId || !content) {
    return res.status(400).json({ success: false, message: 'conversationId and content required' });
  }

  // Verify participant
  const conv = db.prepare(`
    SELECT * FROM conversations WHERE id = ? 
    AND (participant1_id = ? OR participant2_id = ?)
  `).get(conversationId, req.userId, req.userId);

  if (!conv) return res.status(403).json({ success: false, message: 'Not authorized' });

  const msgId = uuidv4();
  const timestamp = Date.now();

  db.prepare(`
    INSERT INTO messages (id, conversation_id, sender_id, content, type, status, timestamp)
    VALUES (?, ?, ?, ?, ?, 'SENT', ?)
  `).run(msgId, conversationId, req.userId, content, type, timestamp);

  // Update conversation last message
  db.prepare(`
    UPDATE conversations SET last_message = ?, last_message_time = ? WHERE id = ?
  `).run(content, timestamp, conversationId);

  const sender = db.prepare('SELECT display_name FROM users WHERE id = ?').get(req.userId);
  const message = {
    id: msgId,
    conversationId,
    senderId: req.userId,
    senderName: sender.display_name,
    content,
    type,
    status: 'SENT',
    timestamp
  };

  // Broadcast to the recipient via WebSocket if online
  const recipientId = conv.participant1_id === req.userId
    ? conv.participant2_id
    : conv.participant1_id;

  broadcastToUser(recipientId, {
    type: 'new_message',
    data: message
  });

  // Also notify sender's other sessions
  broadcastToUser(req.userId, {
    type: 'new_message',
    data: message
  }, true);  // exclude sender's current connection

  res.status(201).json({ success: true, data: message });
});

// PUT /api/messages/:conversationId/read
app.put('/api/messages/:conversationId/read', requireAuth, (req, res) => {
  const { conversationId } = req.params;
  const now = Date.now();

  db.prepare(`
    INSERT OR REPLACE INTO message_reads (conversation_id, user_id, last_read_time)
    VALUES (?, ?, ?)
  `).run(conversationId, req.userId, now);

  // Update message statuses to READ
  db.prepare(`
    UPDATE messages SET status = 'READ' 
    WHERE conversation_id = ? AND sender_id != ? AND status != 'READ'
  `).run(conversationId, req.userId);

  res.json({ success: true });
});

// ==================== WEBSOCKET SERVER ====================

/**
 * WebSocket Server
 * Handles two types of messages:
 *   1. Chat events: typing indicators, message delivery confirmations
 *   2. WebRTC signaling: call_offer, call_answer, ice_candidate, call_end
 *
 * Authentication: JWT token in query param ?token=<jwt>
 * Example: ws://localhost:3000?token=eyJ...
 */
const wss = new WebSocket.Server({ server });

// Map of userId -> Set of WebSocket connections (user can have multiple sessions)
const onlineUsers = new Map(); // userId -> Set<WebSocket>

wss.on('connection', (ws, req) => {
  // Authenticate via JWT in query string
  const url = new URL(req.url, 'ws://localhost');
  const token = url.searchParams.get('token');

  let userId;
  try {
    const decoded = jwt.verify(token, JWT_SECRET);
    userId = decoded.userId;
  } catch {
    ws.close(1008, 'Invalid token');
    return;
  }

  // Track online status
  if (!onlineUsers.has(userId)) {
    onlineUsers.set(userId, new Set());
  }
  onlineUsers.get(userId).add(ws);
  ws.userId = userId;

  console.log(`✅ User ${userId} connected (${onlineUsers.get(userId).size} connections)`);

  // Notify contacts that this user is online
  broadcastUserStatus(userId, true);

  // Handle incoming messages
  ws.on('message', (data) => {
    try {
      const message = JSON.parse(data);
      handleWebSocketMessage(ws, userId, message);
    } catch (e) {
      console.error('Failed to parse WS message:', e.message);
    }
  });

  ws.on('close', () => {
    onlineUsers.get(userId)?.delete(ws);
    if (onlineUsers.get(userId)?.size === 0) {
      onlineUsers.delete(userId);
      broadcastUserStatus(userId, false);
      console.log(`👋 User ${userId} disconnected`);
    }
  });

  ws.on('error', (err) => {
    console.error(`WS error for ${userId}:`, err.message);
  });
});

/**
 * Route incoming WebSocket messages.
 */
function handleWebSocketMessage(ws, fromUserId, message) {
  const { type, to } = message;

  switch (type) {
    // ==================== WEBRTC SIGNALING ====================
    // These messages are forwarded to the target user.
    // The server does NOT interpret them - just routes them.

    case 'call_offer':
    case 'call_answer':
    case 'ice_candidate':
    case 'call_reject':
    case 'call_end':
    case 'call_busy':
      if (to) {
        broadcastToUser(to, {
          type,
          from: fromUserId,
          to,
          payload: message.payload
        });
        console.log(`📞 Relayed ${type} from ${fromUserId} to ${to}`);
      }
      break;

    // ==================== CHAT EVENTS ====================

    case 'typing':
      broadcastToUser(message.to || message.conversationId, {
        type: 'typing',
        userId: fromUserId,
        conversationId: message.conversationId,
        isTyping: message.isTyping
      });
      break;

    default:
      console.log(`Unknown WS message type: ${type}`);
  }
}

/**
 * Send a JSON message to all connections of a specific user.
 * @param userId Target user ID
 * @param payload Object to send (will be JSON.stringified)
 * @param excludeSelf Skip connections matching the sender
 */
function broadcastToUser(userId, payload, excludeSelf = false) {
  const connections = onlineUsers.get(userId);
  if (!connections) return;

  const data = JSON.stringify(payload);
  connections.forEach(ws => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(data);
    }
  });
}

/**
 * Broadcast user online/offline status to all their conversation partners.
 */
function broadcastUserStatus(userId, online) {
  // Find all conversation partners
  const partners = db.prepare(`
    SELECT CASE 
      WHEN participant1_id = ? THEN participant2_id 
      ELSE participant1_id 
    END as partner_id
    FROM conversations
    WHERE participant1_id = ? OR participant2_id = ?
  `).all(userId, userId, userId);

  const statusMsg = JSON.stringify({
    type: 'user_status',
    userId,
    online
  });

  partners.forEach(({ partner_id }) => {
    broadcastToUser(partner_id, { type: 'user_status', userId, online });
  });
}

// ==================== HEALTH CHECK ====================
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    users: onlineUsers.size,
    connections: wss.clients.size,
    timestamp: new Date().toISOString()
  });
});

// ==================== START SERVER ====================
server.listen(PORT, () => {
  console.log(`🚀 ChatApp server running on port ${PORT}`);
  console.log(`   REST API: http://localhost:${PORT}/api`);
  console.log(`   WebSocket: ws://localhost:${PORT}`);
  console.log(`   Health: http://localhost:${PORT}/health`);
  console.log('');
  console.log('📱 Android emulator connections:');
  console.log(`   HTTP: http://10.0.2.2:${PORT}`);
  console.log(`   WS:   ws://10.0.2.2:${PORT}`);
});
