package com.example.myapplication

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myapplication.databinding.ActivityChatBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val viewModel: ChatViewModel by viewModels()
    private val adapter = MessageAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var isUserAtBottom = true
    private var previousMessageCount = 0

    private val simulatedMessageRunnable = object : Runnable {
        override fun run() {
            viewModel.receiveSimulatedMessage()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupInput()
        setupFab()
        observeMessages()
        observeNewMessageCount()

        // 恢复状态
        if (savedInstanceState != null) {
            binding.etMessage.setText(savedInstanceState.getString("draft", ""))
            isUserAtBottom = savedInstanceState.getBoolean("isUserAtBottom", true)
            // 滚动位置会在 RecyclerView 布局完成后恢复
            val scrollPosition = savedInstanceState.getInt("scrollPosition", -1)
            if (scrollPosition >= 0) {
                binding.rvMessages.post {
                    (binding.rvMessages.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(scrollPosition, 0)
                }
            }
        }

        // 开始模拟消息
        handler.postDelayed(simulatedMessageRunnable, 3000)
    }

    private fun setupRecyclerView() {
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = adapter

        // 监听滚动，判断用户是否在底部
        binding.rvMessages.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    isUserAtBottom = !recyclerView.canScrollVertically(1)
                    if (isUserAtBottom) {
                        viewModel.resetNewMessageCount()
                    }
                }
            }
        })
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString()
            if (text.isNotBlank()) {
                viewModel.sendMessage(text)
                binding.etMessage.text.clear()
            }
        }
    }

    private fun setupFab() {
        binding.tvNewMessage.setOnClickListener {
            isUserAtBottom = true
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            viewModel.resetNewMessageCount()
        }
    }

    private fun observeMessages() {
        viewModel.messages.observe(this) { messages ->
            val hasNewMessage = messages.size > previousMessageCount
            adapter.submitList(messages) {
                if (isUserAtBottom) {
                    // 用户在底部，自动滚动
                    binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                } else if (hasNewMessage) {
                    // 用户不在底部，有新消息，增加计数
                    viewModel.incrementNewMessageCount()
                }
                previousMessageCount = messages.size
            }
        }
    }

    private fun observeNewMessageCount() {
        viewModel.newMessageCount.observe(this) { count ->
            if (count > 0 && !isUserAtBottom) {
                binding.tvNewMessage.text = "↓ $count 条新消息"
                binding.tvNewMessage.visibility = android.view.View.VISIBLE
            } else {
                binding.tvNewMessage.visibility = android.view.View.GONE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draft", binding.etMessage.text.toString())
        outState.putBoolean("isUserAtBottom", isUserAtBottom)
        // 保存当前可见的第一个 item 位置
        val layoutManager = binding.rvMessages.layoutManager as? LinearLayoutManager
        if (layoutManager != null) {
            outState.putInt("scrollPosition", layoutManager.findFirstVisibleItemPosition())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(simulatedMessageRunnable)
    }
}
