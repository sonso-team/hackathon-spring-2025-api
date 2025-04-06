package org.sonso.hackathonspring2025api.service

import org.slf4j.LoggerFactory
import org.sonso.hackathonspring2025api.dto.*
import org.sonso.hackathonspring2025api.dto.response.RaceResponse
import org.sonso.hackathonspring2025api.dto.response.ResponseType
import org.sonso.hackathonspring2025api.repository.RaceHistoryRepository
import org.springframework.stereotype.Service
import java.util.Date
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Количество прогонов Монте-Карло для оценки вероятностей
 */
private const val N_SIM = 1000

/**
 * Первые NO_HISTORY_RACES забегов не учитываем историю
 * (не пересчитываем mu, sigma1, sigma2)
 */
private const val NO_HISTORY_RACES = 3

@Service
class SimulationService(
    private val historyRepo: RaceHistoryRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val random = Random()

    // =========================================
    //  1) Пауза между забегами, текущее состояние
    // =========================================

    var nextRaceStartTime: Long = 0L
    var isRaceRunning = false
    val RACE_PAUSE_MS = 10_000L  // 10 секунд

    // raceIndexCounter зададим = количеству уже сохранённых забегов, чтобы не перезапускать с нуля
    private var raceIndexCounter = historyRepo.findAllRecords().lastIndex

    private val athletes = mutableListOf<Athlete>()

    // Текущие показатели забега
    private var distances: DoubleArray? = null
    private var finishTimes: Array<Double?>? = null
    private var currentTimeSeconds = 0.0

    // =========================================
    //  2) Метод, вызываемый каждую секунду шедуллером
    // =========================================

    fun runScheduledLogic(): RaceResponse {
        val now = System.currentTimeMillis()

        // Если спортсменов нет, инициализируем
        if (athletes.isEmpty()) {
            initializeAthletes()
            nextRaceStartTime = now
        }

        return if (!isRaceRunning) {
            // ЗАБЕГ НЕ ИДЁТ
            if (now >= nextRaceStartTime) {
                // Стартуем новый
                startNewRace()
                buildSyncResponse() // только что стартовали
            } else {
                // Ждём старта
                buildUpdateResponse()
            }
        } else {
            // ЗАБЕГ ИДЁТ
            val raceFinished = updateRaceOneStep()
            if (raceFinished) {
                finishRace()
                buildUpdateResponse()
            } else {
                buildSyncResponse()
            }
        }
    }

    // =========================================
    //  3) Старт, шаг, финиш забега
    // =========================================

    private fun startNewRace() {
        logger.info("СТАРТ нового забега №$raceIndexCounter")
        isRaceRunning = true
        distances = DoubleArray(athletes.size) { 0.0 }
        finishTimes = arrayOfNulls(athletes.size)
        currentTimeSeconds = 0.0
    }

    /**
     * Посекундное обновление забега: прибавляем скорость тем,
     * кто ещё не финишировал. Возвращает true, если все добежали.
     */
    private fun updateRaceOneStep(): Boolean {
        val k = athletes.size
        if (finishTimes == null) return true

        val dt = 1.0
        for (i in 0 until k) {
            if (finishTimes!![i] == null) {
                // Базовая скорость (не максимальная, а именно средняя на дистанцию 100м)
                val baseSpeed = 100.0 / athletes[i].mu

                // Флуктуации
                val fluct = skewedNormal(0.0, baseSpeed * 0.1, baseSpeed * 0.15)
                var stepSpeed = baseSpeed + fluct
                // Не допускаем отрицательную скорость
                if (stepSpeed < 0) stepSpeed = 0.0

                val dOld = distances!![i]
                val dNew = dOld + stepSpeed * dt
                if (dNew >= 100.0) {
                    // Финишируем
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

        return finishTimes!!.all { it != null }
    }

    private fun finishRace() {
        logger.info("ФИНИШ забега №$raceIndexCounter")
        isRaceRunning = false
        nextRaceStartTime = System.currentTimeMillis() + RACE_PAUSE_MS

        // Формируем итоги
        val runnersInfo = finishTimes!!.mapIndexed { idx, ft -> idx to (ft ?: Double.POSITIVE_INFINITY) }
            .sortedBy { it.second }
        val results = mutableListOf<RaceResult>()
        runnersInfo.forEachIndexed { rank, pair ->
            val idx = pair.first
            val tfin = pair.second
            results.add(RaceResult(athletes[idx].name, tfin, rank + 1))
        }

        // Первые N забегов без учёта истории
        if (raceIndexCounter >= NO_HISTORY_RACES) {
            updateAthleteParamsLinearly(athletes, results, memoryWindow = 25)
        } else {
            logger.info(" -> Пропускаем обновление mu/sigma (raceIndex=$raceIndexCounter < $NO_HISTORY_RACES)")
        }

        // Сохраняем запись в Redis
        val record = RaceHistoryRecord(
            raceIndex = raceIndexCounter,
            results = results
        )
        historyRepo.addRecord(record)

        raceIndexCounter += 1

        // Сбрасываем временные массивы
        distances = null
        finishTimes = null
    }

    // =========================================
    //  4) Сборка DTO для фронта: SYNC и UPDATE
    // =========================================

    private fun buildSyncResponse(): RaceResponse {
        return RaceResponse(
            type = ResponseType.SYNC,
            remainBefore = Date(), // текущее время
            history = loadLast10History(),
            isRunning = true,
            lastResults = emptyList(), // SYNC -> не даём результаты
            currentRun = buildCurrentRun()
        )
    }

    private fun buildUpdateResponse(): RaceResponse {
        val remain = Date(nextRaceStartTime)
        val lastRes = if (!isRaceRunning) {
            // последний забег = самая свежая запись
            val lastRace = historyRepo.findLastNRecords(1).firstOrNull()
            lastRace?.results?.map { r ->
                RaceResponse.HistoryItem(
                    id = r.name.lowercase(),
                    place = r.place
                )
            } ?: emptyList()
        } else emptyList()

        return RaceResponse(
            type = ResponseType.UPDATE,
            remainBefore = remain,
            history = loadLast10History(),
            isRunning = isRaceRunning,
            lastResults = lastRes,
            currentRun = if (isRaceRunning) buildCurrentRun() else emptyList()
        )
    }

    /**
     * Собираем список HistoryItem для текущего состояния забега (probabilities + pairProbabilities).
     */
    private fun buildCurrentRun(): List<RaceResponse.HistoryItem> {
        if (!isRaceRunning || distances == null) return emptyList()

        val stats = simulateFuturePositions(
            currentDistances = distances!!,
            currentTime = currentTimeSeconds,
            athletes = athletes
        )

        val out = mutableListOf<RaceResponse.HistoryItem>()
        for (i in athletes.indices) {
            // Сырые вероятности занять места 1..6
            val raw = stats.probsByPlace[i]
            val finalProbs = clampAndNormalizeProbs(raw)

            val p1 = finalProbs[0]
            val p2 = finalProbs[1]
            val p3 = finalProbs[2]
            val p4 = finalProbs[3]
            val p5 = finalProbs[4]
            val p6 = finalProbs[5]

            val pTop2 = p1 + p2
            val pTop3 = pTop2 + p3

            val pairProb = buildPairProbabilities(i, stats)

            out.add(
                RaceResponse.HistoryItem(
                    id = athletes[i].name.lowercase(),
                    place = calcCurrentPlace(i),
                    progress = distances!![i],
                    probabilities = RaceResponse.Probabilities(
                        pos1 = p1,
                        pos2 = p2,
                        pos3 = p3,
                        pos4 = p4,
                        pos5 = p5,
                        pos6 = p6,
                        inThree = pTop3,
                        inTwo = pTop2
                    ),
                    pairProbabilities = pairProb
                )
            )
        }
        return out
    }

    // =========================================
    //  5) Логика обновления параметров (учёт последних 25 забегов)
    // =========================================

    fun updateAthleteParamsLinearly(
        athletes: List<Athlete>,
        raceResults: List<RaceResult>,
        memoryWindow: Int
    ) {
        // добавляем текущий забег
        val raceMap = raceResults.associateBy { it.name }
        for (athlete in athletes) {
            raceMap[athlete.name]?.let { athlete.history.add(it) }
        }

        // Пересчитываем mu, sigma1, sigma2
        for (athlete in athletes) {
            val hist = athlete.history
            if (hist.isEmpty()) continue

            // последние memoryWindow
            val recent = if (hist.size > memoryWindow) hist.takeLast(memoryWindow) else hist
            val W = recent.size
            if (W == 1) {
                // если всего 1 забег
                val onlyFt = recent[0].finishTime
                athlete.mu = onlyFt
                athlete.sigma1 = 0.1 * onlyFt
                athlete.sigma2 = 0.15 * onlyFt
            } else {
                // линейное взвешивание
                val rev = recent.reversed()
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

            // --- КРИТИЧЕСКИ ВАЖНО: ЗАЖИМАЕМ mu, sigma1, sigma2 ---
            // чтобы никто не улетел в скорость 1c/100м
            clampAthleteParams(athlete)
        }

        // жёстко сбрасывать
        initializeAthletes()
    }

    /**
     * Если mu ушло ниже 8.0, ставим 8.0 (100м за 8с).
     * Если выше 15.0, ставим 15.0 (100м за 15с).
     * sigma1,2 тоже зажимаем, чтобы не были безумно большими.
     */
    private fun clampAthleteParams(athlete: Athlete) {
        if (athlete.mu < 9.0) {
            athlete.mu = 9.0
        }
        if (athlete.mu > 13.0) {
            athlete.mu = 13.0
        }

        // sigma1, sigma2: не больше половины mu (примерно)
        // чтобы флуктуации не были слишком огромными
        val maxStd = athlete.mu * 0.5
        if (athlete.sigma1 > maxStd) {
            athlete.sigma1 = maxStd
        }
        if (athlete.sigma2 > maxStd) {
            athlete.sigma2 = maxStd
        }
        // Если хотите, можно и нижнюю границу задать (например, sigma>0.05).
        // ...
    }

    // =========================================
    //  6) Хелперы
    // =========================================

    private fun initializeAthletes() {
        // Вместо 10..12 сужаем коридор. Например, 9.5..10.5, чтобы все были ~10с
        val names = listOf("A", "B", "C", "D", "E", "F")
        athletes.clear()
        athletes.addAll(names.map { name ->
            val muInit = 9.5 + random.nextDouble()  // 9.5..10.5
            Athlete(
                name = name,
                mu = muInit,
                sigma1 = muInit * 0.07, // 7%
                sigma2 = muInit * 0.12, // 12%
                history = mutableListOf()
            )
        })
        logger.info("Спортсмены инициализированы: ${athletes.map { it.name }}")
    }

    private fun skewedNormal(mu: Double, sigma1: Double, sigma2: Double): Double {
        val z = random.nextGaussian()
        return if (z >= 0) mu + z * sigma2 else mu + z * sigma1
    }

    /**
     * Функция, которая симулирует будущее распределение мест при данном currentDistances
     * и currentTime. Делает N_SIM прогонов.
     */
    private fun simulateFuturePositions(
        currentDistances: DoubleArray,
        currentTime: Double,
        athletes: List<Athlete>
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

        repeat(N_SIM) {
            val simDistances = currentDistances.copyOf()
            val finishedTime = DoubleArray(k) { Double.POSITIVE_INFINITY }

            for (i in 0 until k) {
                if (simDistances[i] >= 100.0) {
                    finishedTime[i] = currentTime
                } else {
                    var fullTime = skewedNormal(athletes[i].mu, athletes[i].sigma1, athletes[i].sigma2)
                    if (fullTime < 0.1) fullTime = 0.1
                    val ratioDone = min(simDistances[i] / 100.0, 1.0)
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
            // Заполняем pairAllCounts (пока не используем)
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
            DoubleArray(k) { j -> placeCounts[i][j] / N_SIM }
        }
        val pairTop2Probs = pairTop2Counts.mapValues { it.value.toDouble() / N_SIM }
        val pairAllProbs = pairAllCounts.mapValues { it.value.toDouble() / N_SIM }
        return SimulationStats(probsByPlace, pairTop2Probs, pairAllProbs)
    }

    /**
     * Зажимаем вероятности по местам, чтобы не было 0.0 или 1.0.
     */
    private fun clampAndNormalizeProbs(rawP: DoubleArray): DoubleArray {
        val minVal = 0.001
        val maxVal = 0.999
        val clamped = rawP.map { p ->
            when {
                p < minVal -> minVal
                p > maxVal -> maxVal
                else -> p
            }
        }

        val sum = clamped.sum()
        if (sum <= 0) {
            // равномерно
            return DoubleArray(clamped.size) { 1.0 / clamped.size }
        }
        // нормируем
        return clamped.map { it / sum }.toDoubleArray()
    }

    /**
     * Для спортсмена i возвращает PairProbabilities,
     * т.е. вероятность (i=1-е, j=2-е) для j="a","b","c","d","e","f".
     */
    private fun buildPairProbabilities(
        i: Int,
        stats: SimulationStats
    ): RaceResponse.PairProbabilities {
        val indexA = athletes.indexOfFirst { it.name == "A" }
        val indexB = athletes.indexOfFirst { it.name == "B" }
        val indexC = athletes.indexOfFirst { it.name == "C" }
        val indexD = athletes.indexOfFirst { it.name == "D" }
        val indexE = athletes.indexOfFirst { it.name == "E" }
        val indexF = athletes.indexOfFirst { it.name == "F" }

        fun prob(i1: Int, i2: Int): Double {
            // clamp чуток, чтобы не было ровно 0 или 1
            val raw = stats.pairTop2Probs[Pair(i1, i2)] ?: 0.0
            return if (raw < 0.001) 0.001 else if (raw > 0.999) 0.999 else raw
        }

        return RaceResponse.PairProbabilities(
            a = if (indexA != -1) prob(i, indexA) else 0.0,
            b = if (indexB != -1) prob(i, indexB) else 0.0,
            c = if (indexC != -1) prob(i, indexC) else 0.0,
            d = if (indexD != -1) prob(i, indexD) else 0.0,
            e = if (indexE != -1) prob(i, indexE) else 0.0,
            f = if (indexF != -1) prob(i, indexF) else 0.0,
        )
    }

    /**
     * Определяем, на каком месте (1..k) сейчас спортсмен i
     * по дистанции. Если одинаковая – пусть будет произвольный порядок.
     */
    private fun calcCurrentPlace(i: Int): Int {
        val sorted = distances!!.withIndex().sortedByDescending { it.value }
        return sorted.indexOfFirst { it.index == i } + 1
    }

    /**
     * Загружаем последние 10 забегов из Redis, преобразуем в DTO для фронта.
     */
    private fun loadLast10History(): List<List<RaceResponse.HistoryItem>> {
        val last10 = historyRepo.findLastNRecords(10).sortedBy { it.raceIndex }
        return last10.map { record ->
            record.results.sortedBy { it.place }.map { r ->
                RaceResponse.HistoryItem(
                    id = r.name.lowercase(),
                    place = r.place
                )
            }
        }
    }
}
