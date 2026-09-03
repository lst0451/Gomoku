package com.gomoku.model;

import com.gomoku.model.entity.ChatMessage;
import com.gomoku.model.entity.Move;
import com.gomoku.model.enums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("领域核心枚举与实体模型测试 (DomainModelTest)")
class DomainModelTest {

    @Nested
    @DisplayName("PieceColor 棋色枚举测试")
    class PieceColorTests {

        @Test
        @DisplayName("数值映射与对立颜色换边")
        void testValueAndOpposite() {
            assertEquals(1, PieceColor.BLACK.getValue());
            assertEquals(2, PieceColor.WHITE.getValue());

            assertEquals(PieceColor.WHITE, PieceColor.BLACK.opposite());
            assertEquals(PieceColor.BLACK, PieceColor.WHITE.opposite());
        }

        @Test
        @DisplayName("根据合法数值解析棋色")
        void testFromValueValid() {
            assertEquals(PieceColor.BLACK, PieceColor.fromValue(1));
            assertEquals(PieceColor.WHITE, PieceColor.fromValue(2));
        }

        @Test
        @DisplayName("非法数值解析抛出 IllegalArgumentException")
        void testFromValueInvalid() {
            assertThrows(IllegalArgumentException.class, () -> PieceColor.fromValue(0));
            assertThrows(IllegalArgumentException.class, () -> PieceColor.fromValue(3));
            assertThrows(IllegalArgumentException.class, () -> PieceColor.fromValue(-1));
        }
    }

    @Nested
    @DisplayName("其他枚举定义完整性测试")
    class OtherEnumsTests {

        @Test
        @DisplayName("GameStatus 枚举状态定义")
        void testGameStatusValues() {
            assertArrayEquals(
                new GameStatus[]{GameStatus.WAITING, GameStatus.PLAYING, GameStatus.PAUSED, GameStatus.FINISHED},
                GameStatus.values()
            );
        }

        @Test
        @DisplayName("FinishReason 终局原因枚举定义")
        void testFinishReasonValues() {
            assertEquals(5, FinishReason.values().length);
            assertNotNull(FinishReason.valueOf("FIVE_IN_A_ROW"));
            assertNotNull(FinishReason.valueOf("SURRENDER"));
            assertNotNull(FinishReason.valueOf("TURN_TIMEOUT"));
            assertNotNull(FinishReason.valueOf("DISCONNECT_TIMEOUT"));
            assertNotNull(FinishReason.valueOf("DRAW"));
        }

        @Test
        @DisplayName("UserRole 角色枚举定义")
        void testUserRoleValues() {
            assertEquals(3, UserRole.values().length);
            assertNotNull(UserRole.valueOf("PLAYER_BLACK"));
            assertNotNull(UserRole.valueOf("PLAYER_WHITE"));
            assertNotNull(UserRole.valueOf("SPECTATOR"));
        }

        @Test
        @DisplayName("ErrorCode 错误码枚举定义")
        void testErrorCodeValues() {
            assertEquals(10, ErrorCode.values().length);
            assertNotNull(ErrorCode.valueOf("NOT_YOUR_TURN"));
            assertNotNull(ErrorCode.valueOf("CELL_OCCUPIED"));
            assertNotNull(ErrorCode.valueOf("INVALID_COORDINATES"));
            assertNotNull(ErrorCode.valueOf("GAME_NOT_PLAYING"));
            assertNotNull(ErrorCode.valueOf("NOT_A_PLAYER"));
            assertNotNull(ErrorCode.valueOf("STALE_STATE"));
            assertNotNull(ErrorCode.valueOf("DUPLICATE_COMMAND"));
            assertNotNull(ErrorCode.valueOf("ROOM_FULL"));
            assertNotNull(ErrorCode.valueOf("UNAUTHORIZED_ACTION"));
            assertNotNull(ErrorCode.valueOf("RATE_LIMITED"));
        }
    }

    @Nested
    @DisplayName("Move 记录实体测试")
    class MoveRecordTests {

        @Test
        @DisplayName("Move 字段访问与 Record 契约")
        void testMoveRecord() {
            long now = System.currentTimeMillis();
            Move move = new Move(1, PieceColor.BLACK, 7, 7, now);

            assertEquals(1, move.stepNumber());
            assertEquals(PieceColor.BLACK, move.color());
            assertEquals(7, move.row());
            assertEquals(7, move.col());
            assertEquals(now, move.timestamp());

            Move sameMove = new Move(1, PieceColor.BLACK, 7, 7, now);
            assertEquals(move, sameMove);
            assertEquals(move.hashCode(), sameMove.hashCode());
        }
    }

    @Nested
    @DisplayName("ChatMessage 记录实体测试")
    class ChatMessageRecordTests {

        @Test
        @DisplayName("ChatMessage 字段访问与 Record 契约")
        void testChatMessageRecord() {
            long now = System.currentTimeMillis();
            ChatMessage chat = new ChatMessage("msg-1", "user-1", "Alice", UserRole.PLAYER_BLACK, "Hello Gomoku", now);

            assertEquals("msg-1", chat.messageId());
            assertEquals("user-1", chat.senderId());
            assertEquals("Alice", chat.senderName());
            assertEquals(UserRole.PLAYER_BLACK, chat.role());
            assertEquals("Hello Gomoku", chat.content());
            assertEquals(now, chat.timestamp());

            ChatMessage sameChat = new ChatMessage("msg-1", "user-1", "Alice", UserRole.PLAYER_BLACK, "Hello Gomoku", now);
            assertEquals(chat, sameChat);
            assertEquals(chat.hashCode(), sameChat.hashCode());
        }
    }
}
