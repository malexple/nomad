# The app and how to try it on the phone (step 8.3b)

## What the app can do now (Russian interface)
- Chat list with the connection state (● подключено), unread counters, last message.
- **Профиль**: your name, the address of the node, your invitation link (copy / share), the cryptographic self-test.
- **+ Контакт**: paste an invitation link (found inside any text), optionally type a name.
- **Чат**: messages with time and the state "отправляется" / "не отправлено"; the "Имя" button renames the contact.
- **+ Группа**: choose contacts, give a name; you become the admin. Members see the group in their list automatically.
  The group name is a local label (the protocol does not carry names): a member without a name sees "Группа: names of members".
- The connection lives while the app is on the screen. The messages are kept in `chats.bin` (sealed with the same master key as
  the cryptographic state), the keys in `state.bin`.
- Not yet: notifications, background work (step 9), QR codes and scanning, media, member management, the node address from an invitation.

## Preparing the computer (once)
1. Windows firewall, PowerShell as administrator:
   `New-NetFirewallRule -DisplayName "Nomad dev node" -Direction Inbound -Protocol TCP -LocalPort 8080,8090,8091 -Action Allow -Profile Any`
2. Start the node with PostgreSQL (the prekey directory must survive restarts; with the in-memory store a restart of the node
   makes new chats impossible until the app data is cleared):
   `docker compose -f docker-compose.dev.yml up -d` and
   `./gradlew :server:bootRun --args="--nomad.store=postgres"` (WebSocket on 8090).
3. Check from the phone (same Wi-Fi, the router must not isolate clients): open `http://192.168.88.210:8080/v1/health` in the
   phone browser: you should see `{"status":"ok",...}`.

## A chat partner on the computer
`./gradlew :client-sim:bot --args="--url ws://localhost:8090/v1/ws --public-url ws://192.168.88.210:8090/v1/ws"`
It prints `INVITE: nomad://invite?...` and keeps its identity in the folder `bot-data` (do not commit it). Send the line to the
phone through any messenger. The bot answers every direct message with "Эхо: ...".
Optionally `--invite LINK` (the link from the phone's profile) makes the bot write first.

## Test plan on the phone
1. Clear the app data (the state format changed), install and start the app. After a few seconds: "● Подключено".
2. Профиль: type your name, save. "+ Контакт": paste the bot's link, add. Write "привет": the bot prints it and the answer
   "Эхо: привет" arrives. The message shows "отправляется" for a moment and then the time only.
3. Kill the app and start it again: the chat and the history are still there, the identifier in the profile is the same.
4. Stop the node (Ctrl+C), write a message ("отправляется"), start the node again: the message is delivered after the automatic
   reconnect (the envelope was in the outbox).
5. Install the app on the tablet, exchange invitations with the phone (Профиль, Поделиться, send it to yourself, "+ Контакт"),
   chat in both directions.
6. On the phone create a group of the tablet and the bot: the tablet sees the group; write from both; the bot is not a group
   participant for messages (it only echoes direct messages), that is expected.
7. In the profile run the diagnostics: all PASS.

## If it does not connect
- The state line says "Нет связи с узлом": check the address in the profile (default `ws://192.168.88.210:8090/v1/ws`), the
  firewall rule, that the computer's address has not changed (`ipconfig`), the browser check from step 3.
- A red strip at the top shows the last error; "Скрыть" closes it.
