// Minimal port of GPTee's ChatDataItem — the engine layer only consumes `text`.
// Original class was UI-coupled (audio player, view holders); Orion keeps chat state in Room.
package com.localai.chat.model

class ChatDataItem(
    @JvmField var time: String? = null,
    var type: Int = 0,
    @JvmField var text: String? = null,
)
