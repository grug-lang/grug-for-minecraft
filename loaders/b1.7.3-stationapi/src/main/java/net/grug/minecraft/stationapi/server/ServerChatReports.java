package net.grug.minecraft.stationapi.server;

import net.grug.minecraft.core.GrugChat;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugReference;
import net.grug.minecraft.stationapi.control.GrugControlStation;
import net.grug.minecraft.stationapi.events.init.InitListener;
import net.minecraft.entity.player.PlayerEntity;

import java.util.function.Consumer;

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
    private ServerChatReports() {}

    /** Called at the end of every server tick, from the server entrypoint. */
    public static void onServerTick() {
        Object actor =
                GrugChat.actingPlayer(GrugServerRun.players(), GrugControlStation.starterPlayer());

        // The decisions and the formatting are core's, which the unit tests reach, so a tick here
        // runs every line on any dedicated run: nothing in this method waits for a chat to exist.
        Consumer<String> chat =
                GrugChat.sink(actor, GrugReference.isCiRun(), ServerChatReports::chatOf);

        GrugChat.drain(GrugChat.reporter(InitListener.LOGGER::info, chat));
    }

    /**
     * The chat of one connected player. It runs only when a report has a player to go to and the
     * run is not a CI run, and every dedicated run in CI sets {@code GRUG_CI}, which the server
     * process reads from its own environment, so no grug test and no unit test can reach it: a unit
     * test would need a live {@code PlayerEntity}, which only a running game makes.
     */
    @GrugGenerated(
            "a player's chat exists only outside a CI run, and every dedicated run in CI is one")
    private static Consumer<String> chatOf(Object actor) {
        PlayerEntity player = (PlayerEntity) actor;
        return player::sendMessage;
    }
}
