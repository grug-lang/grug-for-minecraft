package net.grug.minecraft.stationapi.server;

import net.grug.minecraft.core.GrugChat;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugReference;
import net.grug.minecraft.stationapi.control.GrugControlStation;
import net.grug.minecraft.stationapi.events.init.InitListener;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Hands grug's reports to the player whose work raised them, on a dedicated server (#165).
 *
 * <p>A client drains the same queues into its one chat in {@code GrugClientHooks.tick}, and logs
 * every line on the way. A server has one chat per player, so core's {@link GrugChat} names the
 * player first: the player who started the run while one is going, since every client call the
 * server forwards is theirs, and otherwise the only player connected. With several players and no
 * run there is no one player whose work it was, and with none connected there is no chat to reach,
 * so those lines, and every line of a CI run, go to the log alone, which is also what keeps the
 * queues bounded.
 *
 * <p>The one way a server's line reads differently from a client's is the game's own doing: {@code
 * ServerPlayerEntity.sendMessage} runs the whole text through {@code TranslationStorage}, so a line
 * that is exactly an {@code en_US} key is translated on its way to the player. An err carries the
 * {@code §c} prefix, so it can never match one.
 */
@GrugGenerated("a dedicated server, which only the dispatch-only multiplayer job launches")
public final class ServerChatReports {
    /** The colour a grug err is drawn in, which is the client's own. */
    private static final String RED = "\u00A7c";

    private ServerChatReports() {}

    /** Called at the end of every server tick, from the server entrypoint. */
    public static void onServerTick() {
        Object actor =
                GrugChat.actingPlayer(GrugServerRun.players(), GrugControlStation.starterPlayer());
        PlayerEntity player = actor instanceof PlayerEntity ? (PlayerEntity) actor : null;

        // A CI run is not watched, and the client that joined it is the one taking the captures, so
        // the run's chat stays clear, exactly as a client's own drain keeps it (#412). The queues
        // are still emptied, or a suite's progress lines would pile up.
        boolean chat = player != null && !GrugReference.isCiRun();

        GrugChat.drain(
                (text, error) -> {
                    String prefix = error ? RED : "";

                    // Every line reaches the log, as a client's drain gives it, so a print is not
                    // lost when there is no chat to show it in.
                    for (String line : text.split("\n")) {
                        InitListener.LOGGER.info(prefix + line);
                    }

                    if (!chat) {
                        return;
                    }
                    for (String piece : GrugChat.wrap(text)) {
                        player.sendMessage(prefix + piece);
                    }
                });
    }
}
