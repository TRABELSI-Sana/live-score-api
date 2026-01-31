package com.selim.livescores.sse

import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Component
class SseHub {
    private val emitters = ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>()
    fun subscribe(matchId: String): SseEmitter {
        val emitter = SseEmitter(0L)
        emitters.computeIfAbsent(matchId) { CopyOnWriteArrayList() }.add(emitter)
        fun remove() {
            emitters[matchId]?.remove(emitter)
        }

        emitter.onCompletion { remove() }
        emitter.onTimeout { remove() }
        emitter.onError { remove() }

        return emitter
    }


    fun publish(matchId: String, eventName: String, data: Any) {
        val list = emitters[matchId] ?: return

        val dead = mutableListOf<SseEmitter>()
        list.forEach { emitter ->
            try {
                emitter.send(
                    SseEmitter.event()
                        .name(eventName)
                        .data(data)
                )
            } catch (e: Exception) {
                dead.add(emitter)
            }
        }
        dead.forEach { list.remove(it) }
    }
}