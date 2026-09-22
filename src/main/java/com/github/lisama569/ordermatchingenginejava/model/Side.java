package com.github.lisama569.ordermatchingenginejava.model;

/** 买卖方向。 */
public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
