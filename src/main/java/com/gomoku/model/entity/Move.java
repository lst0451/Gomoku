package com.gomoku.model.entity;

import com.gomoku.model.enums.PieceColor;

/**
 * 落子记录不可变对象 (Record)
 *
 * @param stepNumber 第几手 (1..225)
 * @param color      执子颜色 (BLACK / WHITE)
 * @param row        行号 (0..14)
 * @param col        列号 (0..14)
 * @param timestamp  落子服务端时间戳
 */
public record Move(
    int stepNumber,
    PieceColor color,
    int row,
    int col,
    long timestamp
) {}
