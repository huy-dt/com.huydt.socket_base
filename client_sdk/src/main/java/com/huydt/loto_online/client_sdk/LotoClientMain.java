package com.huydt.loto_online.client_sdk;

import com.huydt.loto_online.client_sdk.callback.LotoClientCallback;
import com.huydt.loto_online.client_sdk.core.LotoClient;
import com.huydt.loto_online.client_sdk.core.LotoSession;
import com.huydt.loto_online.client_sdk.model.LotoPage;
import com.huydt.loto_online.client_sdk.model.LotoPlayerInfo;
import com.huydt.loto_online.client_sdk.model.LotoRoomInfo;
import com.huydt.socket_base.client.core.ClientConfig;
import org.json.JSONArray;

import java.util.List;
import java.util.Scanner;

/**
 * CLI entry point for Loto Online client.
 *
 * <h3>Usage</h3>
 * <pre>
 * java -jar loto_client.jar [options]
 *
 * Connection:
 *   --url     &lt;url&gt;      Full URL (ws://localhost:9001)
 *   --host    &lt;h&gt;        Host      (default: localhost)
 *   --port    &lt;n&gt;        Port      (default: 9001)
 *   --ssl                Enable WSS
 *   --name    &lt;n&gt;        Player name
 *   --room    &lt;roomId&gt;   Auto-join room
 *   --token   &lt;token&gt;   Reconnect token
 *   --admin   &lt;token&gt;   Admin token
 *   --help
 * </pre>
 *
 * <h3>Console commands</h3>
 * <pre>
 *   info / session / room / players
 *   buy &lt;count&gt;              Buy pages
 *   pages [playerId]          Show my pages (or another player's)
 *   vote                      Vote to start
 *   claim &lt;pageId&gt;           Claim win
 *   wallet                   Show transaction history
 *   room-join &lt;roomId&gt;       Join a room
 *   room-leave               Leave room
 *
 *   — Admin —
 *   game-start / game-end [reason] / game-cancel [reason]
 *   game-pause / game-resume / game-reset
 *   confirm-win &lt;playerId&gt; &lt;pageId&gt;
 *   reject-win  &lt;playerId&gt; &lt;pageId&gt;
 *   topup &lt;playerId&gt; &lt;amount&gt; [note]
 *   set-draw-ms &lt;ms&gt;
 *   set-price &lt;price&gt;
 *   set-auto-reset &lt;ms&gt;
 *   set-auto-start &lt;ms&gt;
 *   bot-add &lt;n&gt; &lt;balance&gt; &lt;maxPages&gt;
 *   bot-remove &lt;n&gt;
 *   admin-auth &lt;token&gt;
 *   kick / ban / unban / ban-ip / unban-ip / bans / stats
 *   quit
 * </pre>
 */
public class LotoClientMain {

    private static LotoClient client;

    public static void main(String[] args) throws Exception {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) { printHelp(); return; }

        // ── Build config ──────────────────────────────────────────────
        LotoClient.Builder builder = new LotoClient.Builder();

        String url = strArg(args, "--url", null);
        if (url != null) {
            builder.url(url);
        } else {
            boolean ssl = hasFlag(args, "--ssl");
            builder.protocol(ssl ? ClientConfig.Protocol.WSS : ClientConfig.Protocol.WS);
            builder.host(strArg(args, "--host", "localhost"));
            String portStr = strArg(args, "--port", "9001");
            builder.port(Integer.parseInt(portStr));
        }

        String name        = strArg(args, "--name",  "Player");
        String room        = strArg(args, "--room",  null);
        String adminToken  = strArg(args, "--admin", null);
        String reconnToken = strArg(args, "--token", null);

        builder.name(name);
        if (room        != null) builder.roomId(room);
        if (adminToken  != null) builder.adminToken(adminToken);
        if (reconnToken != null) builder.reconnectToken(reconnToken);

        builder.callback(makeCallback());

        client = builder.build();

        System.out.println("[LotoClient] Kết nối → " + client.getConfig() + " với tên '" + name + "'…");
        client.connect();

        // ── Interactive console ───────────────────────────────────────
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("loto-client> ");
            if (!sc.hasNextLine()) break;
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 5);
            try {
                handleCommand(parts[0].toLowerCase(), parts);
            } catch (Exception e) {
                System.err.println("  [Lỗi] " + e.getMessage());
            }
        }
    }

    // ── Callback ──────────────────────────────────────────────────────

    private static LotoClientCallback makeCallback() {
        return new LotoClientCallback() {

            @Override public void onConnected() {
                System.out.println("[Event] ✓ Đã kết nối"); }

            @Override public void onDisconnected(String reason) {
                System.out.println("[Event] ✗ Ngắt kết nối: " + reason); }

            @Override public void onWelcome(String id, long balance) {
                System.out.printf("[Event] WELCOME  id=%s  balance=%,d%n", id, balance);
                System.out.println("[Event] Token: " + client.getLotoSession().getToken()
                        + "  (dùng --token để reconnect)"); }

            @Override public void onReconnected(String id) {
                System.out.printf("[Event] RECONNECTED  id=%s%n", id); }

            @Override public void onRoomSnapshot(LotoRoomInfo r) {
                System.out.printf("[Event] ROOM_SNAPSHOT  %s  state=%s  jackpot=%,d  players=%d%n",
                        r.name, r.state, r.jackpot, r.getPlayerCount()); }

            @Override public void onPlayerJoined(LotoPlayerInfo p) {
                System.out.printf("[Event] + %-12s vào phòng%n", p.name); }

            @Override public void onPlayerLeft(String id) {
                System.out.printf("[Event] - %s rời phòng%n", id); }

            @Override public void onPlayerDisconnected(String id) {
                System.out.printf("[Event] ~ %s ngắt kết nối%n", id); }

            @Override public void onPlayerReconnected(LotoPlayerInfo p) {
                System.out.printf("[Event] ↩ %s kết nối lại%n", p.name); }

            @Override public void onVoteUpdate(int count, int needed) {
                System.out.printf("[Event] 🗳 Vote: %d/%d%n", count, needed); }

            @Override public void onGameStarting(int ms) {
                System.out.printf("[Event] ▶ Game bắt đầu! Tốc độ quay: %,d ms%n", ms); }

            @Override public void onNumberDrawn(int n, List<Integer> all, List<Integer> winning) {
                System.out.printf("[Event] 🎱 Số %-3d   (%d/90)%n", n, all.size());
                if (!winning.isEmpty())
                    System.out.println("[Event] 🏆 Tờ " + winning + " có hàng thắng! Gõ 'claim <pageId>'"); }

            @Override public void onGameEnded(String id, String name) {
                System.out.printf("[Event] 🏆 Kết thúc! Thắng: %s (%s)%n", name, id); }

            @Override public void onGameEndedByServer(String reason) {
                System.out.println("[Event] ⏹ Kết thúc: " + reason); }

            @Override public void onGameCancelled(String reason, long refunded) {
                System.out.printf("[Event] ✖ Hủy: %s  (hoàn: %,d)%n", reason, refunded); }

            @Override public void onGamePaused()  { System.out.println("[Event] ⏸ Game tạm dừng"); }

            @Override public void onGameResumed(int ms) {
                System.out.printf("[Event] ▶ Game tiếp tục  ms=%d%n", ms); }

            @Override public void onRoomReset(long prize, int winners) {
                System.out.printf("[Event] 🔄 Reset  (winners=%d  prize=%,d mỗi người)%n", winners, prize); }

            @Override public void onClaimReceived(String id, String name, int pageId) {
                System.out.printf("[Event] 🙋 Claim: %s tờ #%d%n", name, pageId); }

            @Override public void onWinConfirmed(String id, String name, int pageId) {
                System.out.printf("[Event] ✅ Xác nhận thắng: %s tờ #%d%n", name, pageId); }

            @Override public void onWinRejected(String id, int pageId) {
                System.out.printf("[Event] ❌ Từ chối: %s tờ #%d%n", id, pageId); }

            @Override public void onPagesAssigned(List<LotoPage> pages) {
                System.out.printf("[Event] 🎫 Nhận %d tờ mới. Gõ 'pages' để xem.%n", pages.size()); }

            @Override public void onPageChanged(int pageId, LotoPage page) {
                System.out.printf("[Event] 🔀 Tờ #%d đã được xáo lại%n", pageId); }

            @Override public void onBalanceUpdate(long bal, String type, long amount, String note) {
                System.out.printf("[Event] 💰 %-15s %+,d  →  số dư: %,d  (%s)%n", type, amount, bal, note); }

            @Override public void onWalletHistory(long balance, JSONArray txs) {
                System.out.printf("[Event] 💳 Số dư: %,d  (%d giao dịch)%n",
                        balance, txs != null ? txs.length() : 0); }

            @Override public void onAutoStartScheduled(int ms) {
                if (ms > 0) System.out.printf("[Event] ⏱ Auto-start sau %,d ms%n", ms);
                else        System.out.println("[Event] ⏱ Auto-start đã hủy"); }

            @Override public void onAutoResetScheduled(int ms) {
                if (ms > 0) System.out.printf("[Event] ⏱ Auto-reset sau %,d ms%n", ms);
                else        System.out.println("[Event] ⏱ Auto-reset đã hủy"); }

            @Override public void onDrawIntervalChanged(int ms) {
                System.out.printf("[Event] ⚙ Tốc độ quay → %,d ms%n", ms); }

            @Override public void onPricePerPageChanged(long price) {
                System.out.printf("[Event] ⚙ Giá/tờ → %,d%n", price); }

            @Override public void onKicked(String reason)  {
                System.out.println("[Event] ⛔ Bị kick: " + reason); }

            @Override public void onBanned(String reason)  {
                System.out.println("[Event] 🚫 Bị ban: " + reason); }

            @Override public void onError(String code, String detail) {
                System.err.println("[Event] ❌ Lỗi: " + code + " — " + detail); }

            @Override public void onPlayerPages(String id, String name, List<LotoPage> pages) {
                System.out.printf("[Event] Tờ của %s (%d tờ):%n", name, pages.size());
                for (LotoPage p : pages) printPage(p); }
        };
    }

    // ── Console commands ──────────────────────────────────────────────

    private static void handleCommand(String cmd, String[] p) {
        LotoSession session = client.getLotoSession();

        switch (cmd) {

            case "help":    printConsoleHelp(); break;

            case "info":
                System.out.println("  " + session);
                System.out.println("  Config   : " + client.getConfig());
                System.out.println("  Connected: " + client.isConnected());
                break;

            case "session":
                System.out.println("  ID       : " + session.getPlayerId());
                System.out.println("  Name     : " + session.getName());
                System.out.println("  Token    : " + session.getToken());
                System.out.printf( "  Balance  : %,d%n", session.getBalance());
                System.out.printf( "  Room     : %s%n", session.getRoomId() != null ? session.getRoomId() : "lobby");
                System.out.printf( "  State    : %s%s%n", session.getGameState(), session.isPaused() ? " (PAUSED)" : "");
                System.out.printf( "  Jackpot  : %,d%n", session.getJackpot());
                System.out.printf( "  Giá/tờ  : %,d%n", session.getPricePerPage());
                System.out.printf( "  Đã quay : %d/90%n", session.getDrawnNumbers().size());
                System.out.printf( "  Vote     : %d/%d%n", session.getVoteCount(), session.getVoteNeeded());
                break;

            case "room": {
                LotoRoomInfo r = session.getLotoRoom();
                if (r == null) { System.out.println("  (lobby — chưa vào phòng)"); break; }
                System.out.println("  " + r);
                System.out.printf( "  Jackpot  : %,d%n", r.jackpot);
                System.out.printf( "  Giá/tờ  : %,d%n", r.pricePerPage);
                System.out.printf( "  Tốc độ  : %,d ms%n", r.drawIntervalMs);
                break;
            }

            case "players": {
                LotoRoomInfo r = session.getLotoRoom();
                if (r == null) { System.out.println("  (chưa vào phòng)"); break; }
                System.out.printf("  %-14s %-14s %-8s %-8s %-6s%n", "ID", "TÊN", "TỜ", "ONLINE", "BOT");
                System.out.println("  " + "─".repeat(52));
                for (var pi : r.getPlayers().values()) {
                    LotoPlayerInfo lp = (LotoPlayerInfo) pi;
                    System.out.printf("  %-14s %-14s %-8d %-8s %-6s%n",
                            lp.id, lp.name, lp.pageCount,
                            lp.connected ? "✓" : "ghost",
                            lp.isBot ? "🤖" : "");
                }
                break;
            }

            case "buy": {
                int count = p.length >= 2 ? parseInt(p[1], 1) : 1;
                client.buyPage(count);
                break;
            }

            case "pages": {
                if (p.length >= 2) {
                    client.getPages(p[1]);
                } else {
                    List<LotoPage> pages = session.getPages();
                    if (pages.isEmpty()) { System.out.println("  (chưa có tờ)"); break; }
                    for (LotoPage pg : pages) printPage(pg);
                }
                break;
            }

            case "vote":    client.voteStart(); break;

            case "claim": {
                if (p.length < 2) { System.out.println("  Usage: claim <pageId>"); break; }
                client.claimWin(parseInt(p[1], -1));
                break;
            }

            case "wallet":  client.getWallet(); break;

            case "room-join": {
                if (p.length < 2) { System.out.println("  Usage: room-join <roomId>"); break; }
                client.joinRoom(p[1]);
                break;
            }

            case "room-leave": client.leaveRoom(); break;

            // ── Admin ─────────────────────────────────────────────────
            case "admin-auth": {
                if (p.length < 2) { System.out.println("  Usage: admin-auth <token>"); break; }
                client.adminAuth(p[1]);
                break;
            }

            case "game-start":   client.gameStart(); break;
            case "game-end":     client.gameEnd(p.length >= 2 ? p[1] : null); break;
            case "game-cancel":  client.gameCancel(p.length >= 2 ? p[1] : null); break;
            case "game-pause":   client.gamePause(); break;
            case "game-resume":  client.gameResume(); break;
            case "game-reset":   client.gameReset(); break;

            case "confirm-win": {
                if (p.length < 3) { System.out.println("  Usage: confirm-win <playerId> <pageId>"); break; }
                client.confirmWin(p[1], parseInt(p[2], -1));
                break;
            }

            case "reject-win": {
                if (p.length < 3) { System.out.println("  Usage: reject-win <playerId> <pageId>"); break; }
                client.rejectWin(p[1], parseInt(p[2], -1));
                break;
            }

            case "topup": {
                if (p.length < 3) { System.out.println("  Usage: topup <playerId> <amount> [note]"); break; }
                client.topUp(p[1], parseLong(p[2], 0), p.length >= 4 ? p[3] : null);
                break;
            }

            case "set-draw-ms": {
                if (p.length < 2) { System.out.println("  Usage: set-draw-ms <ms>"); break; }
                client.setDrawInterval(parseInt(p[1], 5000));
                break;
            }

            case "set-price": {
                if (p.length < 2) { System.out.println("  Usage: set-price <price>"); break; }
                client.setPricePerPage(parseLong(p[1], 0));
                break;
            }

            case "set-auto-reset": {
                if (p.length < 2) { System.out.println("  Usage: set-auto-reset <ms>"); break; }
                client.setAutoReset(parseInt(p[1], 0));
                break;
            }

            case "set-auto-start": {
                if (p.length < 2) { System.out.println("  Usage: set-auto-start <ms>"); break; }
                client.setAutoStart(parseInt(p[1], 0));
                break;
            }

            case "bot-add": {
                if (p.length < 4) { System.out.println("  Usage: bot-add <n> <balance> <maxPages>"); break; }
                client.botAdd(p[1], parseLong(p[2], 0), parseInt(p[3], 1));
                break;
            }

            case "bot-remove": {
                if (p.length < 2) { System.out.println("  Usage: bot-remove <n>"); break; }
                client.botRemove(p[1]);
                break;
            }

            case "kick": {
                if (p.length < 2) { System.out.println("  Usage: kick <playerId> [reason]"); break; }
                client.kick(p[1], p.length >= 3 ? p[2] : "Kicked by admin");
                break;
            }

            case "ban": {
                if (p.length < 2) { System.out.println("  Usage: ban <playerId> [reason]"); break; }
                client.ban(p[1], p.length >= 3 ? p[2] : "Banned by admin");
                break;
            }

            case "unban": {
                if (p.length < 2) { System.out.println("  Usage: unban <displayName>"); break; }
                client.unban(p[1]);
                break;
            }

            case "ban-ip": {
                if (p.length < 2) { System.out.println("  Usage: ban-ip <ip>"); break; }
                client.banIp(p[1]);
                break;
            }

            case "unban-ip": {
                if (p.length < 2) { System.out.println("  Usage: unban-ip <ip>"); break; }
                client.unbanIp(p[1]);
                break;
            }

            case "bans":   client.getBanList(); break;
            case "stats":  client.getStats(); break;
            case "rooms":  client.listRooms(); break;

            case "quit": case "exit":
                client.disconnect();
                System.exit(0);
                break;

            default:
                System.out.println("  Lệnh không hợp lệ: '" + cmd + "'. Gõ 'help'.");
        }
    }

    // ── Page display ──────────────────────────────────────────────────

    private static void printPage(LotoPage page) {
        System.out.println("\n  ── Tờ #" + page.id + (page.hasWinningRow() ? "  🏆 WINNING!" : "") + " ──");
        for (int r = 0; r < LotoPage.ROWS; r++) {
            StringBuilder sb = new StringBuilder("  ");
            boolean rowWon = page.isRowComplete(r);
            for (int c = 0; c < LotoPage.COLS; c++) {
                Integer val = page.grid[r][c];
                if (val == null)          sb.append("  __ ");
                else if (page.marked[r][c]) sb.append(String.format(" [%2d]", val));
                else                        sb.append(String.format("  %2d ", val));
            }
            if (rowWon) sb.append(" ✓");
            System.out.println(sb);
        }
        System.out.println();
    }

    // ── Print helpers ─────────────────────────────────────────────────

    private static void printConsoleHelp() {
        System.out.println("\n  ── Player ────────────────────────────────────────────────────────");
        System.out.println("  info / session / room / players");
        System.out.println("  buy          <count>              Mua tờ");
        System.out.println("  pages        [playerId]           Xem tờ loto");
        System.out.println("  vote                              Vote bắt đầu");
        System.out.println("  claim        <pageId>             Khai báo thắng");
        System.out.println("  wallet                            Lịch sử giao dịch");
        System.out.println("  room-join    <roomId>             Vào phòng");
        System.out.println("  room-leave                        Rời phòng");
        System.out.println("\n  ── Admin ─────────────────────────────────────────────────────────");
        System.out.println("  admin-auth   <token>");
        System.out.println("  game-start / game-end [reason] / game-cancel [reason]");
        System.out.println("  game-pause / game-resume / game-reset");
        System.out.println("  confirm-win  <playerId> <pageId>");
        System.out.println("  reject-win   <playerId> <pageId>");
        System.out.println("  topup        <playerId> <amount> [note]");
        System.out.println("  set-draw-ms  <ms>                Tốc độ quay");
        System.out.println("  set-price    <price>             Giá/tờ");
        System.out.println("  set-auto-reset <ms>              Auto reset (0=tắt)");
        System.out.println("  set-auto-start <ms>              Auto start (0=tắt)");
        System.out.println("  bot-add      <n> <balance> <maxPages>");
        System.out.println("  bot-remove   <n>");
        System.out.println("  kick / ban / unban / ban-ip / unban-ip / bans / stats / rooms");
        System.out.println("\n  quit\n");
    }

    private static void printHelp() {
        System.out.println(
            "Loto Online Client\n\n" +
            "Usage: java -jar loto_client.jar [options]\n\n" +
            "  --url    <url>     Full URL  (ws://localhost:9001)\n" +
            "  --host   <h>       Host      (default: localhost)\n" +
            "  --port   <n>       Port      (default: 9001)\n" +
            "  --ssl              Dùng WSS\n" +
            "  --name   <n>       Tên người chơi\n" +
            "  --room   <roomId>  Tự vào phòng sau khi kết nối\n" +
            "  --token  <token>   Reconnect token\n" +
            "  --admin  <token>   Admin token (tự gửi ADMIN_AUTH)\n" +
            "  --help\n\n" +
            "Ví dụ:\n" +
            "  java -jar loto_client.jar --host localhost --port 9001 --name Alice --room default\n" +
            "  java -jar loto_client.jar --url ws://localhost:9001 --name Bob --admin secret\n"
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static String strArg(String[] args, String flag, String def) {
        for (int i = 0; i < args.length - 1; i++)
            if (args[i].equalsIgnoreCase(flag)) return args[i + 1];
        return def;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (a.equalsIgnoreCase(flag)) return true;
        return false;
    }

    private static int  parseInt(String s, int def)  { try { return Integer.parseInt(s);  } catch (Exception e) { return def; } }
    private static long parseLong(String s, long def) { try { return Long.parseLong(s);    } catch (Exception e) { return def; } }
}
