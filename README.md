# ChatApp - Full Android Chat App with WebRTC Calls

## Overview

A complete Android chat application with:
- **Real-time messaging** via REST API + WebSocket
- **Voice & video calls** via WebRTC (free, peer-to-peer)
- **Offline support** via Room local database
- **Authentication** via JWT tokens

**Everything is 100% free and open source.**

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   ANDROID APP                        │
│                                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │ Auth UI  │  │ Chat UI  │  │   Call UI        │  │
│  │(Compose) │  │(Compose) │  │(Compose+WebRTC)  │  │
│  └────┬─────┘  └────┬─────┘  └────────┬─────────┘  │
│       │              │                  │            │
│  ┌────▼──────────────▼──────────────────▼────────┐  │
│  │              ViewModels (Hilt DI)              │  │
│  └────┬──────────────┬──────────────────┬────────┘  │
│       │              │                  │            │
│  ┌────▼─────┐  ┌─────▼──────┐  ┌───────▼────────┐  │
│  │Repository│  │  Room DB   │  │ WebRtcManager  │  │
│  │(REST+WS) │  │(local cache│  │(stream-webrtc) │  │
│  └────┬─────┘  └────────────┘  └───────┬────────┘  │
│       │                                 │            │
│  ┌────▼──────────────────────────────────▼────────┐  │
│  │    WebSocketManager (OkHttp WebSocket)          │  │
│  │    ChatApiService (Retrofit REST)               │  │
│  └────┬────────────────────────────────┬───────────┘  │
└───────┼────────────────────────────────┼───────────┘
        │ HTTP REST                       │ WebSocket
        ▼                                 ▼
┌─────────────────────────────────────────────────────┐
│                  BACKEND SERVER                      │
│            Node.js + Express + ws + SQLite           │
│                                                      │
│  REST API /api/*        WebSocket (ws://)            │
│  - /auth/login          - Chat events                │
│  - /auth/register       - WebRTC signaling relay     │
│  - /users/search        - User status updates        │
│  - /conversations                                    │
│  - /messages                                         │
└─────────────────────────────────────────────────────┘
```

---

## Free Technology Stack

### Android App
| Library | License | Purpose |
|---------|---------|---------|
| Jetpack Compose | Apache 2.0 | UI framework |
| Hilt | Apache 2.0 | Dependency injection |
| Retrofit | Apache 2.0 | REST API client |
| OkHttp | Apache 2.0 | HTTP + WebSocket client |
| Room | Apache 2.0 | Local SQLite database |
| stream-webrtc-android | Apache 2.0 | WebRTC wrapper |
| Kotlin Coroutines | Apache 2.0 | Async programming |
| DataStore | Apache 2.0 | Token persistence |
| Coil | Apache 2.0 | Image loading |

### Backend Server
| Library | License | Purpose |
|---------|---------|---------|
| Node.js | MIT | Runtime |
| Express | MIT | HTTP REST API |
| ws | MIT | WebSocket server |
| better-sqlite3 | MIT | Database |
| bcryptjs | MIT | Password hashing |
| jsonwebtoken | MIT | Auth tokens |
| uuid | MIT | ID generation |
| cors | MIT | Cross-origin requests |

---

## Project Structure

```
ChatApp/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/chatapp/
│           ├── ChatApplication.kt       # Hilt app class
│           ├── AppModule.kt             # DI providers
│           ├── data/
│           │   ├── api/
│           │   │   ├── ChatApiService.kt    # Retrofit interface
│           │   │   ├── WebSocketManager.kt  # WS connection + flows
│           │   │   └── Database.kt          # Room DB, DAOs
│           │   ├── model/
│           │   │   └── Models.kt            # All data classes
│           │   └── repository/
│           │       └── Repository.kt        # Data layer
│           ├── service/
│           │   ├── WebRtcManager.kt         # WebRTC peer connections
│           │   └── CallService.kt           # Foreground service
│           ├── viewmodel/
│           │   └── ViewModels.kt            # Auth, Chat, Call VMs
│           └── ui/
│               ├── MainActivity.kt          # Navigation host
│               ├── theme/Theme.kt           # Material 3 theme
│               ├── auth/AuthScreens.kt      # Login, Register
│               ├── conversations/           # Conversations list
│               ├── chat/ChatScreen.kt       # Message UI
│               ├── contacts/               # User search
│               └── call/CallScreen.kt       # WebRTC call UI
└── backend/
    ├── server.js                            # Full backend
    └── package.json
```

---

## Setup Instructions

### 1. Backend Setup

```bash
cd backend
npm install
node server.js
```

The server starts on port 3000 with:
- SQLite database (`chatapp.db`) auto-created
- REST API at `http://localhost:3000/api`
- WebSocket at `ws://localhost:3000`
- Health check at `http://localhost:3000/health`

**Environment variables:**
```bash
PORT=3000                          # HTTP port
JWT_SECRET=change-me-in-production # Secret key for JWT signing
```

### 2. Android App Setup

**a) Open in Android Studio**
- File → Open → select `ChatApp/` folder
- Let Gradle sync

**b) Configure backend URL**

In `app/build.gradle`, update the URL:
```groovy
// For Android Emulator (localhost):
buildConfigField "String", "BASE_URL", '"http://10.0.2.2:3000/"'
buildConfigField "String", "WS_URL", '"ws://10.0.2.2:3000"'

// For physical device (replace with your machine's IP):
buildConfigField "String", "BASE_URL", '"http://192.168.1.100:3000/"'
buildConfigField "String", "WS_URL", '"ws://192.168.1.100:3000"'
```

**c) Build and run**
- Connect device or start emulator
- Click Run (▶)

---

## How It Works

### Authentication Flow
```
1. User enters credentials
2. App calls POST /api/auth/login
3. Server verifies password with bcrypt
4. Server returns JWT token (valid 30 days)
5. App stores token in DataStore (persistent)
6. App connects WebSocket with token as query param
7. All subsequent API calls include "Authorization: Bearer <token>"
```

### Real-Time Messaging Flow
```
SENDING:
1. User types message → tap Send
2. Message inserted into Room DB with status=SENDING
3. POST /api/messages sent to backend
4. Backend saves to SQLite, broadcasts via WebSocket
5. Room DB updated: status=SENT

RECEIVING:
1. WebSocket receives "new_message" event
2. WebSocketManager emits via Flow
3. Repository inserts into Room DB
4. ChatViewModel observes Room DB Flow
5. UI updates automatically (recomposition)
```

### WebRTC Call Flow
```
CALLER (Alice):                    SERVER                    CALLEE (Bob)
     |                               |                            |
     |-- startCall() --------------→ |                            |
     |   createOffer() (SDP)         |                            |
     |-- CALL_OFFER via WS --------→ |-- relay to Bob ----------→ |
     |                               |                  incoming call UI shown
     |                               |                            |
     |                               |←--- CALL_ANSWER ----------|
     |←-- relay to Alice -----------|    createAnswer() (SDP)    |
     |   setRemoteDescription()      |                            |
     |                               |                            |
     |-- ICE_CANDIDATE via WS ----→ |-- relay -----------------→ |
     |←-- ICE_CANDIDATE via WS -----| ←--- ICE_CANDIDATE --------|
     |   addIceCandidate()           |                            |
     |                               |                            |
     |======== P2P MEDIA STREAM (audio/video, direct) ===========|
     |                (no server involved after connection)       |
```

### STUN/TURN Explanation
- **STUN** (Session Traversal Utilities for NAT): Helps devices discover their public IP. We use Google's FREE STUN servers (`stun.l.google.com:19302`).
- **TURN** (Traversal Using Relays around NAT): Needed when STUN fails (symmetric NAT, corporate firewalls). For production, options:
  - **Metered.ca**: 50GB/month free, add credentials in `WebRtcManager.kt`
  - **Self-hosted coturn**: Free, open source, run on any VPS (~$5/mo)

---

## API Reference

### Auth Endpoints

| Method | Endpoint | Body | Response |
|--------|----------|------|----------|
| POST | `/api/auth/register` | `{username, password, displayName}` | `{token, user}` |
| POST | `/api/auth/login` | `{username, password}` | `{token, user}` |
| GET | `/api/auth/me` | - | `{user}` |

### User Endpoints

| Method | Endpoint | Query/Body | Response |
|--------|----------|------------|----------|
| GET | `/api/users` | - | `{users[]}` |
| GET | `/api/users/search` | `?q=name` | `{users[]}` |

### Conversation Endpoints

| Method | Endpoint | Body | Response |
|--------|----------|------|----------|
| GET | `/api/conversations` | - | `{conversations[]}` |
| POST | `/api/conversations` | `{participantId}` | `{conversation}` |

### Message Endpoints

| Method | Endpoint | Query/Body | Response |
|--------|----------|------------|----------|
| GET | `/api/messages/:conversationId` | `?page=1&limit=50` | `{messages[], hasMore}` |
| POST | `/api/messages` | `{conversationId, content}` | `{message}` |
| PUT | `/api/messages/:conversationId/read` | - | `{success}` |

---

## WebSocket Events

### Client → Server
```json
// WebRTC signaling (relayed to target user)
{"type": "call_offer",     "to": "userId", "payload": "<SDP string>"}
{"type": "call_answer",    "to": "userId", "payload": "<SDP string>"}
{"type": "ice_candidate",  "to": "userId", "payload": "{sdpMid, sdpMLineIndex, candidate}"}
{"type": "call_reject",    "to": "userId", "payload": "{}"}
{"type": "call_end",       "to": "userId", "payload": "{}"}

// Chat events
{"type": "typing", "conversationId": "...", "isTyping": true}
```

### Server → Client
```json
// New message from another user
{"type": "new_message", "data": {/* Message object */}}

// User online/offline
{"type": "user_status", "userId": "...", "online": true}

// WebRTC signals (relayed from other user)
{"type": "call_offer", "from": "userId", "to": "userId", "payload": "..."}
```

---

## Database Schema (SQLite)

```sql
-- Users table
users (id, username, password, display_name, avatar, created_at)

-- Conversations table (1 row per unique pair of users)
conversations (id, participant1_id, participant2_id, last_message, last_message_time)

-- Messages table
messages (id, conversation_id, sender_id, content, type, status, timestamp)

-- Read receipts
message_reads (conversation_id, user_id, last_read_time)
```

---

## Adding TURN Servers (For Production)

Edit `WebRtcManager.kt`:
```kotlin
private val iceServers = listOf(
    // Free Google STUN
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),

    // Free Metered.ca TURN (50GB/month free, create account at metered.ca)
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
        .setUsername("openrelayproject")
        .setPassword("openrelayproject")
        .createIceServer(),
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
        .setUsername("openrelayproject")
        .setPassword("openrelayproject")
        .createIceServer(),
)
```

---

## Deploying Backend (Free Options)

### Railway.app (Free tier)
```bash
# Install Railway CLI
npm i -g @railway/cli
railway login
railway init
railway up
```

### Render.com (Free tier)
1. Push backend to GitHub
2. Create new Web Service on render.com
3. Set build command: `npm install`
4. Set start command: `node server.js`

### Self-hosted VPS
```bash
# On Ubuntu server
git clone <your-repo>
cd backend
npm install
npm install -g pm2
pm2 start server.js --name chatapp
pm2 startup  # Auto-start on reboot
```

---

## Troubleshooting

**"Connection refused" on emulator**
- Use `10.0.2.2` NOT `localhost` in Android emulator
- Make sure backend is running: `curl http://localhost:3000/health`

**WebRTC calls not connecting**
- Check camera/microphone permissions are granted
- Test on real devices or two emulators
- Add TURN servers if behind NAT (see above)

**WebSocket disconnects frequently**
- The app has auto-reconnect with exponential backoff (1s, 2s, 4s, 8s, 16s)
- Check server logs for errors

**Build errors**
- Run `./gradlew clean` then rebuild
- Make sure Kotlin version matches in `build.gradle`
- Android Studio: File → Invalidate Caches → Restart

---

## Extending the App

### Add Push Notifications (FCM - Free)
1. Create Firebase project at console.firebase.google.com (free)
2. Add `google-services.json` to `/app`
3. Add `com.google.gms:google-services` plugin
4. Store FCM tokens in backend, send via FCM API when user is offline

### Add File/Image Sharing
1. Add multipart upload endpoint to backend
2. Store files on server or use Cloudinary free tier (25GB)
3. Use `Coil` in Android to load images in chat bubbles

### Add Group Chats
1. Add `groups` table to SQLite
2. Add `group_members` table
3. Modify conversation model to support multiple participants
4. WebSocket broadcast to all group members

### Add End-to-End Encryption
1. Use libsodium or Tink (both free) for key generation
2. Exchange public keys via API
3. Encrypt message content client-side before sending
4. Decrypt on receipt - server never sees plaintext
