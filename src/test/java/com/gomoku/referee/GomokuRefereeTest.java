package com.gomoku.referee;

import com.gomoku.model.enums.PieceColor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("五子棋裁判规则引擎测试 (GomokuRefereeTest)")
class GomokuRefereeTest {

    private int[][] board;

    @BeforeEach
    void setUp() {
        board = new int[GomokuReferee.BOARD_SIZE][GomokuReferee.BOARD_SIZE];
    }

    @Nested
    @DisplayName("1. 水平轴向 (East-West) 连五测试")
    class HorizontalWinTests {

        @Test
        @DisplayName("盘面中央水平连五：落子在中间 (7, 7)")
        void testHorizontalWinCenterDrop() {
            int row = 7;
            for (int col = 5; col <= 9; col++) {
                board[row][col] = PieceColor.BLACK.getValue();
            }

            assertTrue(GomokuReferee.checkWin(board, row, 7, PieceColor.BLACK));
            assertTrue(GomokuReferee.checkWin(board, row, 5, PieceColor.BLACK.getValue()));
            assertTrue(GomokuReferee.checkWin(board, row, 9, PieceColor.BLACK));
        }

        @Test
        @DisplayName("顶边边缘 (row=0) 水平连五")
        void testHorizontalWinTopEdge() {
            int row = 0;
            for (int col = 0; col < 5; col++) {
                board[row][col] = PieceColor.WHITE.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, row, 0, PieceColor.WHITE));
            assertTrue(GomokuReferee.checkWin(board, row, 4, PieceColor.WHITE));
        }

        @Test
        @DisplayName("底边边缘 (row=14) 水平连五至右下角")
        void testHorizontalWinBottomRightEdge() {
            int row = 14;
            for (int col = 10; col <= 14; col++) {
                board[row][col] = PieceColor.BLACK.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, row, 14, PieceColor.BLACK));
            assertTrue(GomokuReferee.checkWin(board, row, 10, PieceColor.BLACK));
        }
    }

    @Nested
    @DisplayName("2. 垂直轴向 (North-South) 连五测试")
    class VerticalWinTests {

        @Test
        @DisplayName("盘面中央垂直连五：落子在末端")
        void testVerticalWinCenter() {
            int col = 7;
            for (int row = 5; row <= 9; row++) {
                board[row][col] = PieceColor.WHITE.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, 5, col, PieceColor.WHITE));
            assertTrue(GomokuReferee.checkWin(board, 7, col, PieceColor.WHITE));
            assertTrue(GomokuReferee.checkWin(board, 9, col, PieceColor.WHITE));
        }

        @Test
        @DisplayName("左边缘 (col=0) 垂直连五")
        void testVerticalWinLeftEdge() {
            int col = 0;
            for (int row = 0; row < 5; row++) {
                board[row][col] = PieceColor.BLACK.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, 0, col, PieceColor.BLACK));
            assertTrue(GomokuReferee.checkWin(board, 4, col, PieceColor.BLACK));
        }

        @Test
        @DisplayName("右边缘 (col=14) 垂直连五至右下角")
        void testVerticalWinRightEdge() {
            int col = 14;
            for (int row = 10; row <= 14; row++) {
                board[row][col] = PieceColor.WHITE.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, 10, col, PieceColor.WHITE));
            assertTrue(GomokuReferee.checkWin(board, 14, col, PieceColor.WHITE));
        }
    }

    @Nested
    @DisplayName("3. 主对角线 (Southeast-Northwest \\) 连五测试")
    class MainDiagonalWinTests {

        @Test
        @DisplayName("过天元 (7,7) 主对角线连五")
        void testMainDiagonalCenter() {
            for (int i = 5; i <= 9; i++) {
                board[i][i] = PieceColor.BLACK.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, 7, 7, PieceColor.BLACK));
            assertTrue(GomokuReferee.checkWin(board, 5, 5, PieceColor.BLACK));
            assertTrue(GomokuReferee.checkWin(board, 9, 9, PieceColor.BLACK));
        }

        @Test
        @DisplayName("左上角顶点 (0,0) 至 (4,4) 主对角线连五")
        void testMainDiagonalTopLeftCorner() {
            for (int i = 0; i < 5; i++) {
                board[i][i] = PieceColor.WHITE.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, 0, 0, PieceColor.WHITE));
            assertTrue(GomokuReferee.checkWin(board, 4, 4, PieceColor.WHITE));
        }

        @Test
        @DisplayName("右下角 (10,10) 至顶点 (14,14) 主对角线连五")
        void testMainDiagonalBottomRightCorner() {
            for (int i = 10; i <= 14; i++) {
                board[i][i] = PieceColor.BLACK.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, 10, 10, PieceColor.BLACK));
            assertTrue(GomokuReferee.checkWin(board, 14, 14, PieceColor.BLACK));
        }
    }

    @Nested
    @DisplayName("4. 副对角线 (Northeast-Southwest /) 连五测试")
    class AntiDiagonalWinTests {

        @Test
        @DisplayName("过天元 (7,7) 副对角线连五")
        void testAntiDiagonalCenter() {
            // (5,9), (6,8), (7,7), (8,6), (9,5)
            int[][] coords = {{5, 9}, {6, 8}, {7, 7}, {8, 6}, {9, 5}};
            for (int[] pt : coords) {
                board[pt[0]][pt[1]] = PieceColor.WHITE.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, 7, 7, PieceColor.WHITE));
            assertTrue(GomokuReferee.checkWin(board, 5, 9, PieceColor.WHITE));
            assertTrue(GomokuReferee.checkWin(board, 9, 5, PieceColor.WHITE));
        }

        @Test
        @DisplayName("右上角顶点 (0,14) 至 (4,10) 副对角线连五")
        void testAntiDiagonalTopRightCorner() {
            // (0,14), (1,13), (2,12), (3,11), (4,10)
            for (int i = 0; i < 5; i++) {
                board[i][14 - i] = PieceColor.BLACK.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, 0, 14, PieceColor.BLACK));
            assertTrue(GomokuReferee.checkWin(board, 4, 10, PieceColor.BLACK));
        }

        @Test
        @DisplayName("左下角 (10,4) 至顶点 (14,0) 副对角线连五")
        void testAntiDiagonalBottomLeftCorner() {
            // (10,4), (11,3), (12,2), (13,1), (14,0)
            int[][] coords = {{10, 4}, {11, 3}, {12, 2}, {13, 1}, {14, 0}};
            for (int[] pt : coords) {
                board[pt[0]][pt[1]] = PieceColor.WHITE.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, 14, 0, PieceColor.WHITE));
            assertTrue(GomokuReferee.checkWin(board, 10, 4, PieceColor.WHITE));
        }
    }

    @Nested
    @DisplayName("5. 自由规则长连判定 (Overline / count >= 5)")
    class OverlineWinTests {

        @Test
        @DisplayName("水平 6 连珠在自由规则下判定为获胜")
        void testSixInARowHorizontal() {
            int row = 7;
            for (int col = 4; col <= 9; col++) {
                board[row][col] = PieceColor.BLACK.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, row, 7, PieceColor.BLACK));
            assertTrue(GomokuReferee.checkWin(board, row, 4, PieceColor.BLACK));
            assertTrue(GomokuReferee.checkWin(board, row, 9, PieceColor.BLACK));
        }

        @Test
        @DisplayName("主对角线 7 连珠在自由规则下判定为获胜")
        void testSevenInARowDiagonal() {
            for (int i = 3; i <= 9; i++) {
                board[i][i] = PieceColor.WHITE.getValue();
            }
            assertTrue(GomokuReferee.checkWin(board, 6, 6, PieceColor.WHITE));
            assertTrue(GomokuReferee.checkWin(board, 3, 3, PieceColor.WHITE));
            assertTrue(GomokuReferee.checkWin(board, 9, 9, PieceColor.WHITE));
        }
    }

    @Nested
    @DisplayName("6. 非获胜情况判定")
    class NonWinTests {

        @Test
        @DisplayName("仅 4 颗连续棋子不应判胜")
        void testFourInARowNotWin() {
            for (int col = 5; col <= 8; col++) {
                board[7][col] = PieceColor.BLACK.getValue();
            }
            assertFalse(GomokuReferee.checkWin(board, 7, 6, PieceColor.BLACK));
        }

        @Test
        @DisplayName("被对方棋子阻断的 5 格序列不应判胜")
        void testBlockedByOpponentNotWin() {
            board[7][3] = PieceColor.BLACK.getValue();
            board[7][4] = PieceColor.BLACK.getValue();
            board[7][5] = PieceColor.WHITE.getValue(); // 被白棋阻断
            board[7][6] = PieceColor.BLACK.getValue();
            board[7][7] = PieceColor.BLACK.getValue();

            assertFalse(GomokuReferee.checkWin(board, 7, 7, PieceColor.BLACK));
            assertFalse(GomokuReferee.checkWin(board, 7, 3, PieceColor.BLACK));
        }

        @Test
        @DisplayName("有间隔空位的序列不应判胜")
        void testGapSequenceNotWin() {
            board[7][3] = PieceColor.BLACK.getValue();
            board[7][4] = PieceColor.BLACK.getValue();
            // (7,5) 为空
            board[7][6] = PieceColor.BLACK.getValue();
            board[7][7] = PieceColor.BLACK.getValue();
            board[7][8] = PieceColor.BLACK.getValue();

            assertFalse(GomokuReferee.checkWin(board, 7, 8, PieceColor.BLACK));
        }

        @Test
        @DisplayName("在空位或敌方棋子坐标校验获胜应返回 false")
        void testCheckWinOnEmptyOrMismatchedCell() {
            board[7][7] = PieceColor.BLACK.getValue();
            // 校验白子在黑子坐标上
            assertFalse(GomokuReferee.checkWin(board, 7, 7, PieceColor.WHITE));
            // 校验空位
            assertFalse(GomokuReferee.checkWin(board, 0, 0, PieceColor.BLACK));
        }
    }

    @Nested
    @DisplayName("7. 边界与异常入参健壮性测试")
    class BoundaryAndRobustnessTests {

        @Test
        @DisplayName("四角顶点单子落子不抛异常且返回 false")
        void testCornerSingleStones() {
            int[][] corners = {{0, 0}, {0, 14}, {14, 0}, {14, 14}};
            for (int[] pt : corners) {
                board[pt[0]][pt[1]] = PieceColor.BLACK.getValue();
                assertFalse(GomokuReferee.checkWin(board, pt[0], pt[1], PieceColor.BLACK));
            }
        }

        @ParameterizedTest
        @CsvSource({
            "-1, 0",
            "0, -1",
            "15, 0",
            "0, 15",
            "-5, -5",
            "20, 20"
        })
        @DisplayName("越界坐标安全返回 false")
        void testOutOfBoundsCoordinates(int row, int col) {
            assertFalse(GomokuReferee.checkWin(board, row, col, PieceColor.BLACK));
            assertFalse(GomokuReferee.isValidCoordinate(row, col));
            assertFalse(GomokuReferee.isCellEmpty(board, row, col));
        }

        @Test
        @DisplayName("空盘面与非法尺寸入参安全防御")
        void testNullAndInvalidBoard() {
            assertFalse(GomokuReferee.checkWin(null, 7, 7, PieceColor.BLACK));
            assertFalse(GomokuReferee.checkWin(new int[10][10], 7, 7, PieceColor.BLACK));
            assertFalse(GomokuReferee.checkWin(board, 7, 7, (PieceColor) null));
            assertFalse(GomokuReferee.isCellEmpty(null, 7, 7));
        }
    }

    @Nested
    @DisplayName("8. 平局判定测试 (checkDraw)")
    class DrawTests {

        @Test
        @DisplayName("未满盘 (occupiedCount < 225) 且未胜，不应判定平局")
        void testNotDrawWhenNotFull() {
            assertFalse(GomokuReferee.checkDraw(224, false));
            assertFalse(GomokuReferee.checkDraw(0, false));
            assertFalse(GomokuReferee.checkDraw(100, false));
        }

        @Test
        @DisplayName("盘面满 225 且未胜，判定平局")
        void testDrawWhenBoardFullAndNoWin() {
            assertTrue(GomokuReferee.checkDraw(225, false));
            assertTrue(GomokuReferee.checkDraw(226, false));
        }

        @Test
        @DisplayName("若当前步已获胜，即使盘面达到 225 也不判定平局（获胜优先）")
        void testWinPrecedesDraw() {
            assertFalse(GomokuReferee.checkDraw(225, true));
        }
    }

    @Nested
    @DisplayName("9. 辅助方法测试 (isValidCoordinate & isCellEmpty)")
    class UtilityTests {

        @Test
        @DisplayName("棋盘有效坐标区间为 [0, 14]")
        void testIsValidCoordinate() {
            assertTrue(GomokuReferee.isValidCoordinate(0, 0));
            assertTrue(GomokuReferee.isValidCoordinate(14, 14));
            assertTrue(GomokuReferee.isValidCoordinate(7, 7));

            assertFalse(GomokuReferee.isValidCoordinate(-1, 0));
            assertFalse(GomokuReferee.isValidCoordinate(0, -1));
            assertFalse(GomokuReferee.isValidCoordinate(15, 0));
            assertFalse(GomokuReferee.isValidCoordinate(0, 15));
        }

        @Test
        @DisplayName("isCellEmpty 准确识别空位与已有落子")
        void testIsCellEmpty() {
            assertTrue(GomokuReferee.isCellEmpty(board, 7, 7));

            board[7][7] = PieceColor.BLACK.getValue();
            assertFalse(GomokuReferee.isCellEmpty(board, 7, 7));

            board[0][0] = PieceColor.WHITE.getValue();
            assertFalse(GomokuReferee.isCellEmpty(board, 0, 0));

            assertFalse(GomokuReferee.isCellEmpty(board, -1, 0));
            assertFalse(GomokuReferee.isCellEmpty(board, 15, 15));
        }
    }
}
