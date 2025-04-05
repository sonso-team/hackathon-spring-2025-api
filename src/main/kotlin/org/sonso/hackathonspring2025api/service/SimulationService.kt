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

private const val N_SIM = 1000
private const val NO_HISTORY_RACES = 3

@Service
class SimulationService(
    private val historyRepo: RaceHistoryRepository
) {
    private val logger = LoggerFactory.getLogger(SimulationService::class.java)
    private val random = Random()

    // =========================================
    //  1) Пауза между забегами, текущее состояние
    // =========================================

    var nextRaceStartTime: Long = 0L
    var isRaceRunning = false
    val RACE_PAUSE_MS = 10_000L  // 10 секунд
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

        if (athletes.isEmpty()) {
            // Инициализируем, если ещё не делали этого
            initializeAthletes()
            nextRaceStartTime = now
        }

        return if (!isRaceRunning) {
            // ЗАБЕГ НЕ ИДЁТ
            if (now >= nextRaceStartTime) {
                // Пора стартовать новый
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

    private fun updateRaceOneStep(): Boolean {
        val k = athletes.size
        if (finishTimes == null) return true

        val dt = 1.0
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
        return finishTimes!!.all { it != null }
    }

    private fun finishRace() {
        logger.info("ФИНИШ забега №$raceIndexCounter")
        isRaceRunning = false
        nextRaceStartTime = System.currentTimeMillis() + RACE_PAUSE_MS

        // Считаем итоги
        val runnersInfo = finishTimes!!.mapIndexed { idx, ft -> idx to (ft ?: Double.POSITIVE_INFINITY) }
            .sortedBy { it.second }
        val results = mutableListOf<RaceResult>()
        runnersInfo.forEachIndexed { rank, pair ->
            val idx = pair.first
            val tfin = pair.second
            results.add(RaceResult(athletes[idx].name, tfin, rank + 1))
        }

        // ВАЖНО: ПЕРВЫЕ N ЗАБЕГОВ БЕЗ УЧЁТА ИСТОРИИ
        // Если мы уже сделали не меньше N забегов, тогда начинаем обновлять параметры:
        if (raceIndexCounter >= NO_HISTORY_RACES) {
            updateAthleteParamsLinearly(athletes, results, memoryWindow = 25)
        } else {
            // Либо вообще пропускаем обновление,
            // либо используем "пустой" update, как хотите
            logger.info("  -> Пропускаем обновление mu/sigma (raceIndex=$raceIndexCounter < $NO_HISTORY_RACES)")
        }

        // Сохраняем в Redis запись об этом забеге
        val record = RaceHistoryRecord(
            raceIndex = raceIndexCounter,
            results = results
        )
        historyRepo.addRecord(record)

        raceIndexCounter += 1

        distances = null
        finishTimes = null
    }

    // =========================================
    //  4) Сборка DTO для фронта: SYNC и UPDATE
    // =========================================

    private fun buildSyncResponse(): RaceResponse {
        return RaceResponse(
            type = ResponseType.SYNC,
            remainBefore = Date(), // просто текущее время
            history = loadLast10History(),   // показываем последние 10 забегов
            isRunning = true,
            lastResults = emptyList(),       // при SYNC не отправляем
            currentRun = buildCurrentRun()
        )
    }

    private fun buildUpdateResponse(): RaceResponse {
        val remain = Date(nextRaceStartTime)
        val lastRes = if (!isRaceRunning) {
            // последний забег = самая свежая запись в Redis
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
     * Собрать список HistoryItem для текущего состояния забега (probabilities и т.д.),
     * включая заполнение pairProbabilities из pairTop2Probs.
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
            // Прогоняем clamp+norm
            val finalProbs = clampAndNormalizeProbs(raw)

            val rawP1 = finalProbs[0]
            val rawP2 = finalProbs[1]
            val rawP3 = finalProbs[2]
            val rawP4 = finalProbs[3]
            val rawP5 = finalProbs[4]
            val rawP6 = finalProbs[5]

            // Сумма гарантированно=1, никаких 0.0 или 1.0
            val pTop2 = rawP1 + rawP2
            val pTop3 = pTop2 + rawP3

            val pairProb = buildPairProbabilities(i, stats)

            out.add(
                RaceResponse.HistoryItem(
                    id = athletes[i].name.lowercase(),
                    place = calcCurrentPlace(i),
                    progress = distances!![i],
                    probabilities = RaceResponse.Probabilities(
                        pos1 = rawP1,
                        pos2 = rawP2,
                        pos3 = rawP3,
                        pos4 = rawP4,
                        pos5 = rawP5,
                        pos6 = rawP6,
                        inThree = pTop3,
                        inTwo = pTop2
                    ),
                    pairProbabilities = pairProb
                )
            )
        }
        return out
    }

    /**
     * Для одного спортсмена имеем сырые вероятности rawP[0..5].
     * 1) Clamp: зажимаем их в диапазон [0.001..0.999]
     * 2) Нормируем, чтобы сумма снова была 1
     * 3) (Опционально) округляем по желанию
     *
     * Возвращаем DoubleArray из 6 вероятностей, которые суммируются в 1, и каждая в [0.001..0.999].
     */
    private fun clampAndNormalizeProbs(rawP: DoubleArray): DoubleArray {
        // 1) Зажимаем
        val minVal = 0.001
        val maxVal = 0.999
        val clamped = rawP.map { p ->
            when {
                p < minVal -> minVal
                p > maxVal -> maxVal
                else -> p
            }
        }

        // 2) Нормируем
        val sum = clamped.sum()
        // если sum=0 (вдруг все были 0?), чтобы не делить на 0
        if (sum <= 0) {
            // равномерно распределим
            return DoubleArray(6) { 1.0 / 6.0 }
        }

        val normalized = clamped.map { it / sum }

        // 3) (Опционально) округлим, например, до 4 знаков (в расчёте)
        // но тогда сумма может уйти от 1.
        // Можно округлять только при отображении.
        return normalized.toDoubleArray()
    }


    /**
     * Для спортсмена i возвращает PairProbabilities,
     * т.е. вероятность (i=1-е, j=2-е) для j="a","b","c","d","e","f".
     * Считаем, что athletes[0] = "A", [1]="B", ..., [5]="F".
     */
    private fun buildPairProbabilities(
        i: Int,
        stats: SimulationStats
    ): RaceResponse.PairProbabilities {
        // Найдём индексы спортсменов A..F
        // Если у вас порядок другой, скорректируйте!
        val indexA = athletes.indexOfFirst { it.name == "A" }
        val indexB = athletes.indexOfFirst { it.name == "B" }
        val indexC = athletes.indexOfFirst { it.name == "C" }
        val indexD = athletes.indexOfFirst { it.name == "D" }
        val indexE = athletes.indexOfFirst { it.name == "E" }
        val indexF = athletes.indexOfFirst { it.name == "F" }

        // Функция достаёт stats.pairTop2Probs[(i, j)] или 0.0
        fun prob(i1: Int, i2: Int): Double {
            return stats.pairTop2Probs[Pair(i1, i2)] ?: 0.0
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

    private fun calcCurrentPlace(i: Int): Int {
        val sorted = distances!!.withIndex().sortedByDescending { it.value }
        return sorted.indexOfFirst { it.index == i } + 1
    }

    /**
     * Возвращаем последние 10 забегов из Redis (на фронт в поле history).
     */
    private fun loadLast10History(): List<List<RaceResponse.HistoryItem>> {
        // Берём из Redis последние 10 записей
        val last10 = historyRepo.findLastNRecords(10).sortedBy { it.raceIndex }
        // Превращаем каждый RaceHistoryRecord в список HistoryItem
        return last10.map { record ->
            record.results.sortedBy { it.place }.map { r ->
                RaceResponse.HistoryItem(
                    id = r.name.lowercase(),
                    place = r.place
                )
            }
        }
    }

    // =========================================
    //  5) Логика обновления параметров (учёт последних 25 забегов)
    // =========================================

    fun updateAthleteParamsLinearly(
        athletes: List<Athlete>,
        raceResults: List<RaceResult>,
        memoryWindow: Int
    ) {
        // добавляем текущий забег в history
        val raceMap = raceResults.associateBy { it.name }
        for (athlete in athletes) {
            raceMap[athlete.name]?.let { athlete.history.add(it) }
        }

        // Чтобы учесть ровно 25 последних забегов – берём из history только хвост
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
    }

    // =========================================
    //  6) Хелперы
    // =========================================

    private fun initializeAthletes() {
        val names = listOf("A", "B", "C", "D", "E", "F")
        athletes.clear()
        athletes.addAll(names.map { name ->
            Athlete(
                name = name,
                mu = 10.0 + random.nextDouble() * 2.0,
                sigma1 = 0.3,
                sigma2 = 0.6,
                history = mutableListOf()
            )
        })
        logger.info("Спортсмены инициализированы: ${athletes.map { it.name }}")
    }

    private fun skewedNormal(mu: Double, sigma1: Double, sigma2: Double): Double {
        val z = random.nextGaussian()
        return if (z >= 0) mu + z * sigma2 else mu + z * sigma1
    }

    private fun simulateFuturePositions(
        currentDistances: DoubleArray,
        currentTime: Double,
        athletes: List<Athlete>
    ): SimulationStats {
        val k = athletes.size
        val placeCounts = Array(k) { DoubleArray(k) { 0.0 } }
        val pairTop2Counts = mutableMapOf<Pair<Int, Int>, Int>()
        val pairAllCounts = mutableMapOf<PairKey, Int>()

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
}
