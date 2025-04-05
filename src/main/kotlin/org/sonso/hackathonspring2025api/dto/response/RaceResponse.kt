package org.sonso.hackathonspring2025api.dto.response

import java.util.*

data class RaceResponse(
    val type: ResponseType,                 // "history", "sync" или "update"
    val remainBefore: Date,                 // дата до которой осталось время (или дедлайн)
    val history: List<List<HistoryItem>>,   // история представлена в виде списка из 10 списков HistoryItem
    val isRunning: Boolean,                 // флаг, что симуляция запущена
    val lastResults: List<HistoryItem>,     // результаты предыдущего забега
    val currentRun: List<HistoryItem>       // данные текущего забега
) {
    data class Probabilities(
        val pos1: Double,       // вероятность занять 1-е место
        val pos2: Double,       // вероятность занять 2-е место
        val pos3: Double,       // вероятность занять 3-е место
        val pos4: Double,       // вероятность занять 4-е место
        val pos5: Double,       // вероятность занять 5-е место
        val pos6: Double,       // вероятность занять 6-е место
        val inThree: Double,    // вероятность занять 1-е ИЛИ 2-е ИЛИ 3-е место
        val inTwo: Double       // вероятность занять 1-е ИЛИ 2-е место
    )

    data class PairProbabilities(
        val a: Double,  // вероятность для спортсмена с id "a"
        val b: Double,  // вероятность для спортсмена с id "b"
        val c: Double,  // вероятность для спортсмена с id "c"
        val d: Double,  // вероятность для спортсмена с id "d"
        val e: Double,  // вероятность для спортсмена с id "e"
        val f: Double   // вероятность для спортсмена с id "f"
    )

    data class HistoryItem(
        val id: String,                                     // один из: "a", "b", "c", "d", "e", "f"
        val place: Int,                                     // занятое место
        val progress: Double? = null,                       // опционально, прогресс (например, процент пройденной дистанции)
        val probabilities: Probabilities? = null,           // опционально, вероятности по местам
        val pairProbabilities: PairProbabilities? = null    // опционально, парные вероятности
    )
}
