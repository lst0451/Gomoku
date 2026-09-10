package com.gomoku.gameplay;

import com.gomoku.chat.ChatService;
import com.gomoku.dto.request.ChatMessageRequest;
import com.gomoku.dto.request.GameSettingsRequest;
import com.gomoku.dto.request.JoinRoomRequest;
import com.gomoku.dto.request.RestartGameRequest;
import com.gomoku.dto.request.SurrenderRequest;
import com.gomoku.model.entity.GameRoom;
import com.gomoku.model.enums.GameStatus;
import com.gomoku.security.UserPrincipal;
import com.gomoku.service.GameRoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GameLifecycleFeaturesTest {

    @Autowired GameRoomService rooms;
    @Autowired ChatService chat;

    @Test
    void spectatorDoesNotTakeSeatAndGuestCannotChat() {
        String roomId = "FEATURE-SPECTATE";
        UserPrincipal guest = new UserPrincipal("guest-feature", "Viewer", true);
        rooms.joinRoom(new JoinRoomRequest("view", roomId, true), guest, "guest-session");

        GameRoom room = rooms.getRoom(roomId);
        assertNull(room.getPlayerBlackId());
        assertTrue(room.getSpectatorUserIds().contains(guest.getUserId()));
        assertEquals("Viewer", room.getOnlineUserNames().get(guest.getUserId()));
        assertFalse(chat.sendMessage(new ChatMessageRequest("chat", roomId, "hello"), guest));
    }

    @Test
    void customAndUnlimitedTimeAreAppliedWhenGameStarts() {
        UserPrincipal black = new UserPrincipal("feature-black", "Black", false);
        UserPrincipal white = new UserPrincipal("feature-white", "White", false);

        String timedRoomId = "FEATURE-TIMED";
        rooms.joinRoom(new JoinRoomRequest("b1", timedRoomId), black, "b1");
        rooms.updateGameSettings(new GameSettingsRequest("settings", timedRoomId, 12), black);
        rooms.joinRoom(new JoinRoomRequest("w1", timedRoomId), white, "w1");
        GameRoom timed = rooms.getRoom(timedRoomId);
        assertEquals(12, timed.getTurnTimeSeconds());
        assertTrue(timed.getTurnDeadline() > System.currentTimeMillis());

        String unlimitedRoomId = "FEATURE-UNLIMITED";
        rooms.joinRoom(new JoinRoomRequest("b2", unlimitedRoomId), black, "b2");
        rooms.updateGameSettings(new GameSettingsRequest("settings2", unlimitedRoomId, 0), black);
        rooms.joinRoom(new JoinRoomRequest("w2", unlimitedRoomId), white, "w2");
        GameRoom unlimited = rooms.getRoom(unlimitedRoomId);
        assertNull(unlimited.getTurnTimeSeconds());
        assertEquals(0, unlimited.getTurnDeadline());
        assertNull(unlimited.getCurrentTurnTimer());
    }

    @Test
    void finishedPlayersCanRestartWithCleanBoard() {
        String roomId = "FEATURE-RESTART";
        UserPrincipal black = new UserPrincipal("restart-black", "Black", false);
        UserPrincipal white = new UserPrincipal("restart-white", "White", false);
        rooms.joinRoom(new JoinRoomRequest("b", roomId), black, "b");
        rooms.joinRoom(new JoinRoomRequest("w", roomId), white, "w");
        GameRoom room = rooms.getRoom(roomId);
        String firstGameId = room.getGameId();

        rooms.handleSurrender(new SurrenderRequest("s", roomId, null), black);
        assertEquals(GameStatus.FINISHED, room.getStatus());

        rooms.restartGame(new RestartGameRequest("r", roomId), white);
        assertEquals(GameStatus.PLAYING, room.getStatus());
        assertNotEquals(firstGameId, room.getGameId());
        assertEquals(0, room.getOccupiedCount());
        assertNull(room.getWinner());
        assertNull(room.getFinishReason());
    }
}

