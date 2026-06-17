package com.example.myapplication

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class ChatViewModel : ViewModel() {

    private val _messages = MutableLiveData<List<Message>>(emptyList())
    val messages: LiveData<List<Message>> = _messages

    private val _newMessageCount = MutableLiveData(0)
    val newMessageCount: LiveData<Int> = _newMessageCount

    private var nextId = 0L

    private val simulatedMessages = listOf(
        "你好呀！",
        "在忙什么呢？",
        "今天天气不错~",
        "有什么计划吗？",
        "哈哈哈",
        "好的，知道了",
        "没问题",
        "稍等一下",
        "我也这么觉得",
        "太棒了！"
    )

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        val newMessage = Message(
            id = nextId++,
            content = content.trim(),
            isSelf = true
        )
        _messages.value = _messages.value.orEmpty() + newMessage
    }

    fun receiveSimulatedMessage() {
        val content = simulatedMessages.random()
        val newMessage = Message(
            id = nextId++,
            content = content,
            isSelf = false
        )
        _messages.value = _messages.value.orEmpty() + newMessage
    }

    fun incrementNewMessageCount() {
        _newMessageCount.value = (_newMessageCount.value ?: 0) + 1
    }

    fun resetNewMessageCount() {
        _newMessageCount.value = 0
    }
}
