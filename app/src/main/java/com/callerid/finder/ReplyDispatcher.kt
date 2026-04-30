package com.callerid.finder

object ReplyDispatcher {
    fun register(chatId: Long, handler: Any) {}
    fun unregister(chatId: Long) {}
    fun dispatch(update: Any) {}
}
