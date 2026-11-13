# Telegram channel server

Kotlin/JVM MCP server (`src/main/kotlin/telegram/`) built on
[ktgbotapi](https://github.com/InsanusMokrassar/ktgbotapi). `.mcp.json` runs
`start.sh`, which builds `build/telegram-channel.jar` with the Gradle wrapper
when it is missing or older than the sources, then execs `java -jar`.

| File | Role |
| --- | --- |
| `Main.kt` | entry point, model instructions, long polling, poller lock, shutdown and orphan watchdog |
| `Mcp.kt` | newline-delimited JSON-RPC over stdio (initialize, tools, `claude/channel` notifications) |
| `Inbound.kt` | typed update dispatch: access gate, commands, permission relay, batching, guest queries |
| `Tools.kt` | `reply`, `react`, `download_attachment`, `edit_message` |
| `Guests.kt` | guest-query state and answers; typing keep-alive |
| `Access.kt` | `access.json`, `.env`, topics/commands config |
| `Format.kt` | parse modes, chunking, rich-block plain-text fallback |
| `Tg.kt` | ktgbotapi bot instance and small helpers |

## Behaviour

- **Access** — `access.json` (pairing / allowlist / disabled, groups with
  `requireMention` and per-group `allowFrom`, `mentionPatterns`, `ackReaction`,
  `replyToMode`, `textChunkLimit`, `chunkMode`), re-read on every use.
  `TELEGRAM_ACCESS_MODE=static` snapshots it at boot. Outbound tools only target
  chats the inbound gate would deliver from, and never send files from the state
  dir (except `inbox/`).
- **Poller lock** — Telegram allows one `getUpdates` consumer per token.
  `bot.pid` names the holder, refreshed every 30 s; a new instance takes over
  only from a dead, unrelated or silent (90 s) holder, otherwise stands by and
  still serves outbound tools. A persistent 409 (8 in a row) exits and tells the
  owner; 5 consecutive failures warn.
- **Delivery** — if a notification can't reach the session, the sender is told
  to resend instead of being left with an ack and a typing indicator.
- **Batching** — inbound messages from one sender in one chat/topic are held for
  1 s (each new part restarts the window) and delivered as one notification:
  the sender's own text first, then each forwarded part under
  `[forwarded from <name>]`. `message_ids` lists every message covered; extra
  photos/files appear as `image_path_2`, `attachment_file_id_2`, …
- **Topics** — `thread_id` / `topic` / `topic_instructions` in the meta; replies,
  attachments and typing go back to the originating topic. Per-topic config in
  `$CLAUDE_CONFIG_DIR/telegram-topics.json` (`"<chat>:<thread>"`, `"<chat>:*"`,
  `"<chat>"` → `{name, instructions, ignore}`), re-read per message; `ignore`
  marks a send-only topic.
- **Formats** — `format: html | markdown | markdownv2`; a markup rejection is
  re-sent as plain text.
- **Typing** — refreshed every 4 s for the whole turn (mid-turn replies don't
  stop it) until the Stop hook writes `$CLAUDE_CONFIG_DIR/telegram-turn-done`,
  `/cancel`, or a 30-minute cap.
- **Commands** — the menu lists only `$CLAUDE_CONFIG_DIR/telegram-commands.json`;
  `/start`, `/help`, `/status` still answer if typed. `/cancel` presses Escape
  in tmux session `$CLAUDE_TMUX_SESSION` (default `claude`).
- **Permission relay** — `permission_request` goes to every allowlisted DM with
  See more / Allow / Deny buttons; `yes abcde` / `no abcde` replies work too.
- **Rich messages** — `reply.rich` blocks are decoded into ktgbotapi's
  `InputRichBlock` types and sent with `sendRichMessage`; anything undecodable or
  rejected is flattened to plain text.
- **Guest queries** (Bot API 10.0) — an @mention from an allowlisted user in a
  chat the bot isn't in arrives with `guest="true"` and a synthetic
  `guest:<query id>` chat_id (1 h TTL). `reply` answers once via
  `answerGuestQuery`; further replies and `edit_message` edit that answer.
- **Session Mini App** — watch-only view of the Claude Code tmux pane (with
  scrollback), streamed over a WebSocket to an xterm.js page opened from the
  bot's "Session" menu button. No input path. The socket's first message must be
  Telegram's signed `initData` (valid hash, under a day old, user in
  `allowFrom`). Config: `TELEGRAM_MINIAPP_PORT` (default 8790, 0 disables),
  `TELEGRAM_MINIAPP_HOST`, `TELEGRAM_MINIAPP_URL` (public https URL — the menu
  button is set only when present), `TELEGRAM_MINIAPP_HISTORY` (default 3000
  lines). Runs only in the instance holding the poller lock.
