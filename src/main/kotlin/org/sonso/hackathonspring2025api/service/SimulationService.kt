package org.sonso.hackathonspring2025api.service

import org.slf4j.LoggerFactory
import org.sonso.hackathonspring2025api.dto.Athlete
import org.sonso.hackathonspring2025api.dto.PairKey
import org.sonso.hackathonspring2025api.dto.RaceResult
import org.sonso.hackathonspring2025api.dto.SimulationStats
import org.sonso.hackathonspring2025api.dto.response.RaceResponse
import org.sonso.hackathonspring2025api.dto.response.ResponseType
import org.springframework.stereotype.Service
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Service
class SimulationService {
    private val logger = LoggerFactory.getLogger(SimulationService::class.java)
    private val random = Random()

    // =========================================
    //  1) Состояние "между забегами" и "идёт забег"
    // =========================================

    /**
     * Когда стартует следующий забег (в мс, System.currentTimeMillis()).
     * Если текущая метка времени < nextRaceStartTime, значит ждём.
     * Если текущее время >= nextRaceStartTime, можно стартовать новый забег.
     */
    var nextRaceStartTime: Long = 0L

    /**
     * Флаг, идёт ли в данный момент забег.
     */
    var isRaceRunning = false

    // 10 секунд пауза между забегами (в мс)
    val RACE_PAUSE_MS = 10_000L

    // История забегов в формате, подходящем фронту:
    // максимум 10 последних.
    // Каждый элемент списка – список HistoryItem (для одного забега).
    private val historyForFront: MutableList<List<RaceResponse.HistoryItem>> = mutableListOf()

    // =========================================
    //  2) Модель спортсменов, хранит результаты для обновления mu, sigma1, sigma2
    // =========================================

    private val athletes = mutableListOf<Athlete>()

    // Инициализация спортсменов
    private fun initializeAthletes() {
        val names = listOf("A", "B", "C", "D", "E", "F")
        athletes.clear()
        athletes.addAll(
            names.map { name ->
                Athlete(
                    name = name,
                    mu = 10.0 + random.nextDouble() * 2.0, // mu в диапазоне [10, 12)
                    sigma1 = 0.3,
                    sigma2 = 0.6,
                    history = mutableListOf()
                )
            }
        )
        logger.info("Спортсмены инициализированы: ${athletes.map { it.name }}")
    }

    // =========================================
    //  3) Логика расстановки, симуляции, обновления
    // =========================================

    fun runScheduledLogic(): RaceResponse {
        val now = System.currentTimeMillis()

        // Если ещё нет спортсменов – создаём
        if (athletes.isEmpty()) {
            initializeAthletes()
            // Сразу можно запланировать 1-й забег "сейчас" (nextRaceStartTime=now)
            nextRaceStartTime = now
        }

        return if (!isRaceRunning) {
            // Забег НЕ идёт
            if (now >= nextRaceStartTime) {
                // Пора начать новый забег
                startNewRace()
                // Сразу возвращаем sync, потому что гонка стартовала только что
                buildSyncResponse()
            } else {
                // Пока ждём (между забегами) – сборка UPDATE:
                //   фронт хочет "lastResults" и "history" и remainBefore = когда будет начало гонки
                buildUpdateResponse()
            }
        } else {
            // Забег идёт – присылаем SYNC
            // (если внутри забег закончится, на следующем тике шедуллера увидим)
            val raceFinished = updateRaceOneStep()
            if (raceFinished) {
                // Забег только что завершился
                finishRace()
                // Посылаем UPDATE, чтобы фронт получил новые lastResults, history и 10-секундный таймер
                buildUpdateResponse()
            } else {
                // Забег ещё не завершён – просто SYNC
                buildSyncResponse()
            }
        }
    }

    /**
     * Старт нового забега
     */
    private fun startNewRace() {
        logger.info("Старт нового забега!")
        isRaceRunning = true
        // Инициируем расчёты
        distances = DoubleArray(athletes.size) { 0.0 }
        finishTimes = arrayOfNulls(athletes.size)
        currentTimeSeconds = 0.0
    }

    /**
     * Обновление состояния забега (шаг в 1с),
     * Возвращает true, если забег в процессе этого шага завершился.
     */
    private fun updateRaceOneStep(): Boolean {
        val k = athletes.size
        if (finishTimes == null) return true // safety-check

        val dt = 1.0
        var raceFinished = false

        // Ещё не финишировавшим игрокам прибавляем дистанцию
        for (i in 0 until k) {
            if (finishTimes!![i] == null) {
                val baseSpeed = 100.0 / athletes[i].mu
                val fluct = skewedNormal(0.0, baseSpeed * 0.1, baseSpeed * 0.15)
                var stepSpeed = baseSpeed + fluct
                if (stepSpeed < 0) stepSpeed = 0.0

                val dOld = distances!![i]
                val dNew = dOld + stepSpeed * dt
                if (dNew >= 100.0) {
                    val distNeeded = 100.0 - dOld
                    val tExtra = if (stepSpeed > 0) distNeeded / stepSpeed else dt
                    finishTimes!![i] = currentTimeSeconds + tExtra
                    distances!![i] = 100.0
                } else {
                    distances!![i] = dNew
                }
            }
        }

        currentTimeSeconds += dt
        // Проверка, все ли добежали
        raceFinished = finishTimes!!.all { it != null }

        return raceFinished
    }

    /**
     * Завершение забега: формируем результаты, обновляем mu/sigma,
     * добавляем запись в историю (historyForFront), планируем след. забег через 10с.
     */
    private fun finishRace() {
        val raceIndex = (athletes.first().history.size) + 1
        logger.info("Забег №$raceIndex завершён")
        isRaceRunning = false
        nextRaceStartTime = System.currentTimeMillis() + RACE_PAUSE_MS

        // Формируем результаты
        val runnersInfo = finishTimes!!.mapIndexed { index, ft -> index to (ft ?: Double.POSITIVE_INFINITY) }
            .sortedBy { it.second }
        val results = mutableListOf<RaceResult>()
        runnersInfo.forEachIndexed { rank, pair ->
            val idx = pair.first
            val tfin = pair.second
            results.add(RaceResult(athletes[idx].name, tfin, rank + 1))
        }

        // Обновляем параметры
        updateAthleteParamsLinearly(athletes, results, 20)

        // Добавляем в историю (для фронта) – по одному HistoryItem на спортсмена
        val newHistoryRow: List<RaceResponse.HistoryItem> = runnersInfo.mapIndexed { rank, pair ->
            val idx = pair.first
            val tfin = pair.second
            RaceResponse.HistoryItem(
                id = athletes[idx].name.lowercase(),  // "a", "b", ...
                place = rank + 1
            )
        }
        // ограничим историю 10 записями (10 последних забегов)
        if (historyForFront.size == 10) {
            historyForFront.removeAt(0)
        }
        historyForFront.add(newHistoryRow)

        // Сносим "рабочие" поля (distances, finishTimes)
        distances = null
        finishTimes = null
    }

    // =========================================
    //  4) Подготовка нужных структур для RaceResponse
    // =========================================

    /**
     * Собрать RaceResponse с типом SYNC
     * (инфа о текущих позициях бегунов, их вероятностях, isRunning=true).
     */
    private fun buildSyncResponse(): RaceResponse {
        val remain = Date() // на SYNC часто всё равно, но дадим текущее время
        val curRun = buildCurrentRunItems() // прогресс и вероятности

        return RaceResponse(
            type = ResponseType.SYNC,
            remainBefore = remain,
            history = historyForFront,
            isRunning = true,
            lastResults = emptyList(),  // при SYNC "прошлые результаты" не отправляем
            currentRun = curRun
        )
    }

    /**
     * Собрать RaceResponse с типом UPDATE
     * (после завершения забега или пока ждём следующий).
     */
    private fun buildUpdateResponse(): RaceResponse {
        val remain = Date(nextRaceStartTime) // когда начнётся новый забег
        val curRun = if (isRaceRunning) buildCurrentRunItems() else emptyList()
        val lastRes = if (!isRaceRunning && historyForFront.isNotEmpty()) {
            // берём последний блок из истории
            historyForFront.last()
        } else emptyList()

        return RaceResponse(
            type = ResponseType.UPDATE,
            remainBefore = remain,
            history = historyForFront,
            isRunning = isRaceRunning,
            lastResults = lastRes,
            currentRun = curRun
        )
    }

    /**
     * Собираем список HistoryItem для текущего момента забега (когда он идёт).
     * Включает прогресс, вероятность занять каждое место, etc.
     */
    private fun buildCurrentRunItems(): List<RaceResponse.HistoryItem> {
        if (!isRaceRunning || distances == null) return emptyList()

        val k = athletes.size
        // Прогнозируем распределения мест
        val stats = simulateFuturePositions(
            currentDistances = distances!!,
            currentTime = currentTimeSeconds,
            athletes = athletes,
            nSim = 1000
        )

        val out = mutableListOf<RaceResponse.HistoryItem>()
        for (i in 0 until k) {
            // Вероятности занять каждое место (1..6)
            val pArray = stats.probsByPlace[i]
            // P(top2) = p(1) + p(2)
            val pTop2 = pArray.getOrElse(0) { 0.0 } + pArray.getOrElse(1) { 0.0 }
            // P(top3) = pTop2 + p(3)
            val pTop3 = pTop2 + pArray.getOrElse(2) { 0.0 }

            val item = RaceResponse.HistoryItem(
                id = athletes[i].name.lowercase(),  // "a", "b", ...
                place = calcCurrentPlace(i),         // какая позиция "по дистанции" прямо сейчас
                progress = distances!![i],           // текущее количество метров (0..100)
                probabilities = RaceResponse.Probabilities(
                    pos1 = pArray.getOrElse(0) { 0.0 },
                    pos2 = pArray.getOrElse(1) { 0.0 },
                    pos3 = pArray.getOrElse(2) { 0.0 },
                    pos4 = pArray.getOrElse(3) { 0.0 },
                    pos5 = pArray.getOrElse(4) { 0.0 },
                    pos6 = pArray.getOrElse(5) { 0.0 },
                    inThree = pTop3,
                    inTwo = pTop2
                ),
                // Для примера поле pairProbabilities можно заполнить чем-то,
                // но ТЗ не совсем понятно, как фронт хочет эти данные видеть (вероятность чего именно?)
                // Допустим, сделаем заглушку. Или можно полностью убрать, если не нужно.
                pairProbabilities = RaceResponse.PairProbabilities(
                    a = 0.0, b = 0.0, c = 0.0, d = 0.0, e = 0.0, f = 0.0
                )
            )
            out.add(item)
        }
        return out
    }

    /**
     * Считаем, на каком месте (1..6) сейчас находится спортсмен i
     * по дистанции. Если одинаковая дистанция – пусть будет произвольный порядок.
     */
    private fun calcCurrentPlace(i: Int): Int {
        val sorted = distances!!.withIndex().sortedByDescending { it.value }
        // Поиск idx i
        val rank = sorted.indexOfFirst { it.index == i }
        return rank + 1
    }

    // =========================================
    //  5) Математика
    // =========================================

    private var distances: DoubleArray? = null
    private var finishTimes: Array<Double?>? = null
    private var currentTimeSeconds = 0.0

    private fun skewedNormal(mu: Double, sigma1: Double, sigma2: Double): Double {
        val z = random.nextGaussian()
        return if (z >= 0) mu + z * sigma2 else mu + z * sigma1
    }

    private fun simulateFuturePositions(
        currentDistances: DoubleArray,
        currentTime: Double,
        athletes: List<Athlete>,
        nSim: Int = 2000
    ): SimulationStats {
        val k = athletes.size
        val placeCounts = Array(k) { DoubleArray(k) { 0.0 } }
        val pairTop2Counts = mutableMapOf<Pair<Int, Int>, Int>()
        val pairAllCounts = mutableMapOf<PairKey, Int>()

        // Инициализируем для top2
        for (i in 0 until k) {
            for (j in 0 until k) {
                if (i != j) {
                    pairTop2Counts[Pair(i, j)] = 0
                }
            }
        }

        repeat(nSim) {
            val distances = currentDistances.copyOf()
            val finishedTime = DoubleArray(k) { Double.POSITIVE_INFINITY }

            for (i in 0 until k) {
                if (distances[i] >= 100.0) {
                    finishedTime[i] = currentTime
                } else {
                    var fullTime = skewedNormal(athletes[i].mu, athletes[i].sigma1, athletes[i].sigma2)
                    if (fullTime < 0.1) fullTime = 0.1
                    val ratioDone = min(distances[i] / 100.0, 1.0)
                    val timeLeft = fullTime * (1.0 - ratioDone)
                    finishedTime[i] = currentTime + timeLeft
                }
            }

            val order = finishedTime.indices.sortedBy { finishedTime[it] }
            order.forEachIndexed { rankPos, runnerIndex ->
                placeCounts[runnerIndex][rankPos] += 1.0
            }
            if (order.size >= 2) {
                val keyTop2 = Pair(order[0], order[1])
                pairTop2Counts[keyTop2] = pairTop2Counts.getOrDefault(keyTop2, 0) + 1
            }
            for (p in 0 until k) {
                for (q in 0 until k) {
                    if (p != q) {
                        val key = PairKey(order[p], p + 1, order[q], q + 1)
                        pairAllCounts[key] = pairAllCounts.getOrDefault(key, 0) + 1
                    }
                }
            }
        }

        val probsByPlace = Array(k) { i ->
            DoubleArray(k) { j -> placeCounts[i][j] / nSim }
        }
        val pairTop2Probs = pairTop2Counts.mapValues { it.value.toDouble() / nSim }
        val pairAllProbs = pairAllCounts.mapValues { it.value.toDouble() / nSim }
        return SimulationStats(probsByPlace, pairTop2Probs, pairAllProbs)
    }

    fun updateAthleteParamsLinearly(
        athletes: List<Athlete>,
        raceResults: List<RaceResult>,
        memoryWindow: Int = 5
    ) {
        val raceMap = raceResults.associateBy { it.name }
        for (athlete in athletes) {
            raceMap[athlete.name]?.let { result ->
                athlete.history.add(result)
            }
        }
        for (athlete in athletes) {
            val hist = athlete.history
            if (hist.isEmpty()) continue
            val recent = if (hist.size > memoryWindow) hist.takeLast(memoryWindow) else hist
            val W = recent.size
            if (W == 1) {
                val onlyFt = recent[0].finishTime
                athlete.mu = onlyFt
                athlete.sigma1 = 0.1 * onlyFt
                athlete.sigma2 = 0.15 * onlyFt
                continue
            }
            val rev = recent.reversed() // самый свежий в начале
            val alpha = 1.0 / (W - 1)
            val weights = rev.indices.map { i -> max(1.0 - i * alpha, 0.0) }
            val sW = weights.sum()
            val finishTimes = rev.map { it.finishTime }
            val muNew = finishTimes.zip(weights).sumOf { (ft, w) -> ft * w } / sW
            val varNew = finishTimes.zip(weights).sumOf { (ft, w) ->
                val diff = ft - muNew
                w * diff * diff
            } / sW
            val stdNew = sqrt(varNew)
            athlete.mu = muNew
            athlete.sigma1 = 0.7 * stdNew
            athlete.sigma2 = 1.3 * stdNew
        }
    }
}
