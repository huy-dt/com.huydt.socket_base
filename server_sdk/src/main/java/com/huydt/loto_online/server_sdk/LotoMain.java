package com.huydt.loto_online.server_sdk;

import com.huydt.loto_online.server_sdk.callback.LotoServerCallback;
import com.huydt.loto_online.server_sdk.core.*;
import com.huydt.loto_online.server_sdk.model.LotoPage;
import com.huydt.loto_online.server_sdk.model.LotoPlayer;
import com.huydt.loto_online.server_sdk.model.LotoRoom;
import com.huydt.socket_base.server.core.ServerConfig;
import com.huydt.socket_base.server.core.TransportMode;
import com.huydt.socket_base.server.event.EventType;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.model.Room;

import java.util.Collection;
import java.util.List;
import java.util.Scanner;

/**
 * Standalone entry point for Loto Online Server.
 *
 * <h3>Usage</h3>
 * <pre>
 * java -jar loto_server.jar [options]
 *
 * Network:
 *   --tcp                     TCP only
 *   --ws                      WebSocket only  (default)
 *   --both                    TCP + WebSocket
 *   --port      &lt;n&gt;           TCP port              (default: 9000)
 *   --ws-port   &lt;n&gt;           WebSocket port        (default: 9001)
 *   --admin-token &lt;secret&gt;    Admin token           (default: auto UUID)
 *   --reconnect-ms &lt;n&gt;        Reconnect window ms   (default: 30000)
 *
 * Game:
 *   --price         &lt;n&gt;       Price per page        (default: 10000)
 *   --balance       &lt;n&gt;       Initial player balance(default: 0)
 *   --draw-ms       &lt;n&gt;       Draw interval ms      (default: 5000)
 *   --max-pages     &lt;n&gt;       Max pages per buy     (default: 10)
 *   --vote-pct      &lt;n&gt;       Vote threshold %      (default: 51)
 *   --min-players   &lt;n&gt;       Min players to start  (default: 1)
 *   --auto-verify             Auto verify win claims
 *   --auto-reset-ms &lt;n&gt;       Auto reset delay ms   (default: 0 = off)
 *   --auto-start-ms &lt;n&gt;       Auto start delay ms   (default: 0 = off)
 *   --max-players   &lt;n&gt;       Max players per room  (default: 0 = unlimited)
 *   --max-rooms     &lt;n&gt;       Max rooms             (default: 0 = unlimited)
 *   --no-console              Disable interactive console
 *   --show-info               Print full config on startup
 *   --help
 * </pre>
 *
 * <h3>Console Commands</h3>
 * <pre>
 *   help / info / token
 *
 *   rooms                                     List all rooms
 *   room-add &lt;name&gt; [maxPlayers]              Create room
 *   room-remove &lt;roomId&gt;                      Close room
 *   room-info &lt;roomId&gt;                        Room details + game state
 *
 *   players [roomId]                          List players (all or in room)
 *   kick &lt;roomId&gt; &lt;playerId&gt; [reason]
 *   ban  &lt;roomId&gt; &lt;playerId&gt; [reason]
 *   unban &lt;roomId&gt; &lt;name&gt;
 *   ban-ip &lt;roomId&gt; &lt;ip&gt;
 *   unban-ip &lt;roomId&gt; &lt;ip&gt;
 *   topup &lt;roomId&gt; &lt;playerId&gt; &lt;amount&gt; [note]
 *
 *   game-start  &lt;roomId&gt;
 *   game-end    &lt;roomId&gt; [reason]
 *   game-cancel &lt;roomId&gt; [reason]
 *   game-pause  &lt;roomId&gt;
 *   game-resume &lt;roomId&gt;
 *   game-reset  &lt;roomId&gt;
 *   confirm-win &lt;roomId&gt; &lt;playerId&gt; &lt;pageId&gt;
 *   reject-win  &lt;roomId&gt; &lt;playerId&gt; &lt;pageId&gt;
 *
 *   set-draw-ms     &lt;roomId&gt; &lt;ms&gt;
 *   set-price       &lt;roomId&gt; &lt;price&gt;
 *   set-auto-reset  &lt;roomId&gt; &lt;ms&gt;
 *   set-auto-start  &lt;roomId&gt; &lt;ms&gt;
 *
 *   bot-add    &lt;roomId&gt; &lt;name&gt; &lt;balance&gt; &lt;maxPages&gt;
 *   bot-remove &lt;roomId&gt; &lt;name&gt;
 *   bots       &lt;roomId&gt;
 *
 *   stop / exit / quit
 * </pre>
 */
public class LotoMain {

    private static LotoServer server;
    private static boolean    showConsole = true;

    public static void main(String[] args) {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) { printHelp(); return; }

        boolean showInfo = hasFlag(args, "--show-info");
        showConsole      = !hasFlag(args, "--no-console");

        ServerConfig baseConfig = parseBaseArgs(args);
        LotoConfig   lotoConfig = parseLotoArgs(args);

        server = new LotoServer.Builder()
                .baseConfig(baseConfig)
                .lotoConfig(lotoConfig)
                .callback(makeCallback())
                .build();

        // Cache the typed dispatcher for console commands
        cachedDispatcher = server.getLotoDispatcher();

        // Auto-create a default room on startup
        server.getAdmin().createRoom("default", "Phòng mặc định",
                baseConfig.maxPlayersPerRoom);

        registerEventListeners();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Main] Đang tắt server…");
            server.stop();
        }, "shutdown-hook"));

        if (showInfo) printFullInfo(baseConfig, lotoConfig);

        if (showConsole) {
            Thread t = new Thread(LotoMain::runConsole, "admin-console");
            t.setDaemon(true);
            t.start();
        }

        server.startSafe();
    }

    // ── Event listeners ───────────────────────────────────────────────

    private static void registerEventListeners() {
        server.getEventBus()
            .on(EventType.SERVER_STARTED,      e -> System.out.println("[Event] Server started"))
            .on(EventType.SERVER_STOPPED,      e -> System.out.println("[Event] Server stopped"))
            .on(EventType.PLAYER_JOINED,       e -> System.out.printf(
                    "[Event] + %-12s joined  (total: %d)%n",
                    e.getPlayer().getName(), server.getPlayerManager().getTotalCount()))
            .on(EventType.PLAYER_RECONNECTED,  e -> System.out.printf(
                    "[Event] ↩ %-12s reconnected%n", e.getPlayer().getName()))
            .on(EventType.PLAYER_DISCONNECTED, e -> System.out.printf(
                    "[Event] ~ %-12s disconnected%n", e.getPlayer().getName()))
            .on(EventType.PLAYER_LEFT,         e -> System.out.printf(
                    "[Event] - %-12s left permanently%n", e.getPlayer().getName()))
            .on(EventType.PLAYER_KICKED,       e -> System.out.printf(
                    "[Event] kick %-12s : %s%n", e.getPlayer().getName(), e.getMessage()))
            .on(EventType.ROOM_CREATED,        e -> System.out.println("[Event] Room created: " + e.getRoom()))
            .on(EventType.ROOM_CLOSED,         e -> System.out.println("[Event] Room closed:  " + e.getRoom()))
            .on(EventType.ERROR,               e -> System.err.println("[Event] ERROR: " + e.getMessage()))
            .onError((event, ex) -> System.err.printf(
                    "[EventBus] Listener threw for %s: %s%n", event.getType(), ex.getMessage()));
    }

    private static LotoServerCallback makeCallback() {
        return new LotoServerCallback() {
            @Override public void onGameStarting(String roomId) {
                System.out.printf("[Game:%s] ▶ Game bắt đầu%n", roomId); }
            @Override public void onNumberDrawn(String roomId, int number, List<Integer> all) {
                System.out.printf("[Game:%s] 🎱 Số: %-3d  (đã quay: %d/90)%n", roomId, number, all.size()); }
            @Override public void onGameEnded(String roomId, LotoPlayer winner, long prize) {
                System.out.printf("[Game:%s] 🏆 Thắng: %s  (jackpot: %,d)%n", roomId, winner.getName(), prize); }
            @Override public void onGameEndedByServer(String roomId, String reason) {
                System.out.printf("[Game:%s] ⏹ Kết thúc: %s%n", roomId, reason); }
            @Override public void onGameCancelled(String roomId, String reason, long refunded) {
                System.out.printf("[Game:%s] ✖ Hủy: %s  (hoàn: %,d)%n", roomId, reason, refunded); }
            @Override public void onClaimReceived(String roomId, LotoPlayer p, int pageId) {
                System.out.printf("[Game:%s] 🙋 Claim: %s  tờ #%d%n", roomId, p.getName(), pageId); }
            @Override public void onWinConfirmed(String roomId, LotoPlayer p, int pageId, long prize) {
                System.out.printf("[Game:%s] ✅ Xác nhận thắng: %s  tờ #%d%n", roomId, p.getName(), pageId); }
            @Override public void onWinRejected(String roomId, LotoPlayer p, int pageId) {
                System.out.printf("[Game:%s] ❌ Từ chối claim: %s  tờ #%d%n", roomId, p.getName(), pageId); }
            @Override public void onRoomReset(String roomId, long prizeEach, int winners) {
                System.out.printf("[Game:%s] 🔄 Reset  (winners=%d  prize=%,d mỗi người)%n", roomId, winners, prizeEach); }
            @Override public void onAutoStartScheduled(String roomId, int ms) {
                if (ms > 0) System.out.printf("[Game:%s] ⏱ Auto-start sau %,d ms%n", roomId, ms); }
            @Override public void onPagesBought(String roomId, LotoPlayer p, List<LotoPage> pages) {
                System.out.printf("[Game:%s] 🎫 %s mua %d tờ%n", roomId, p.getName(), pages.size()); }
            @Override public void onTopUp(String roomId, LotoPlayer p, long amount) {
                System.out.printf("[Game:%s] 💰 Nạp %,d → %s  (số dư: %,d)%n", roomId, amount, p.getName(), p.getBalance()); }
        };
    }

    // ── Admin console ─────────────────────────────────────────────────

    private static void runConsole() {
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        printBanner();

        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("loto> ");
            if (!sc.hasNextLine()) break;
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 6);
            try {
                handleCommand(parts[0].toLowerCase(), parts);
            } catch (Exception e) {
                System.err.println("  [Lỗi] " + e.getMessage());
            }
        }
    }

    private static void handleCommand(String cmd, String[] p) {
        switch (cmd) {

            // ── General ──────────────────────────────────────────────────
            case "help":  printConsoleHelp(); break;
            case "info":  printRuntimeInfo(); break;
            case "token": System.out.println("  Admin token: " + server.getConfig().adminToken); break;

            // ── Rooms ─────────────────────────────────────────────────────
            case "rooms": {
                Collection<Room> rooms = server.getAdmin().getAllRooms();
                if (rooms.isEmpty()) { System.out.println("  (không có phòng)"); break; }
                System.out.printf("  %-12s %-20s %-10s %-8s %-8s %-10s %-10s%n",
                        "ID", "TÊN", "TRẠNG THÁI", "PLAYERS", "MAX", "JACKPOT", "GIÁ/TỜ");
                System.out.println("  " + "─".repeat(80));
                for (Room r : rooms) {
                    LotoRoom lr = (LotoRoom) r;
                    GameFlow flow = getFlow(lr.getId());
                    String state = flow != null ? flow.getState().name() : r.getState().name();
                    System.out.printf("  %-12s %-20s %-10s %-8d %-8s %-10s %-10s%n",
                            r.getId(), r.getName(), state,
                            r.getPlayerCount(),
                            r.getMaxPlayers() == 0 ? "∞" : r.getMaxPlayers(),
                            String.format("%,d", lr.getJackpot()),
                            String.format("%,d", lr.getPricePerPage()));
                }
                System.out.println("  Tổng: " + rooms.size());
                break;
            }

            case "room-add": {
                if (p.length < 2) { System.out.println("  Usage: room-add <name> [maxPlayers]"); break; }
                int max = p.length >= 3 ? parseInt(p[2], 0) : 0;
                Room r = server.getAdmin().createRoom(p[1], p[1], max);
                System.out.printf("  ✓ Tạo phòng '%s'  (id: %s)%n", r.getName(), r.getId());
                break;
            }

            case "room-remove": {
                if (p.length < 2) { System.out.println("  Usage: room-remove <roomId>"); break; }
                Room r = server.getAdmin().getRoom(p[1]);
                if (r == null) { System.out.println("  Không tìm thấy phòng: " + p[1]); break; }
                server.getAdmin().closeRoom(p[1]);
                System.out.printf("  ✓ Đã đóng phòng '%s'%n", r.getName());
                break;
            }

            case "room-info": {
                if (p.length < 2) { System.out.println("  Usage: room-info <roomId>"); break; }
                LotoRoom r = lotoRoom(p[1]);
                if (r == null) { System.out.println("  Không tìm thấy phòng: " + p[1]); break; }
                GameFlow flow = getFlow(p[1]);
                GameState state = flow != null ? flow.getState() : GameState.WAITING;
                System.out.println("\n  ── Phòng: " + r.getName() + " (" + r.getId() + ") " + "─".repeat(30));
                System.out.println("  Trạng thái   : " + state);
                System.out.printf( "  Jackpot      : %,d%n", r.getJackpot());
                System.out.printf( "  Giá/tờ       : %,d%n", r.getPricePerPage());
                System.out.printf( "  Tốc độ quay  : %,d ms%n", r.getDrawIntervalMs());
                System.out.printf( "  Đã quay      : %d/90%n", r.getDrawnNumbers().size());
                System.out.printf( "  Người thắng  : %s%n",
                        r.getWinnerIds().isEmpty() ? "(chưa có)" : String.join(", ", r.getWinnerIds()));
                if (flow != null)
                    System.out.printf("  Vote         : %d/%d%n", flow.voteCount(), flow.voteThreshold());
                System.out.println();
                break;
            }

            // ── Players ───────────────────────────────────────────────────
            case "players": {
                Collection<Player> players = p.length >= 2
                        ? (lotoRoom(p[1]) != null ? server.getAdmin().getRoom(p[1]).getPlayers() : null)
                        : server.getAdmin().getAllPlayers();
                if (players == null) { System.out.println("  Không tìm thấy phòng: " + p[1]); break; }
                if (players.isEmpty()) { System.out.println("  (không có player)"); break; }
                System.out.printf("  %-12s %-14s %-12s %-10s %-8s %-8s %-6s%n",
                        "ID", "TÊN", "PHÒNG", "TRẠNG THÁI", "TỜ", "SỐ DƯ", "BOT");
                System.out.println("  " + "─".repeat(72));
                for (Player pl : players) {
                    LotoPlayer lp = (LotoPlayer) pl;
                    System.out.printf("  %-12s %-14s %-12s %-10s %-8d %-8s %-6s%n",
                            lp.getId(), lp.getName(),
                            lp.getRoomId() != null ? lp.getRoomId() : "—",
                            lp.isConnected() ? "online" : "ghost",
                            lp.getPages().size(),
                            String.format("%,d", lp.getBalance()),
                            lp.isBot() ? "✓" : "");
                }
                System.out.println("  Tổng: " + players.size());
                break;
            }

            case "kick": {
                if (p.length < 3) { System.out.println("  Usage: kick <roomId> <playerId> [reason]"); break; }
                server.getAdmin().kick(p[2], p.length >= 4 ? p[3] : "Bị kick bởi admin");
                System.out.println("  ✓ Đã kick " + p[2]);
                break;
            }

            case "ban": {
                if (p.length < 3) { System.out.println("  Usage: ban <roomId> <playerId> [reason]"); break; }
                server.getAdmin().ban(p[2], p.length >= 4 ? p[3] : "Bị ban bởi admin");
                System.out.println("  ✓ Đã ban " + p[2]);
                break;
            }

            case "unban": {
                if (p.length < 3) { System.out.println("  Usage: unban <roomId> <name>"); break; }
                server.getAdmin().unban(p[2]);
                System.out.println("  ✓ Đã unban " + p[2]);
                break;
            }

            case "ban-ip": {
                if (p.length < 3) { System.out.println("  Usage: ban-ip <roomId> <ip>"); break; }
                server.getAdmin().banIp(p[2]);
                System.out.println("  ✓ Đã ban IP " + p[2]);
                break;
            }

            case "unban-ip": {
                if (p.length < 3) { System.out.println("  Usage: unban-ip <roomId> <ip>"); break; }
                server.getAdmin().unbanIp(p[2]);
                System.out.println("  ✓ Đã unban IP " + p[2]);
                break;
            }

            case "topup": {
                if (p.length < 4) { System.out.println("  Usage: topup <roomId> <playerId> <amount> [note]"); break; }
                LotoRoom room = requireRoom(p[1]); if (room == null) break;
                GameFlow flow = getFlow(p[1]);     if (flow == null) break;
                long amount   = parseLong(p[3], 0);
                if (amount <= 0) { System.out.println("  Số tiền phải > 0"); break; }
                String note = p.length >= 5 ? p[4] : "Admin nạp tiền";
                // top-up via PlayerManager directly
                Player pl = server.getPlayerManager().getById(p[2]);
                if (!(pl instanceof LotoPlayer)) { System.out.println("  Không tìm thấy player: " + p[2]); break; }
                LotoPlayer lp = (LotoPlayer) pl;
                lp.topUp(amount, note);
                flow.sendBalanceUpdate(lp);
                System.out.printf("  ✓ Nạp %,d → %s  (số dư mới: %,d)%n", amount, lp.getName(), lp.getBalance());
                break;
            }

            // ── Game flow ─────────────────────────────────────────────────
            case "game-start": {
                GameFlow flow = requireFlow(p.length >= 2 ? p[1] : null); if (flow == null) break;
                flow.serverStart();
                System.out.println("  ✓ Game bắt đầu");
                break;
            }

            case "game-end": {
                GameFlow flow = requireFlow(p.length >= 2 ? p[1] : null); if (flow == null) break;
                flow.serverEnd(p.length >= 3 ? p[2] : null);
                System.out.println("  ✓ Game kết thúc");
                break;
            }

            case "game-cancel": {
                GameFlow flow = requireFlow(p.length >= 2 ? p[1] : null); if (flow == null) break;
                flow.cancelGame(p.length >= 3 ? p[2] : "Admin hủy game");
                System.out.println("  ✓ Game đã hủy (đã hoàn tiền)");
                break;
            }

            case "game-pause": {
                GameFlow flow = requireFlow(p.length >= 2 ? p[1] : null); if (flow == null) break;
                flow.pauseGame();
                System.out.println("  ✓ Game tạm dừng");
                break;
            }

            case "game-resume": {
                GameFlow flow = requireFlow(p.length >= 2 ? p[1] : null); if (flow == null) break;
                flow.resumeGame();
                System.out.println("  ✓ Game tiếp tục");
                break;
            }

            case "game-reset": {
                GameFlow flow = requireFlow(p.length >= 2 ? p[1] : null); if (flow == null) break;
                flow.reset();
                System.out.println("  ✓ Room reset — ván mới bắt đầu");
                break;
            }

            case "confirm-win": {
                if (p.length < 4) { System.out.println("  Usage: confirm-win <roomId> <playerId> <pageId>"); break; }
                GameFlow flow = requireFlow(p[1]); if (flow == null) break;
                flow.confirmWin(p[2], parseInt(p[3], -1));
                System.out.printf("  ✓ Xác nhận thắng: player=%s tờ#%s%n", p[2], p[3]);
                break;
            }

            case "reject-win": {
                if (p.length < 4) { System.out.println("  Usage: reject-win <roomId> <playerId> <pageId>"); break; }
                GameFlow flow = requireFlow(p[1]); if (flow == null) break;
                flow.rejectWin(p[2], parseInt(p[3], -1));
                System.out.printf("  ✓ Từ chối claim: player=%s tờ#%s%n", p[2], p[3]);
                break;
            }

            // ── Settings ──────────────────────────────────────────────────
            case "set-draw-ms": {
                if (p.length < 3) { System.out.println("  Usage: set-draw-ms <roomId> <ms>"); break; }
                GameFlow flow = requireFlow(p[1]); if (flow == null) break;
                flow.setDrawInterval(parseInt(p[2], 5000));
                System.out.printf("  ✓ Tốc độ quay → %,d ms%n", parseInt(p[2], 5000));
                break;
            }

            case "set-price": {
                if (p.length < 3) { System.out.println("  Usage: set-price <roomId> <price>"); break; }
                GameFlow flow = requireFlow(p[1]); if (flow == null) break;
                flow.setPricePerPage(parseLong(p[2], 0));
                System.out.printf("  ✓ Giá/tờ → %,d%n", parseLong(p[2], 0));
                break;
            }

            case "set-auto-reset": {
                if (p.length < 3) { System.out.println("  Usage: set-auto-reset <roomId> <ms>"); break; }
                GameFlow flow = requireFlow(p[1]); if (flow == null) break;
                flow.setAutoResetDelay(parseInt(p[2], 0));
                System.out.printf("  ✓ Auto-reset → %,d ms%n", parseInt(p[2], 0));
                break;
            }

            case "set-auto-start": {
                if (p.length < 3) { System.out.println("  Usage: set-auto-start <roomId> <ms>"); break; }
                GameFlow flow = requireFlow(p[1]); if (flow == null) break;
                flow.setAutoStartMs(parseInt(p[2], 0));
                System.out.printf("  ✓ Auto-start → %,d ms%n", parseInt(p[2], 0));
                break;
            }

            // ── Bots ──────────────────────────────────────────────────────
            case "bot-add": {
                if (p.length < 5) { System.out.println("  Usage: bot-add <roomId> <name> <balance> <maxPages>"); break; }
                GameFlow flow = requireFlow(p[1]); if (flow == null) break;
                long balance  = parseLong(p[3], 0);
                int  maxPages = parseInt(p[4], 1);
                BotManager bm = flow.getBotManager();
                if (bm.hasBot(p[2])) { System.out.println("  Bot đã tồn tại: " + p[2]); break; }
                bm.addBot(p[2], balance, maxPages);
                System.out.printf("  ✓ Bot '%s' đã vào phòng (balance=%,d maxPages=%d)%n", p[2], balance, maxPages);
                break;
            }

            case "bot-remove": {
                if (p.length < 3) { System.out.println("  Usage: bot-remove <roomId> <name>"); break; }
                GameFlow flow = requireFlow(p[1]); if (flow == null) break;
                boolean ok = flow.getBotManager().removeBot(p[2]);
                System.out.println(ok ? "  ✓ Đã xóa bot " + p[2] : "  Bot không tồn tại: " + p[2]);
                break;
            }

            case "bots": {
                if (p.length < 2) { System.out.println("  Usage: bots <roomId>"); break; }
                GameFlow flow = requireFlow(p[1]); if (flow == null) break;
                var list = flow.getBotManager().listBots();
                if (list.isEmpty()) { System.out.println("  (không có bot trong phòng này)"); break; }
                System.out.printf("  %-14s %-10s %-10s%n", "TÊN", "SỐ DƯ", "MAX PAGES");
                System.out.println("  " + "─".repeat(36));
                list.forEach(b -> System.out.printf("  %-14s %-10s %-10d%n",
                        b.getName(), String.format("%,d", b.getBalance()), b.getMaxPages()));
                System.out.println("  Tổng: " + list.size());
                break;
            }

            // ── Server ────────────────────────────────────────────────────
            case "stop": case "exit": case "quit":
                System.out.println("[Console] Đang tắt server…");
                server.stop();
                System.exit(0);
                break;

            default:
                System.out.println("  Lệnh không hợp lệ: '" + cmd + "'. Gõ 'help' để xem danh sách.");
        }
    }

    // ── Print helpers ─────────────────────────────────────────────────

    private static void printBanner() {
        ServerConfig cfg  = server.getConfig();
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              🎱  LOTO ONLINE  ·  ADMIN CONSOLE  🎱           ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Transport   : %-46s║%n", cfg.transport);
        System.out.printf( "║  TCP port    : %-46s║%n", cfg.tcpPort);
        System.out.printf( "║  WS  port    : %-46s║%n", cfg.wsPort > 0 ? cfg.wsPort : "disabled");
        System.out.printf( "║  Admin token : %-46s║%n", cfg.adminToken);
        System.out.printf( "║  Reconnect   : %-46s║%n", cfg.reconnectTimeoutMs + " ms");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Phòng       : %-46s║%n", server.getAdmin().getRoomCount());
        System.out.printf( "║  Player      : %-46s║%n", server.getAdmin().getPlayerCount());
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  Gõ 'help' để xem lệnh                                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void printRuntimeInfo() {
        ServerConfig cfg  = server.getConfig();
        long uptimeSec    = server.getUptimeMs() / 1000;
        System.out.println("\n  ── Server Info " + "─".repeat(40));
        System.out.printf( "  Uptime    : %dh %02dm %02ds%n", uptimeSec/3600, (uptimeSec%3600)/60, uptimeSec%60);
        System.out.printf( "  Players   : %d%n", server.getAdmin().getPlayerCount());
        System.out.printf( "  Rooms     : %d%n", server.getAdmin().getRoomCount());
        System.out.printf( "  Transport : %s  tcp=%d  ws=%s%n",
                cfg.transport, cfg.tcpPort, cfg.wsPort > 0 ? cfg.wsPort : "off");
        System.out.println();
    }

    private static void printConsoleHelp() {
        System.out.println("\n  ── General ──────────────────────────────────────────────────────");
        System.out.println("  help / info / token / stop");
        System.out.println("\n  ── Phòng ────────────────────────────────────────────────────────");
        System.out.println("  rooms");
        System.out.println("  room-add    <name> [maxPlayers]");
        System.out.println("  room-remove <roomId>");
        System.out.println("  room-info   <roomId>");
        System.out.println("\n  ── Player ───────────────────────────────────────────────────────");
        System.out.println("  players     [roomId]");
        System.out.println("  kick        <roomId> <playerId> [reason]");
        System.out.println("  ban         <roomId> <playerId> [reason]");
        System.out.println("  unban       <roomId> <name>");
        System.out.println("  ban-ip      <roomId> <ip>");
        System.out.println("  unban-ip    <roomId> <ip>");
        System.out.println("  topup       <roomId> <playerId> <amount> [note]");
        System.out.println("\n  ── Game ─────────────────────────────────────────────────────────");
        System.out.println("  game-start  <roomId>");
        System.out.println("  game-end    <roomId> [reason]");
        System.out.println("  game-cancel <roomId> [reason]");
        System.out.println("  game-pause  <roomId>");
        System.out.println("  game-resume <roomId>");
        System.out.println("  game-reset  <roomId>");
        System.out.println("  confirm-win <roomId> <playerId> <pageId>");
        System.out.println("  reject-win  <roomId> <playerId> <pageId>");
        System.out.println("\n  ── Cài đặt ──────────────────────────────────────────────────────");
        System.out.println("  set-draw-ms    <roomId> <ms>      (min 200ms)");
        System.out.println("  set-price      <roomId> <price>");
        System.out.println("  set-auto-reset <roomId> <ms>      (0 = tắt)");
        System.out.println("  set-auto-start <roomId> <ms>      (0 = tắt)");
        System.out.println("\n  ── Bot ──────────────────────────────────────────────────────────");
        System.out.println("  bot-add    <roomId> <name> <balance> <maxPages>");
        System.out.println("  bot-remove <roomId> <name>");
        System.out.println("  bots       <roomId>\n");
    }

    private static void printFullInfo(ServerConfig base, LotoConfig loto) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║              LOTO ONLINE SERVER                      ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf( "║  Transport  : %-38s║%n", base.transport);
        System.out.printf( "║  TCP port   : %-38s║%n", base.tcpPort);
        System.out.printf( "║  WS  port   : %-38s║%n", base.wsPort > 0 ? base.wsPort : "disabled");
        System.out.printf( "║  Admin token: %-38s║%n", base.adminToken);
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf( "║  Giá/tờ     : %-38s║%n", String.format("%,d", loto.pricePerPage));
        System.out.printf( "║  Số dư ban đầu: %-36s║%n", String.format("%,d", loto.initialBalance));
        System.out.printf( "║  Tốc độ quay: %-38s║%n", loto.drawIntervalMs + " ms");
        System.out.printf( "║  Max tờ/lần : %-38s║%n", loto.maxPagesPerBuy);
        System.out.printf( "║  Vote tối thiểu: %-35s║%n", loto.voteThresholdPct + "%");
        System.out.printf( "║  Auto verify: %-38s║%n", loto.autoVerifyWin);
        System.out.printf( "║  Auto reset : %-38s║%n", loto.autoResetDelayMs > 0 ? loto.autoResetDelayMs + " ms" : "off");
        System.out.printf( "║  Auto start : %-38s║%n", loto.autoStartMs > 0 ? loto.autoStartMs + " ms" : "off");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }

    // ── CLI parsing ───────────────────────────────────────────────────

    private static ServerConfig parseBaseArgs(String[] args) {
        ServerConfig.Builder b = new ServerConfig.Builder()
                .transport(TransportMode.WS)
                .wsPort(9001)
                .autoCreateDefaultRoom(false);
        for (int i = 0; i < args.length; i++) {
            switch (args[i].toLowerCase()) {
                case "--tcp":   b.transport(TransportMode.TCP);  break;
                case "--ws":    b.transport(TransportMode.WS);   break;
                case "--both":  b.transport(TransportMode.BOTH); break;
                case "--port":        b.port(intArg(args, ++i, "--port"));               break;
                case "--ws-port":     b.wsPort(intArg(args, ++i, "--ws-port"));          break;
                case "--admin-token": b.adminToken(strArg(args, ++i, "--admin-token"));  break;
                case "--reconnect-ms":b.reconnectTimeoutMs(intArg(args, ++i, "--reconnect-ms")); break;
                case "--max-players": b.maxPlayersPerRoom(intArg(args, ++i, "--max-players"));   break;
                case "--max-rooms":   b.maxRooms(intArg(args, ++i, "--max-rooms"));              break;
            }
        }
        return b.build();
    }

    private static LotoConfig parseLotoArgs(String[] args) {
        LotoConfig.Builder b = new LotoConfig.Builder();
        for (int i = 0; i < args.length; i++) {
            switch (args[i].toLowerCase()) {
                case "--price":        b.pricePerPage(longArg(args, ++i, "--price"));            break;
                case "--balance":      b.initialBalance(longArg(args, ++i, "--balance"));        break;
                case "--draw-ms":      b.drawIntervalMs(intArg(args, ++i, "--draw-ms"));         break;
                case "--max-pages":    b.maxPagesPerBuy(intArg(args, ++i, "--max-pages"));       break;
                case "--vote-pct":     b.voteThresholdPct(intArg(args, ++i, "--vote-pct"));      break;
                case "--min-players":  b.minPlayers(intArg(args, ++i, "--min-players"));         break;
                case "--auto-verify":  b.autoVerifyWin(true);                                    break;
                case "--auto-reset-ms":b.autoResetDelayMs(intArg(args, ++i, "--auto-reset-ms")); break;
                case "--auto-start-ms":b.autoStartMs(intArg(args, ++i, "--auto-start-ms"));      break;
            }
        }
        return b.build();
    }

    // ── Console helpers ───────────────────────────────────────────────

    private static GameFlow getFlow(String roomId) {
        LotoDispatcher d = lotoDispatcher();
        return d != null ? d.getFlow(roomId) : null;
    }

    private static GameFlow requireFlow(String roomId) {
        if (roomId == null) { System.out.println("  roomId bắt buộc"); return null; }
        LotoRoom r = lotoRoom(roomId);
        if (r == null) { System.out.println("  Không tìm thấy phòng: " + roomId); return null; }
        // getOrCreateFlow via dispatcher — access via reflection-free cast
        LotoDispatcher d = lotoDispatcher();
        if (d == null) return null;
        // Trigger creation if not exists by calling getFlow (returns null) → use internal method
        GameFlow flow = d.getFlow(roomId);
        if (flow == null) { System.out.println("  Phòng chưa có game flow (chưa có player nào vào)"); return null; }
        return flow;
    }

    private static LotoRoom lotoRoom(String roomId) {
        Room r = server.getAdmin().getRoom(roomId);
        return (r instanceof LotoRoom) ? (LotoRoom) r : null;
    }

    private static LotoRoom requireRoom(String roomId) {
        LotoRoom r = lotoRoom(roomId);
        if (r == null) System.out.println("  Không tìm thấy phòng: " + roomId);
        return r;
    }

    private static LotoDispatcher lotoDispatcher() {
        // MessageDispatcher is not directly exposed by SocketBaseServer.
        // Access via the typed subclass we created.
        // LotoServer stores it internally — expose via a package-level accessor if needed.
        // For now use the event bus to get the reference (stored at build time).
        // Simplest approach: store reference at startup.
        return cachedDispatcher;
    }

    // Set once at startup after server is built
    private static LotoDispatcher cachedDispatcher;

    // ── Arg helpers ───────────────────────────────────────────────────

    private static int intArg(String[] args, int idx, String flag) {
        if (idx >= args.length) { System.err.println("Missing value for " + flag); System.exit(1); }
        try { return Integer.parseInt(args[idx]); }
        catch (NumberFormatException e) {
            System.err.println(flag + " requires integer, got: " + args[idx]); System.exit(1); return 0;
        }
    }

    private static long longArg(String[] args, int idx, String flag) {
        if (idx >= args.length) { System.err.println("Missing value for " + flag); System.exit(1); }
        try { return Long.parseLong(args[idx]); }
        catch (NumberFormatException e) {
            System.err.println(flag + " requires long, got: " + args[idx]); System.exit(1); return 0;
        }
    }

    private static String strArg(String[] args, int idx, String flag) {
        if (idx >= args.length || args[idx].startsWith("--")) {
            System.err.println("Missing value for " + flag); System.exit(1);
        }
        return args[idx];
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (a.equalsIgnoreCase(flag)) return true;
        return false;
    }

    private static int  parseInt(String s, int def)  { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }
    private static long parseLong(String s, long def) { try { return Long.parseLong(s);   } catch (Exception e) { return def; } }

    // ── Help ──────────────────────────────────────────────────────────

    private static void printHelp() {
        System.out.println(
            "Loto Online Server\n\nUsage: java -jar loto_server.jar [options]\n\n" +
            "Network:\n" +
            "  --tcp / --ws / --both       Transport mode (default: ws)\n" +
            "  --port      <n>             TCP port       (default: 9000)\n" +
            "  --ws-port   <n>             WS port        (default: 9001)\n" +
            "  --admin-token <s>           Admin secret\n" +
            "  --reconnect-ms <n>          Reconnect window ms\n" +
            "  --max-players <n>           Max players per room (0 = unlimited)\n" +
            "  --max-rooms   <n>           Max rooms (0 = unlimited)\n\n" +
            "Game:\n" +
            "  --price         <n>         Price per page    (default: 10000)\n" +
            "  --balance       <n>         Initial balance   (default: 0)\n" +
            "  --draw-ms       <n>         Draw interval ms  (default: 5000)\n" +
            "  --max-pages     <n>         Max pages/buy     (default: 10)\n" +
            "  --vote-pct      <n>         Vote threshold %  (default: 51)\n" +
            "  --min-players   <n>         Min players       (default: 1)\n" +
            "  --auto-verify               Auto verify win claims\n" +
            "  --auto-reset-ms <n>         Auto reset delay  (default: 0=off)\n" +
            "  --auto-start-ms <n>         Auto start delay  (default: 0=off)\n\n" +
            "Other:\n" +
            "  --no-console                Disable admin console\n" +
            "  --show-info                 Print full config at startup\n" +
            "  --help\n\n" +
            "Examples:\n" +
            "  java -jar loto_server.jar\n" +
            "  java -jar loto_server.jar --ws --ws-port 9001 --admin-token secret --auto-verify\n" +
            "  java -jar loto_server.jar --both --port 9000 --ws-port 9001 --price 5000 --draw-ms 3000\n"
        );
    }
}
